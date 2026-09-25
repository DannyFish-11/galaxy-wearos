package main

import (
	"context"
	"io"
	"log"
	"net"
	"sync"
	"time"
)

// forwarder：回环口上每接到一条 TCP 连接，就经 tailnet 拨一条到 target，两头对拷。
//
// 目标是**固定的一个** —— 这不是一个通用代理。手表上别的 App 连这个口，
// 也只能到达网关那一个端口，而网关那边还要设备令牌才认。
type forwarder struct {
	dial        func(ctx context.Context, network, addr string) (net.Conn, error)
	target      string
	dialTimeout time.Duration
}

func (f *forwarder) serve(ctx context.Context, ln net.Listener) error {
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go f.handle(ctx, c)
	}
}

func (f *forwarder) handle(ctx context.Context, local net.Conn) {
	defer local.Close()
	dctx, cancel := context.WithTimeout(ctx, f.dialTimeout)
	remote, err := f.dial(dctx, "tcp", f.target)
	cancel()
	if err != nil {
		log.Printf("tailnet: 拨 %s 失败：%v", f.target, err)
		return
	}
	defer remote.Close()
	pipe(local, remote)
}

// pipe：双向对拷，任一方向结束后半关闭对端写方向，两边都结束才返回。
// 直接两边一起 Close 会截断还在路上的最后一段数据（比如 WebSocket 的关闭帧）。
func pipe(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	cp := func(dst, src net.Conn) {
		defer wg.Done()
		io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		} else {
			dst.Close()
		}
	}
	go cp(a, b)
	go cp(b, a)
	wg.Wait()
}
