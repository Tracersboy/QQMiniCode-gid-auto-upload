# QQ小程序抓包 · 2.0（好友 GID 抓包即解析）

> 由 **huojiujian** 开源免费使用，二改请备注来源
> 源码地址：https://github.com/huojiujian2

一款 Android 本地抓包工具：抓取 QQ 小程序（QQ 农场）网关握手时的 `code` 与 `ver`，
并在抓包过程中**自动解析服务器下发的全部好友 GID**——无需 code 二次联网、无需手动翻好友列表。

- 纯本地运行：VPN 抓包 + 内置 MITM + 本地解析，不上传任何数据
- 服务器 → 客户端方向的游戏协议响应为明文，App 在转发的同时**只读嗅探**并即时解析
- 一次打开农场即可拿全量好友（实测 145 位，含 gid/昵称/等级/open_id），支持一键复制 JSON 数组导入机器人/服务器

## 功能

| 能力 | 说明 |
| --- | --- |
| 🔍 抓包 | 本地 VPN（tun2proxy）+ MITM 解密 `gate-obt.nqf.qq.com`，提取 `code=` 与版本号 |
| 👥 好友 GID 自动解析 | 嗅探 `SyncAll/GetAll/GetGameFriends/InteractRecords` 明文响应，自动排除自身与内置 NPC（小果 10001） |
| 📋 一键复制 | 「复制 GID」= JSON 数字数组，可直接导入机器人已知好友 GID 列表 |
| 🛡️ R8 混淆 | release 构建默认全量混淆 + 资源压缩 |
| 🔐 证书托管 | 自动生成本地 CA 并引导安装（一次性），MITM 解密用 |

## 工作原理

```
QQ 客户端 ──打开农场──▶ 自动发 SyncAll（带全量 open_id）
        │                服务器明文回 145 位好友
        ▼
App VPN(MITM) ── 解密后 u2c 泵 ──▶ WsFriendSniffer（只读嗅探下行 WS 帧）
        │
        └─▶ 识别 Login 得自身 gid；SyncAll/GetAll/GetGameFriends 得好友列表
             └─▶ 卡片实时展示 + 复制 JSON 数组
```

- 客户端 → 服务器方向（请求体加密）完全不触碰、不改动任何字节
- 服务器 → 客户端方向协议响应为明文（gatepb.Message 封装），本地可直接解码

## 构建

环境要求：

- JDK 17+
- Android SDK（compileSdk 37）
- NDK（release 变体 strip native 库需要；版本 r26d 已验证）

```bash
# debug（带日志面板，可“复制日志”排查）
./gradlew assembleDebug

# release（R8 全量混淆 + 资源压缩）
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/{debug,release}/app-*.apk`

## 使用

1. 安装 App → 启动 → 按提示安装本地 CA 证书（抓包解密必需，一次性）
2. 点「启动抓包」→ 授权 VPN → 打开一次 QQ 农场小程序
3. 回到 App：code 已捕获，「好友 GID」卡片自动显示解析出的全部好友（自动排除自身与内置 NPC）
4. 点「复制 GID」→ 得到 `[gid,gid,...]` JSON 数组，可导入你自己的机器人/服务器使用

> 提示：好友 GID 解析是“被动嗅探”，需要在抓包开启时打开一次农场；解析出的好友带
> `gid ↔ open_id` 映射（仅在内存与剪贴板，本 App 不落盘不联网）。

## 技术要点（便于二次开发）

- `vpn/CaptureVpnService.kt`：VPN 服务（独立 `:vpn` 进程，native 崩溃不拖垮 UI）
- `proxy/MitmTunnel.kt`：TLS 解密泵，u2c 方向挂载嗅探器
- `proxy/WsFriendSniffer.kt`：WS 下行帧嗅探 + 极简 protobuf 扫描（零第三方依赖），有 JVM 单测回归
- `crypto/`：本地 CA 生成（BouncyCastle）与信任自检
- `util/CodeBus.kt`：`:vpn` → 主进程跨进程事件通道

## 上传到 qq-farm-bot 面板

主界面底部新增「上传到 qq-farm-bot 面板」卡片，可把抓到的 `code` 直接配置进已部署的
qq-farm-bot Web 面板（登录/更新对应 QQ 账号）：

1. 填写 **面板 API 地址**（如 `http://192.168.1.10:3007`，面板默认端口 3007）、
   **管理 Token**、**账号备注**（必填，面板内同名账号会被更新 code 并重启，否则新建并自动启动）
2. Token 获取：登录面板后，浏览器开发者工具 → localStorage 里的 `admin_token`；
   或抓包面板请求，看请求头里的 `x-admin-token`
3. 点「填入已抓取的Code」把当前捕获的 code 填进 Code 输入框（也可手动粘贴）；
   打开「抓到Code后自动填入」开关（默认开）后，每次抓到新 code 会自动填入
4. 点「保存配置」可记住地址 / Token / 备注 / 开关状态（code 不会保存）；
   「验证Token」可测试连通性
5. 点「提交 Code」：App 以 `x-admin-token` 请求头调用 `POST {地址}/api/accounts`，
   结果显示在卡片底部状态栏与 Toast
6. 点「在线状态」：查询面板账号运行状态——填写备注时显示该账号的
   在线情况 / 昵称 / QQ 号，运行中还会附带等级与经验；备注留空则列出面板全部账号

## GitHub Actions 自动编译发布

仓库自带 `.github/workflows/android.yml`：

- push 到 `master` / `main`，或在 Actions 页面手动运行（workflow_dispatch）：
  自动跑单测并编译 debug + release APK，在运行结果的 **Artifacts** 里下载
- 打 `v*` 开头的 tag 并推送（如 `git tag v2.1.0 && git push origin v2.1.0`）：
  除 Artifacts 外还会自动创建 **GitHub Release** 并附带两个 APK、自动生成更新日志

## License

本项目为 **huojiujian** 开源。

- ✅ 允许：免费使用、学习研究、二次开发（请保留全部来源声明与文件头注释）
- ❌ 禁止：商用贩卖、闭源分发、抹除来源声明后二次发布

请遵守开源精神，二改请备注来源：https://github.com/huojiujian2
