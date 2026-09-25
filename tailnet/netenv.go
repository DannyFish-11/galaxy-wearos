package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/netip"
	"sync"

	"tailscale.com/net/dnscache"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// netEnv：App 交过来的网卡列表与 DNS 服务器。
//
// 在 Android 上这两样本程序自己拿不到（见 main.go 顶部）。在普通 Linux 上
// App 不发时，回落到系统自己的 —— 本地测试就走这条。
type netEnv struct {
	mu       sync.Mutex
	supplied bool
	ifaces   []netmon.Interface
	dns      []netip.AddrPort
	srv      *tsnet.Server
}

func newNetEnv() *netEnv { return &netEnv{} }

type ifaceMsg struct {
	Name  string   `json:"name"`
	Index int      `json:"index"`
	MTU   int      `json:"mtu"`
	Up    bool     `json:"up"`
	Addrs []string `json:"addrs"`
}

type commandMsg struct {
	Cmd        string     `json:"cmd"`
	DNS        []string   `json:"dns"`
	Interfaces []ifaceMsg `json:"interfaces"`
}

// update 用一条 network 命令替换当前网络视图。解析不了的条目跳过，不整条拒绝 ——
// 一个格式怪的 IPv6 地址不该让整块表断网。
func (e *netEnv) update(m commandMsg) {
	var ifs []netmon.Interface
	for _, im := range m.Interfaces {
		if im.Name == "" {
			continue
		}
		flags := net.FlagMulticast
		if im.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		var addrs []net.Addr
		for _, a := range im.Addrs {
			p, err := netip.ParsePrefix(a)
			if err != nil {
				continue
			}
			addrs = append(addrs, &net.IPNet{
				IP:   p.Addr().AsSlice(),
				Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen()),
			})
		}
		ifs = append(ifs, netmon.Interface{
			Interface: &net.Interface{Index: im.Index, MTU: im.MTU, Name: im.Name, Flags: flags},
			AltAddrs:  addrs,
		})
	}
	var dns []netip.AddrPort
	for _, d := range m.DNS {
		if ap, err := netip.ParseAddrPort(d); err == nil {
			dns = append(dns, ap)
		} else if a, err := netip.ParseAddr(d); err == nil {
			dns = append(dns, netip.AddrPortFrom(a, 53))
		}
	}
	e.mu.Lock()
	e.supplied = true
	e.ifaces = ifs
	e.dns = dns
	srv := e.srv
	e.mu.Unlock()
	if srv != nil {
		if nm := srv.Sys().NetMon.Get(); nm != nil {
			nm.InjectEvent() // 网络变了：让它立刻重新探测，而不是等下一轮轮询
		}
	}
}

func (e *netEnv) interfaces() ([]netmon.Interface, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if !e.supplied {
		return systemInterfaces()
	}
	return append([]netmon.Interface(nil), e.ifaces...), nil
}

func systemInterfaces() ([]netmon.Interface, error) {
	ifs, err := net.Interfaces()
	if err != nil {
		// Android 11+ 上就是这里失败（netlink 被拒）。返回空表而不是错误：
		// 没有网卡信息时 tailscale 仍能靠 STUN 找到自己的公网端点。
		return nil, nil
	}
	out := make([]netmon.Interface, 0, len(ifs))
	for i := range ifs {
		out = append(out, netmon.Interface{Interface: &ifs[i]})
	}
	return out, nil
}

// dial：DNS 查询发给 App 交来的服务器。没交过就用系统默认。
func (e *netEnv) dialDNS(ctx context.Context, network, address string) (net.Conn, error) {
	e.mu.Lock()
	servers := append([]netip.AddrPort(nil), e.dns...)
	supplied := e.supplied
	e.mu.Unlock()
	var d net.Dialer
	if !supplied || len(servers) == 0 {
		return d.DialContext(ctx, network, address)
	}
	var last error
	for _, s := range servers {
		c, err := d.DialContext(ctx, network, s.String())
		if err == nil {
			return c, nil
		}
		last = err
	}
	return nil, last
}

// install：把网卡与 DNS 的来源换成本对象。必须在 tsnet 启动前调用。
func (e *netEnv) install() {
	netmon.RegisterInterfaceGetter(e.interfaces)
	r := &net.Resolver{PreferGo: true, Dial: e.dialDNS}
	net.DefaultResolver = r
	dnscache.Get().Forward = r
}

func (e *netEnv) attach(s *tsnet.Server) {
	e.mu.Lock()
	e.srv = s
	e.mu.Unlock()
}

// readCommands 读 stdin 上的命令，直到 EOF（= App 没了）。
func readCommands(r io.Reader, e *netEnv, firstNetwork chan<- struct{}, closed chan<- struct{}) {
	defer close(closed)
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1<<20)
	gotFirst := false
	for sc.Scan() {
		var m commandMsg
		if err := json.Unmarshal(sc.Bytes(), &m); err != nil {
			log.Printf("tailnet: 忽略一条解析不了的命令：%v", err)
			continue
		}
		switch m.Cmd {
		case "network":
			e.update(m)
			if !gotFirst {
				gotFirst = true
				close(firstNetwork)
			}
		default:
			log.Printf("tailnet: 忽略未知命令 %q", m.Cmd)
		}
	}
	if err := sc.Err(); err != nil && !errors.Is(err, io.EOF) {
		log.Printf("tailnet: 读 stdin 出错：%v", err)
	}
}
