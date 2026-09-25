// galaxy-tailnet — 手表自己加入 tailnet 的那个小进程。
//
// 为什么是一个独立进程
// ====================
// Wear OS 把 VPN 授权做成了桩（frameworkpackagestubs 里的 VpnStub），任何基于
// VpnService 的 App —— 包括 Tailscale 官方 App —— 在手表上都拿不到授权。
// tsnet 是 Tailscale 的用户态实现：自带 WireGuard 与 TCP/IP 栈，不碰系统网卡，
// 所以不需要 VPN 授权。代价是它只服务于**自己进程里**拨出去的连接。
//
// 于是做成这样：手表 App 把本程序作为子进程拉起，本程序以用户态加入 tailnet，
// 在手表的回环口上开一个端口，把进来的每条 TCP 连接原样转发到网关在 tailnet
// 里的地址（100.x.y.z:9000）。App 那边照旧连 ws://127.0.0.1:<端口>/…，协议、
// 鉴权、重连一概不变 —— 它不需要知道自己走的是 tailnet。
//
// 数据是手表 ↔ 电脑端到端的 WireGuard；打洞成功就直连，打不通才经 DERP 中继
// （仍然是端到端加密）。会合点是你自建的 headscale。
//
// 与 App 的约定（stdin / stdout 各一行一个 JSON）
// ==============================================
// 参数：--state-dir --control-url --hostname --target --listen [--await-network]
// 环境变量：TS_AUTHKEY —— 一次性加入密钥，只在首次加入时需要；之后凭 state-dir
// 里的节点身份重连。不走命令行参数，免得出现在进程列表里。
//
// stdin（App → 本程序）：
//
//	{"cmd":"network","dns":["192.168.1.1"],"interfaces":[{"name":"wlan0","index":3,"mtu":1500,"up":true,"addrs":["192.168.1.23/24"]}]}
//
// Android 11+ 不许普通 App 用 netlink 枚举网卡，Go 的 net.Interfaces() 因此失败；
// 没有 /etc/resolv.conf，Go 的解析器也找不到 DNS。这两样只能由 App 从
// ConnectivityManager 读出来交给本程序（Tailscale 官方 Android 客户端也是这么做的）。
// 网络变化时 App 再发一次，本程序据此唤醒网络监视器。
// stdin 关闭 = App 进程没了 → 本程序退出，不留孤儿。
//
// stdout（本程序 → App）：
//
//	{"event":"starting"}
//	{"event":"needs_login"}                                    —— 没有节点身份也没有密钥，退出码 3
//	{"event":"ready","tailnet_ip":"100.64.0.7","listen":"127.0.0.1:41234","target":"100.64.0.1:9000"}
//	{"event":"error","message":"..."}                          —— 退出码 1
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"tailscale.com/tsnet"
)

const (
	exitError      = 1
	exitNeedsLogin = 3
)

type config struct {
	stateDir     string
	controlURL   string
	hostname     string
	target       string
	listen       string
	awaitNetwork bool
	verbose      bool
	upTimeout    time.Duration
}

func parseFlags(args []string) (config, error) {
	fs := flag.NewFlagSet("galaxy-tailnet", flag.ContinueOnError)
	var c config
	fs.StringVar(&c.stateDir, "state-dir", "", "节点身份与状态目录（App 私有目录）")
	fs.StringVar(&c.controlURL, "control-url", "", "headscale 地址，如 https://hs.example.com")
	fs.StringVar(&c.hostname, "hostname", "galaxy-watch", "本节点在 tailnet 里的名字")
	fs.StringVar(&c.target, "target", "", "网关在 tailnet 里的地址，如 100.64.0.1:9000")
	fs.StringVar(&c.listen, "listen", "127.0.0.1:0", "本机转发口（只许回环）")
	fs.BoolVar(&c.awaitNetwork, "await-network", false, "先等 App 发来网卡/DNS 再启动（Android 上必须）")
	fs.BoolVar(&c.verbose, "verbose", false, "把 tsnet 的内部日志打到 stderr（排障用）")
	fs.DurationVar(&c.upTimeout, "up-timeout", 90*time.Second, "加入 tailnet 的最长等待")
	if err := fs.Parse(args); err != nil {
		return c, err
	}
	switch {
	case c.stateDir == "":
		return c, errors.New("--state-dir 必填")
	case c.controlURL == "":
		// 不给就会默认连 Tailscale 官方的控制服务器 —— 自建场景下这等于悄悄换了会合点。
		return c, errors.New("--control-url 必填（不许默认落到官方控制服务器）")
	case c.target == "":
		return c, errors.New("--target 必填")
	}
	if err := checkLoopback(c.listen); err != nil {
		return c, err
	}
	if _, _, err := net.SplitHostPort(c.target); err != nil {
		return c, fmt.Errorf("--target 不是 host:port：%w", err)
	}
	return c, nil
}

