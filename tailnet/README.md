# galaxy-tailnet

手表自己加入 tailnet 的用户态进程。**出门在外、只带手表时，手表直连电脑靠它。**

- 为什么是独立进程、为什么不用 Tailscale App：见 `main.go` 顶部。
- 手表 App 怎么管它：`app/src/main/java/com/galaxy/wear/network/TailnetDaemon.kt`。
- 钥匙从哪来：配对时由网关签发（v2 仓 `core/headscale_join.py`），一次性、10 分钟有效。

## 构建

随 APK 自动构建（`app/build.gradle.kts` 的 `buildTailnet`，需要 Go，版本见 `go.mod`）。
单独编：

```bash
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o libgalaxytailnet.so .
```

只编 64 位（arm64-v8a）。

## 测试

```bash
go test ./...                     # 单测
HEADSCALE=/path/to/headscale e2e/run.sh   # 真 headscale 端到端（本机起 headscale + 两个节点）
```

端到端覆盖：首次凭一次性钥匙加入并转发 WebSocket（含 200KB 大帧）；App 断开后进程自退；
不带钥匙凭已存身份重连；无身份无钥匙报 `needs_login`；同一把钥匙第二次被 headscale 拒绝。
