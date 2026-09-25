// gatewaynode —— 端到端测试里"电脑"那一端。
//
// 真实部署里电脑跑的是普通的 tailscaled，网关就监听在它的 100.x 地址上。
// 测试机上没有 TUN，所以这里用 tsnet 扮演电脑：加入同一个 tailnet，
// 把 tailnet 上 :9000 进来的连接转给本机真正的服务（一个 WebSocket 回声服务）。
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"

	"tailscale.com/tsnet"
)

func main() {
	dir := flag.String("state-dir", "", "")
	control := flag.String("control-url", "", "")
	backend := flag.String("backend", "127.0.0.1:19000", "")
	flag.Parse()
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	s := &tsnet.Server{Dir: *dir, Hostname: "galaxy-desk", ControlURL: *control,
		AuthKey: os.Getenv("TS_AUTHKEY"), Logf: func(string, ...any) {}}
	defer s.Close()
	st, err := s.Up(context.Background())
	if err != nil {
		log.Fatal(err)
	}
	ln, err := s.Listen("tcp", ":9000")
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("READY %s\n", st.TailscaleIPs[0])
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			b, err := net.Dial("tcp", *backend)
			if err != nil {
				return
			}
			defer b.Close()
			go io.Copy(b, c)
			io.Copy(c, b)
		}()
	}
}
