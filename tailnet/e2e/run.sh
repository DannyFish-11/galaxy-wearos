#!/usr/bin/env bash
# 手表直连 tailnet 的端到端验证 —— 全部在本机，用真的 headscale、真的 tsnet 节点。
#
#   headscale（自建会合点） ← 两个节点都向它登记
#   galaxy-desk  : tsnet 节点，扮演电脑，tailnet :9000 → 本机 WebSocket 回声服务
#   galaxy-watch : 就是要装进手表的 galaxy-tailnet，本机回环口 → tailnet → galaxy-desk:9000
#   client       : 扮演手表 App，连 ws://127.0.0.1:<转发口>/ws/device/watch-1
#
# 用法：HEADSCALE=/path/to/headscale ./run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
HS="${HEADSCALE:?设 HEADSCALE 为 headscale 可执行文件路径}"
W="$(mktemp -d)"
PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; [ -n "${KEEP:-}" ] && echo "保留现场：$W" || rm -rf "$W"; }
trap cleanup EXIT

cat > "$W/hs.yaml" <<YAML
server_url: http://127.0.0.1:18080
listen_addr: 127.0.0.1:18080
metrics_listen_addr: 127.0.0.1:19090
grpc_listen_addr: 127.0.0.1:50443
noise:
  private_key_path: $W/noise.key
prefixes:
  v4: 100.64.0.0/10
  v6: fd7a:115c:a1e0::/48
  allocation: sequential
derp:
  server:
    enabled: true
    region_id: 999
    region_code: local
    region_name: local
    stun_listen_addr: 127.0.0.1:13478
    private_key_path: $W/derp.key
    automatically_add_embedded_derp_region: true
    ipv4: 127.0.0.1
  urls: []
  paths: []
  auto_update_enabled: false
database:
  type: sqlite
  sqlite:
    path: $W/db.sqlite
dns:
  magic_dns: false
  override_local_dns: false
  nameservers:
    global: []
unix_socket: $W/hs.sock
unix_socket_permission: "0770"
log:
  level: warn
policy:
  mode: file
  path: ""
YAML

"$HS" -c "$W/hs.yaml" serve >"$W/hs.log" 2>&1 & PIDS+=($!)
for _ in $(seq 50); do "$HS" -c "$W/hs.yaml" users list >/dev/null 2>&1 && break; sleep 0.2; done
"$HS" -c "$W/hs.yaml" users create galaxy >/dev/null
UID_=$("$HS" -c "$W/hs.yaml" users list -o json | python3 -c 'import json,sys; print([u["id"] for u in json.load(sys.stdin) if u["name"]=="galaxy"][0])')
key() { "$HS" -c "$W/hs.yaml" preauthkeys create -u "$UID_" -e 10m -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])'; }

python3 "$HERE/echo_ws.py" 19000 & PIDS+=($!)

( cd "$HERE" && go build -o "$W/gatewaynode" ./gatewaynode && go build -o "$W/netmsg" ./netmsg )
( cd "$HERE/.." && go build -o "$W/galaxy-tailnet" . )

TS_AUTHKEY="$(key)" "$W/gatewaynode" --state-dir "$W/desk" --control-url http://127.0.0.1:18080 >"$W/desk.out" 2>"$W/desk.err" & PIDS+=($!)
for _ in $(seq 150); do grep -q READY "$W/desk.out" 2>/dev/null && break; sleep 0.2; done
DESK_IP=$(awk '/READY/{print $2}' "$W/desk.out")
[ -n "$DESK_IP" ] || { echo "电脑节点没起来"; cat "$W/desk.err" "$W/hs.log"; exit 1; }
echo "电脑节点 tailnet 地址：$DESK_IP"

