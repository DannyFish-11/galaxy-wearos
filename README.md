# Galaxy Wear OS

Galaxy 在手腕上的那一端。

手表是中心智能体的**成员**，不是另一块三态显示屏：

- 你对它说的话（语音、通知上的回复）→ 交给智能体；
- 智能体的话（消息、向你提问、需要你拍板的决策）→ 送到你的手腕上，你答，答复回到智能体；
- 手表向智能体**登记**，用一份设备自述说清「我是什么、能替你做什么、此刻做不了什么」，智能体自己看着决定让谁做什么（经 `devices__invoke` 让手表做事）；
- 和智能体**通话**；
- 出门在外，靠 App 里内嵌的用户态 tailnet 进程直连智能体（Wear OS 装不了系统 VPN）。

三态（静默 / 临界 / 显现）是智能体在电脑上的一种表达，**与手表无关**：手表既不上报它，也不显示它。首页显示的是
「和智能体连着没有、要不要重新配对、有几件事在等你」。

## 手表能替智能体做什么

登记之后，手表在能力上报里带一份设备自述（`self_description`，V2 `contracts/device_self_v1.json`）：一句形态描述、
做得了的动作（每个动作做了会怎样、要什么参数）、此刻做不了的动作和原因，以及几样设备级声明（能回答智能体的提问、
出门靠内嵌 tailnet）。中心不按「这是手表」做任何判断，只读这份自述。动作是封闭清单（见 `domain/AgentCommand.kt`）：

| 动作 | 作用 | 说明 |
|---|---|---|
| `notify` | 弹一条消息通知 | 通知权限被拒时如实回失败，不回「已送达」 |
| `haptic` | 震一下 | 只放行「提醒类」触感；点按确认这类伴随用户自己动作的反馈不能由智能体触发 |
| `get_status` | 报告手表当前状态 | 电量、是否在充电、连接状态、应用版本、通知权限是否可用 |
| `heart_rate` | 现测一次心率 | 测几秒、取中间值；表没戴好如实回「没测到」。要「身体传感器」权限 |
| `steps` | 今天走了多少步 | 带上从几点开始算的（今天第一次读到计步数之前的步算不进来）。要「体能活动」权限 |

没有传感器、没给权限的动作不进 `actions`，进 `unavailable` 并写明原因（智能体照实告诉你缺什么、去哪打开）；
用户在设置里改了权限，回到 App 时重报。智能体主动想跟你说的话，也走手表自己的 `notify` 动作。
同一个 `command_id` 只执行一次（测心率期间重发的也只测一次）；输入有长度上限；失败回原因而不是忽略。

**此刻的状态由手表自己报**，跟着心跳走（`payload.state`，V2 `contracts/device_state_v1.json`）：电量、充没充电、
屏幕亮不亮、在不在通话、能不能打扰，外加一句原因（比如「极可能在睡」）。每一份都是此刻的全部，中心整份替换；
手表说不准的（比如戴没戴着，现在测不出）就不报，中心当不知道，不替你猜。

身体数据：心率、运动、睡眠这些原始信号只在手表本地参与判断「能不能打扰」，离开手表的只有上面那几个键和一句只有词、
没有数值的原因。心率、步数只在智能体调 `heart_rate` / `steps` 时读一次、回那一次的数；不想让智能体读，就不给对应权限。

## 支持的设备

| 设备 | 状态 |
|---|---|
| Wear OS 3.0+（Pixel Watch、Galaxy Watch 4–7、OPPO Watch、小米 Watch 2 Pro 等） | ✅ 目标平台 |
| 小米 Watch S 系列 / 手环、华为 Watch GT 系列 / 手环、Apple Watch | ❌ 封闭系统，不能装第三方 APK |

只编 `arm64-v8a`（手表侧只支持 64 位）。

## 构建

### 环境要求

- JDK 17+，Android SDK（compileSdk 35）
- **Go**（版本见 `tailnet/go.mod`）：出门直连用的 tailnet 进程是 Go 程序，构建时编进 APK。没有 Go 会直接构建失败并说明原因，不会静默跳过
- **必须**把 `ufo-galaxy-android` 克隆为本仓库的**同级目录**——`settings.gradle.kts` 通过相对路径引入它的
  `shared-transport` 与 `shared-protocol` 两个模块：

  ```
  <工作区>/
  ├── galaxy-wearos/          # 本仓库
  └── ufo-galaxy-android/     # 必须同级存在
  ```

### 编译与安装

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest   # debug 包 + 单测
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Release 包

```bash
./gradlew :app:assembleRelease    # R8 + 资源收缩
```

没设签名环境变量时产出 `app-release-unsigned.apk`（能验证 R8，装不上）。要出可安装的正式包，设：

| 环境变量 | 含义 |
|---|---|
| `GALAXY_KEYSTORE_PATH` | 钥匙库文件路径（`*.jks` 已被 `.gitignore` 排除，**不要提交**） |
| `GALAXY_KEYSTORE_PASSWORD` | 钥匙库口令 |
| `GALAXY_KEY_ALIAS` / `GALAXY_KEY_PASSWORD` | 钥匙别名与口令 |

