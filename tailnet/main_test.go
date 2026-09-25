package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestFlagsRequireAnExplicitControlServer(t *testing.T) {
	// 不给 control-url 时 tsnet 会默认连 Tailscale 官方控制服务器。
	// 自建场景下那等于悄悄换了会合点 —— 必须拒绝，而不是默认。
	_, err := parseFlags([]string{"--state-dir", "/x", "--target", "100.64.0.1:9000"})
	if err == nil || !strings.Contains(err.Error(), "control-url") {
		t.Fatalf("没有 control-url 却没被拒：%v", err)
	}
}

func TestListenMustBeLoopback(t *testing.T) {
	base := []string{"--state-dir", "/x", "--control-url", "https://hs", "--target", "100.64.0.1:9000"}
	for _, bad := range []string{"0.0.0.0:0", "192.168.1.5:1234", ":0", "[::]:0"} {
		if _, err := parseFlags(append(base, "--listen", bad)); err == nil {
			t.Errorf("--listen %s 被放行了 —— 同一个 Wi-Fi 里的机器能借这块表进 tailnet", bad)
		}
	}
	for _, good := range []string{"127.0.0.1:0", "[::1]:0"} {
		if _, err := parseFlags(append(base, "--listen", good)); err != nil {
			t.Errorf("--listen %s 被拒：%v", good, err)
		}
	}
}

func TestTargetMustBeHostPort(t *testing.T) {
	_, err := parseFlags([]string{"--state-dir", "/x", "--control-url", "https://hs", "--target", "100.64.0.1"})
	if err == nil {
		t.Fatal("没有端口的 target 被放行了")
	}
}

func TestNoKeyAndNoIdentityMeansNeedsLogin(t *testing.T) {
	// 没有密钥、也从没加入过：必须明确报 needs_login 并退出，
	// 而不是卡在"等人去浏览器里点登录链接" —— 手表上没有那个浏览器。
	os.Unsetenv("TS_AUTHKEY")
	var buf bytes.Buffer
	err := run([]string{
		"--state-dir", t.TempDir(), "--control-url", "https://hs.invalid", "--target", "100.64.0.1:9000",
	}, strings.NewReader(""), newEmitter(&buf))
	var ec exitCode
	if !errors.As(err, &ec) || int(ec) != exitNeedsLogin {
		t.Fatalf("应当以退出码 %d 结束，得到 %v", exitNeedsLogin, err)
	}
	var ev map[string]any
	if json.Unmarshal(bytes.TrimSpace(buf.Bytes()), &ev) != nil || ev["event"] != "needs_login" {
		t.Fatalf("stdout 应当是一行 needs_login，得到 %q", buf.String())
	}
}

func TestExistingIdentityDoesNotNeedAKey(t *testing.T) {
	dir := t.TempDir()
	if hasNodeState(dir) {
		t.Fatal("空目录被当成已加入")
	}
	os.WriteFile(filepath.Join(dir, "tailscaled.state"), []byte("{}"), 0o600)
	if !hasNodeState(dir) {
		t.Fatal("已有节点身份却被当成没加入 —— 每次重启都要一把新密钥")
	}
}

func TestAwaitNetworkGivesUpWhenAppGoesAway(t *testing.T) {
	// App 在交网络信息之前就没了：本进程必须退出，不能留成孤儿。
	t.Setenv("TS_AUTHKEY", "k")
	done := make(chan error, 1)
	go func() {
		done <- run([]string{
			"--state-dir", t.TempDir(), "--control-url", "https://hs.invalid",
			"--target", "100.64.0.1:9000", "--await-network",
		}, strings.NewReader(""), newEmitter(io.Discard))
	}()
	select {
	case err := <-done:
		if err == nil || !strings.Contains(err.Error(), "断开") {
			t.Fatalf("应当报 App 已断开，得到 %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("stdin 关了还在等")
	}
}

// ── 转发 ────────────────────────────────────────────────────────────────

func echoServer(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.(*net.TCPConn).CloseWrite() }()
		}
	}()
	return ln.Addr().String()
}

func TestForwarderCarriesBytesBothWaysToTheOneTarget(t *testing.T) {
	target := echoServer(t)
	var dialed []string
	f := &forwarder{
		target:      target,
		dialTimeout: time.Second,
		dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dialed = append(dialed, addr)
			var d net.Dialer
			return d.DialContext(ctx, network, addr)
		},
	}
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go f.serve(ctx, ln)

	c, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	msg := "GET /ws/device/w HTTP/1.1\r\n\r\n"
	c.Write([]byte(msg))
	c.(*net.TCPConn).CloseWrite()
	got, _ := io.ReadAll(c)
	if string(got) != msg {
		t.Fatalf("回来的不是发出去的：%q", got)
	}
	if len(dialed) != 1 || dialed[0] != target {
		t.Fatalf("只许拨那一个目标，实际拨了 %v", dialed)
	}
}

func TestForwarderDropsTheClientWhenTheTailnetDialFails(t *testing.T) {
	f := &forwarder{
		target:      "100.64.0.1:9000",
		dialTimeout: time.Second,
		dial: func(context.Context, string, string) (net.Conn, error) {
			return nil, errors.New("no route")
		},
	}
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go f.serve(ctx, ln)
	c, _ := net.Dial("tcp", ln.Addr().String())
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, err := bufio.NewReader(c).ReadByte()
	if err != io.EOF {
		// 拨不通时必须立刻断开，让 App 马上换下一条路，而不是挂着等超时。
		t.Fatalf("拨不通时应当立刻关掉本地连接，得到 %v", err)
	}
}

// ── 网络视图 ────────────────────────────────────────────────────────────

func TestNetworkCommandIsParsedAndBadEntriesSkipped(t *testing.T) {
	e := newNetEnv()
	e.update(commandMsg{
		Cmd: "network",
		DNS: []string{"192.168.1.1", "[fd00::1]:5353", "not-an-ip"},
		Interfaces: []ifaceMsg{
			{Name: "wlan0", Index: 3, MTU: 1500, Up: true, Addrs: []string{"192.168.1.23/24", "garbage", "fe80::1/64"}},
			{Name: "", Index: 9},
		},
	})
	ifs, err := e.interfaces()
	if err != nil || len(ifs) != 1 {
		t.Fatalf("应当只剩一块网卡，得到 %v %v", ifs, err)
	}
	if ifs[0].Name != "wlan0" || ifs[0].Flags&net.FlagUp == 0 || len(ifs[0].AltAddrs) != 2 {
		t.Fatalf("网卡解析错了：%+v", ifs[0])
	}
	if len(e.dns) != 2 || e.dns[0].String() != "192.168.1.1:53" || e.dns[1].String() != "[fd00::1]:5353" {
		t.Fatalf("DNS 解析错了：%v", e.dns)
	}
}

func TestSuppliedEmptyNetworkIsRespected(t *testing.T) {
	// App 明确说"现在没有网卡"（比如刚断网）时，不许回落去读系统的 ——
	// 在 Android 上那条路会失败或给出过时的结果。
	e := newNetEnv()
	e.update(commandMsg{Cmd: "network"})
	ifs, _ := e.interfaces()
	if len(ifs) != 0 {
		t.Fatalf("App 给的是空表，却用了别的：%v", ifs)
	}
}