network_msg() { "$W/netmsg"; }
run_watch() {  # $1 = 名字 ; 其余透传给 galaxy-tailnet
  local name=$1; shift
  mkfifo "$W/$name.in"
  "$W/galaxy-tailnet" "$@" ${VERBOSE:+--verbose} <"$W/$name.in" >"$W/$name.out" 2>"$W/$name.err" & PIDS+=($!)
  exec {FD}>"$W/$name.in"   # 扮演 App 握着 stdin；关掉它 = App 没了
  eval "${name}_FD=$FD"
  # 照 App 的做法把本机真实网卡交过去（App 从 ConnectivityManager 读）。
  # 只交回环口不行：tailscale 会判定"没网"，把与 headscale 的连接挂起。
  network_msg >&$FD
}
wait_ready() {
  for _ in $(seq 150); do grep -q '"ready"' "$W/$1.out" && return 0; sleep 0.2; done
  echo "$1 没到 ready"; cat "$W/$1.out" "$W/$1.err"; exit 1
}

echo "── 1. 首次加入：带一次性密钥 ──"
TS_AUTHKEY="$(key)" run_watch watch1 --state-dir "$W/watch" --control-url http://127.0.0.1:18080 \
  --hostname galaxy-watch --target "$DESK_IP:9000" --await-network
wait_ready watch1
cat "$W/watch1.out"
LISTEN=$(python3 -c 'import json,sys; print([json.loads(l) for l in open(sys.argv[1]) if "ready" in l][0]["listen"])' "$W/watch1.out")
python3 "$HERE/client_ws.py" "ws://$LISTEN/ws/device/watch-1"

echo "── 2. App 没了（stdin 关闭）→ 进程必须自己退出 ──"
WPID=${PIDS[-1]}
eval "exec ${watch1_FD}>&-"
for _ in $(seq 50); do kill -0 "$WPID" 2>/dev/null || break; sleep 0.2; done
if kill -0 "$WPID" 2>/dev/null; then echo "stdin 关了进程还在 —— 会成孤儿"; exit 1; fi
echo "已退出"

echo "── 3. 重启：不带密钥，凭已存的节点身份重新加入 ──"
run_watch watch2 --state-dir "$W/watch" --control-url http://127.0.0.1:18080 \
  --hostname galaxy-watch --target "$DESK_IP:9000" --await-network
wait_ready watch2
LISTEN=$(python3 -c 'import json,sys; print([json.loads(l) for l in open(sys.argv[1]) if "ready" in l][0]["listen"])' "$W/watch2.out")
python3 "$HERE/client_ws.py" "ws://$LISTEN/ws/device/watch-1"

echo "── 4. 新的状态目录、没有密钥 → needs_login，退出码 3 ──"
set +e
"$W/galaxy-tailnet" --state-dir "$W/fresh" --control-url http://127.0.0.1:18080 --target "$DESK_IP:9000" </dev/null >"$W/fresh.out" 2>&1
RC=$?
set -e
cat "$W/fresh.out"
[ "$RC" = 3 ] || { echo "退出码应为 3，得到 $RC"; exit 1; }

echo "── 5. 一次性密钥不能用第二次 ──"
K="$(key)"
TS_AUTHKEY="$K" run_watch watch3 --state-dir "$W/w3" --control-url http://127.0.0.1:18080 --target "$DESK_IP:9000" --await-network
wait_ready watch3
# 第二台握着 stdin 不放（像真的 App 那样），只让"密钥被拒"这一个原因能让它停下。
TS_AUTHKEY="$K" run_watch watch4 --state-dir "$W/w4" --control-url http://127.0.0.1:18080 --target "$DESK_IP:9000" \
  --await-network --up-timeout 15s
W4PID=${PIDS[-1]}
set +e
wait "$W4PID"; RC=$?
set -e
grep -q '"ready"' "$W/watch4.out" && { echo "同一把一次性密钥加入了第二台 —— 密钥泄露就等于门开着"; exit 1; }
grep -q '"error"' "$W/watch4.out" || { echo "第二台没有报错就退出了："; cat "$W/watch4.out"; exit 1; }
[ "$RC" = 1 ] || { echo "退出码应为 1，得到 $RC"; exit 1; }
N=$("$HS" -c "$W/hs.yaml" nodes list -o json | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
[ "$N" = 3 ] || { echo "headscale 里应当正好 3 个节点（电脑 + 两次合法加入），实际 $N"; exit 1; }
echo "第二次被拒：$(grep -o '"message":"[^"]*' "$W/watch4.out" | head -c 160)"

echo "ALL_E2E_PASSED"