// checkLoopback：转发口只许开在回环上。开在 0.0.0.0 上，同一个 Wi-Fi 里的任何
// 机器都能借这块表进你的 tailnet。
func checkLoopback(addr string) error {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("--listen 不是 host:port：%w", err)
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("--listen 必须是回环地址，收到 %q", host)
	}
	return nil
}

// hasNodeState：state-dir 里有没有已经加入过的节点身份。
func hasNodeState(dir string) bool {
	st, err := os.Stat(filepath.Join(dir, "tailscaled.state"))
	return err == nil && st.Size() > 0
}

func main() {
	out := newEmitter(os.Stdout)
	if err := run(os.Args[1:], os.Stdin, out); err != nil {
		var ec exitCode
		if errors.As(err, &ec) {
			os.Exit(int(ec))
		}
		out.emit(map[string]any{"event": "error", "message": err.Error()})
		os.Exit(exitError)
	}
}

type exitCode int

func (e exitCode) Error() string { return fmt.Sprintf("exit %d", int(e)) }

func run(args []string, stdin io.Reader, out *emitter) error {
	c, err := parseFlags(args)
	if err != nil {
		return err
	}
	// tsnet 默认把运行日志上传到 log.tailscale.com。自建 headscale 的意义就是
	// 不依赖外部服务 —— 这里在进程内强制关掉，不指望调用方记得设。
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")

	authKey := os.Getenv("TS_AUTHKEY")
	os.Unsetenv("TS_AUTHKEY") // 读进来就从环境里拿掉，别再被子组件看到
	if authKey == "" && !hasNodeState(c.stateDir) {
		out.emit(map[string]any{"event": "needs_login"})
		return exitCode(exitNeedsLogin)
	}
	if err := os.MkdirAll(c.stateDir, 0o700); err != nil {
		return fmt.Errorf("建状态目录失败：%w", err)
	}

	env := newNetEnv()
	firstNetwork := make(chan struct{})
	stdinClosed := make(chan struct{})
	go readCommands(stdin, env, firstNetwork, stdinClosed)
	if c.awaitNetwork {
		select {
		case <-firstNetwork:
		case <-stdinClosed:
			return errors.New("App 在发来网络信息之前就断开了")
		case <-time.After(10 * time.Second):
			return errors.New("10 秒内没收到 App 的网络信息（--await-network）")
		}
	}
	env.install()

	out.emit(map[string]any{"event": "starting"})
	s := &tsnet.Server{
		Dir:        c.stateDir,
		Hostname:   c.hostname,
		ControlURL: c.controlURL,
		AuthKey:    authKey,
		Logf:       func(string, ...any) {},
		UserLogf:   log.New(os.Stderr, "tailnet: ", 0).Printf,
	}
	if c.verbose {
		s.Logf = log.New(os.Stderr, "tsnet: ", log.Lmicroseconds).Printf
	}
	defer s.Close()

	ctx, cancel := signalContext()
	defer cancel()
	go func() {
		select {
		case <-stdinClosed:
			cancel()
		case <-ctx.Done():
		}
	}()

	upCtx, upCancel := context.WithTimeout(ctx, c.upTimeout)
	st, err := s.Up(upCtx)
	upCancel()
	if err != nil {
		if ctx.Err() != nil {
			return nil // 是被叫停的，不是失败
		}
		return fmt.Errorf("加入 tailnet 失败：%w", err)
	}
	env.attach(s)

	ln, err := net.Listen("tcp", c.listen)
	if err != nil {
		return fmt.Errorf("开转发口失败：%w", err)
	}
	defer ln.Close()

	ip := ""
	if len(st.TailscaleIPs) > 0 {
		ip = st.TailscaleIPs[0].String()
	}
	out.emit(map[string]any{
		"event":      "ready",
		"tailnet_ip": ip,
		"listen":     ln.Addr().String(),
		"target":     c.target,
	})

	fwd := &forwarder{dial: s.Dial, target: c.target, dialTimeout: 15 * time.Second}
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	if err := fwd.serve(ctx, ln); err != nil && ctx.Err() == nil {
		return err
	}
	return nil
}

func signalContext() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
}

// emitter：stdout 上一行一个 JSON。App 靠它知道进度，所以每行都要立刻刷出去。
type emitter struct{ w io.Writer }

func newEmitter(w io.Writer) *emitter { return &emitter{w: w} }

func (e *emitter) emit(v map[string]any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	fmt.Fprintf(e.w, "%s\n", b)
	if f, ok := e.w.(*os.File); ok {
		f.Sync()
	}
}
