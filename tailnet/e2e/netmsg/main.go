// netmsg —— 按 App 的格式打印本机的 network 命令（网卡 + DNS）。
//
// 手表上这一行由 App 从 ConnectivityManager 读出来发给 galaxy-tailnet；
// 测试机上用它代替。
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
)

func main() {
	ifs, err := net.Interfaces()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	type iface struct {
		Name  string   `json:"name"`
		Index int      `json:"index"`
		MTU   int      `json:"mtu"`
		Up    bool     `json:"up"`
		Addrs []string `json:"addrs"`
	}
	var out []iface
	for _, i := range ifs {
		addrs, _ := i.Addrs()
		var as []string
		for _, a := range addrs {
			as = append(as, a.String())
		}
		out = append(out, iface{i.Name, i.Index, i.MTU, i.Flags&net.FlagUp != 0, as})
	}
	b, _ := json.Marshal(map[string]any{"cmd": "network", "dns": []string{"127.0.0.53"}, "interfaces": out})
	fmt.Println(string(b))
}