CI（`.github/workflows/wear-compile.yml`）每个 PR 都会构建 debug + release 并跑单测；打 `v*` tag 时上传 release APK。
要让 CI 产出已签名的包，在仓库 Secret 里配 `GALAXY_KEYSTORE_BASE64`（钥匙库的 base64）、`GALAXY_KEYSTORE_PASSWORD`、
`GALAXY_KEY_ALIAS`、`GALAXY_KEY_PASSWORD`。

## 首次配对

不再需要手填令牌：

1. 智能体出示配对码（6 位短码；在电脑上的 Galaxy 面板里）；
2. 手表：**设置** → 填网关地址（或点「自动发现网关」，同一 Wi-Fi 下用 mDNS 找到智能体）→ **与智能体配对** → 输入配对码；
3. 配对成功会拿到一枚 24 小时的能力令牌，快到期时手表自己续期；网关明确拒绝才会提示「需要重新配对」（首页出现入口）。

配对时如果智能体已配好自建 headscale 且它所在的机器已在 tailnet 里，手表还会拿到一把一次性进网钥匙——出门后靠它直连智能体。
智能体那边没入 tailnet 时不会发钥匙，配对完成页会说明原因（「出门直连未开启：智能体那边还没加入 tailnet」），在家的局域网直连不受影响。智能体一直在局域网里广播自己（mDNS），配了 headscale 就自动入 tailnet，都不看电脑是不是开了「跨设备」——那个开关只说电脑自己加不加入，从不限制智能体；手表配对即接入。

## 界面

- **首页**：连接状态（已连接 / 连接中 / 未连接 / 需要重新配对）、待处理条数；入口：语音、通话、会话、设备、设置
- **语音**：点一下 → 系统语音识别 → 发给智能体（一问一答）
- **通话**：和智能体实时通话（WebRTC）。失败原因会留在屏幕上
- **会话**：这块表上的完整对话时间轴（你说的和它说的），可清除记录
- **决策**：智能体需要你拍板时，通知和首页顶部的胶囊都会出现；点选项，或「语音回复」直接回答这一条决策
- **设备**：智能体当前能看到的设备
- **Tile**：表盘轮播里一眼看到连接状态

## 协议

AIP v3 over WebSocket（`/ws/device/{device_id}`），消息结构来自共享协议模块。手表发出的主要帧：

```jsonc
// 认证后登记（令牌在顶层）
{"version":"3.0","type":"device_register","device_id":"…","token":"…","device_type":"wearos","platform":"wearos", "payload":{…}}
// 报能力：设备自述 self_description（V2 以它为准）；supported_actions 等散字段过渡期照发
{"version":"3.0","type":"capability_report","device_id":"…","supported_actions":["notify","haptic","get_status"],
 "self_description":{"schema":"device_self/1","kind":"wearos","about":"戴在手腕上的手表……","actions":[…],"unavailable":[…],"declares":{…}}}
// 心跳带此刻的状态（整份替换上一份；不知道的键不带）
{"version":"3.0","type":"heartbeat","device_id":"…","payload":{"state":{"battery":0.62,"charging":false,"screen_on":true,"interruptible":"blocked","note":"极可能在睡"}}}
// 一问一答的语音；相关 id 用 correlation_id
{"type":"command","command":"voice_query","correlation_id":"cmd_7","payload":{"text":"开灯","source":"wear_os","session_id":"…"}}
// 回答一条决策
{"type":"command","command":"human_input","payload":{"decision_id":"…","selected_option":"approve"}}
// 对智能体下发动作的回话（command_id 在顶层，结果在 payload）
{"version":"3.0","type":"command_result","command_id":"…","payload":{"success":true,"…":"…"}}
```

## 项目结构

```
galaxy-wearos/
├── app/src/main/java/com/galaxy/wear/
│   ├── GalaxyWearApplication.kt   # 全局：AIP 客户端、重连、决策/消息分发、令牌续期
│   ├── MainActivity.kt            # 导航、首次权限
│   ├── auth/                      # 配对（短码）、令牌续期、配对地址与提示文案
│   ├── data/                      # AIPClient（WebSocket + 协议）、成员登记帧
│   ├── domain/                    # 纯逻辑：智能体动作执行器、首页状态、决策解析
│   ├── call/                      # 实时通话（WebRTC，前台服务承载）
│   ├── conversation/              # 会话记录
│   ├── network/                   # 内嵌 tailnet 进程、连接路径
│   ├── sensing/                   # 此刻的状态与可打扰性（心率/运动只在本地运算，随心跳报）
│   ├── service/                   # 常驻前台服务、通知
│   ├── receiver/                  # 开机自启、通知回复
│   ├── tile/                      # 表盘 Tile
│   └── ui/                        # Compose 界面、触觉词汇表、主题
├── tailnet/                       # 用户态 tailnet 进程（Go，tsnet）
├── build.gradle.kts
├── settings.gradle.kts
└── .github/workflows/wear-compile.yml
```

## 测试

`app/src/test` 里是 JVM 单测：纯逻辑直接跑；另有一批「源码守卫」，读源码 / 清单 / 配置本身，
钉住编译器和 debug 构建看不见的东西（权限、R8 规则、接线、三态没有悄悄长回来）。
这些守卫证明**声明存在**，不证明真机上的行为——Compose 界面、WebRTC 通话、tailnet 进程拉起、前台服务保活、
息屏重连需要真机验证。

## License

与 Galaxy 主项目相同。
