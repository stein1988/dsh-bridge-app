# DSH Bridge Android App

远程访问 [dsh-bridge](../dsh-bridge) 网页的 Android 客户端。

手机在外面时，用它在 WebView 里打开家里的 DSH 网页继续干活：首页维护链接记录，
扫码即可添加，登录密码加密保存在本机。

## 功能

| 功能 | 说明 |
|---|---|
| 链接记录列表 | 首页列出所有保存的地址，按最近打开排序；显示主机名与"已记住密码"标记 |
| 扫码添加 | 扫 dsh-bridge 面板上的二维码直接添加并进入会话（免密 token 自动提取并加密保管） |
| 手动添加 | 也可手输地址（局域网 IP 或隧道域名），可命名 |
| 全屏会话页 | 无应用内标题栏，网页铺满可用区域；支持网页发消息、传附件（文件选择器已接） |
| **右缘左滑返回** | 在会话页从屏幕**右缘**向左滑 → 回首页 |
| **记住密码** | 每个链接单独保存 dsh-bridge 访问密码，打开时自动登录 |
| **清除密码** | 每个链接可随时清除已保存的密码 |

## 环境要求

- Android 8.0（API 26）及以上
- 构建需要：JDK 17～21、Android SDK（platform 35 + build-tools 35.0.0）

## 构建

### 方式一：Android Studio

直接 `Open` 本目录，等待 Gradle 同步完成后运行 `app` 配置即可。

### 方式二：命令行

```bash
# 指向你的 Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 或者用环境变量
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release 签名

仓库里**不含任何签名材料** —— `.gitignore` 已排除 `keystore.properties`、`keystore/`、`*.jks`、`*.keystore`。

### 首次配置

1）生成密钥库（`keytool` 随 JDK 提供）：

```bash
keytool -genkeypair -keystore keystore/dsh-bridge-app.jks -storetype PKCS12 \
  -alias dsh-bridge-app -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '<库口令>' -keypass '<库口令>' \
  -dname "CN=DSH Bridge App, OU=Android, O=你的名字, L=City, ST=Province, C=CN"
```

> PKCS12 密钥库中 `keyPassword` 必须与 `storePassword` 相同（这是格式限制，不是本项目的要求）。

2）复制模板并填入口令：

```bash
cp keystore.properties.example keystore.properties
# 编辑 keystore.properties 填入 storePassword / keyPassword
```

3）构建：

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 校验签名

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

本项目 release 证书 SHA-256 指纹（可用来核对 APK 是否出自本仓库）：

```
58:6F:72:17:D2:58:1F:FC:2D:06:C6:F2:CE:8C:86:80:B0:14:9D:91:AC:63:BD:E3:F1:47:E9:A0:AB:74:A8:57
```

签名方案：minSdk 26 起 `v2` 签名即可（`v1`/JAR 签名只对 API < 24 有必要），实测 `apksigner` 报告 `v1=false, v2=true`。

### ⚠️ 务必备份

`keystore/dsh-bridge-app.jks` 与 `keystore.properties` 请**离线备份**。密钥库一旦丢失，
就无法再用同一签名更新已安装的 App —— 只能卸载重装，本机保存的链接与密码会一并丢失。

`keystore.properties` 缺失时，release 会构建成**未签名** APK，便于他人 clone 后直接编译。

## 使用

1. **添加链接**
   - 在电脑上打开 dsh-bridge 面板，显示局域网/隧道二维码；
   - App 首页点右下角「添加链接」→「扫描二维码添加」，扫码后自动进入会话页。
   - 也可以「手动输入地址」，例如 `192.168.1.10:3082`（会自动补 `http://`）。

2. **记住密码**
   - 首页某条记录右侧「⋮」→「记住密码」，输入 dsh-bridge 的**访问密码**；
   - App 会先向服务端验证，通过后才保存（密码错误不会保存）；
   - 之后打开该链接会自动登录，不再出现登录页。

3. **清除密码**
   - 同一条记录「⋮」→「清除密码」。

4. **返回首页**
   - 会话页从**屏幕最右侧向左滑**（起手点需落在右缘 24dp 内，滑动超过 64dp 触发）；
   - 或用系统返回键：网页内有历史则先回退，到顶后再按一次回首页。

## 认证是怎么做的（实现说明）

dsh-bridge 的登录是一个干净的 JSON 接口（见 `dsh-bridge/lib/index.js` 的 `ProxyServer`）：

```
POST /__dsh_bridge__/login     body: {"password":"..."}
  200 + Set-Cookie: dsh_bridge_auth=<sessionToken>; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000
  401 + {"ok":false,"error":"访问密码错误"}
```

因此"记住密码"不需要去模拟网页填表：App 原生 POST 拿到会话令牌，再写进 WebView 的
`CookieManager`，页面加载出来就是已登录状态。这样实现的好处是**不依赖登录页的 DOM 结构**，
宿主改版也不会失效。

打开链接时的优先级：

1. **已保存的访问密码**（首选）：原生登录换 cookie，再加载页面。**与认证模式无关、永远可靠**，
   所以排在 token 前面。
2. **扫码免密 token**：二维码地址形如 `http://<ip>:3082/?auth=<secretToken>`。App 会把
   `auth` 参数从保存的地址里摘出来单独加密保管（首页因此不会明文显示 token），打开时再拼回去；
   服务端校验通过后会下发会话 cookie 并 302 到干净地址。
3. 两者都没有：正常加载页面，显示官方登录页，由用户手动输入，并提示一次可用首页的「记住密码」。

为什么密码优先于 token，而不是反过来 —— dsh-bridge 只在
`enabled && mode !== 'password_only' && token` 成立时才把 `?auth=` 拼进二维码，并且服务端在
`mode === 'password_only'` 时会**直接忽略** query token。也就是说 token 在某些配置下是
"看着有、其实不生效"的；而原生登录换 cookie 不受模式影响。

同理，**token 与密码是两件不同的机密**，App 分开加密保存：「清除密码」只清除访问密码，不影响
已保存的 token；删除链接才会把两者一起清掉。若登录被服务端 401 拒绝，App 会自动退回尝试 token。

### 认证模式对使用的影响

| 你的配置 | 实际效果 |
|---|---|
| `scope = public_only`（默认常见） | **局域网访问不需要密码**（直接进）；**隧道/公网访问需要密码** —— 这才是「记住密码」真正发挥作用的场景 |
| `scope = all` | 局域网与公网都需要密码 |
| `mode = password_only` | 二维码不含免密 token，只能靠密码 |
| `mode = token_and_password` | 二维码含 token，但密码同样可用（App 仍优先用密码） |

## 安全说明

- **访问密码**用 `EncryptedSharedPreferences` 保存（AES256-GCM 值加密 + AES256-SIV 键加密，
  主密钥在 Android Keystore 里）。若设备 Keystore 异常，会退回普通存储并**弹出提示**，
  不会静默降级。
- 凭据文件（`credentials.xml`）已从**云备份与换机迁移**中排除 —— 密钥绑定本机，
  备份过去也无法解密。
- `AndroidManifest.xml` 里 `usesCleartextTraffic="true"` 是**必需**的：dsh-bridge 的局域网
  地址是 `http://`，Android 9+ 默认禁止明文流量。若你只用 HTTPS 隧道，可以收紧为
  `networkSecurityConfig` 白名单。
- 会话 cookie 由服务端控制（默认 30 天）。"清除密码"只清除本机保存的密码，
  不影响已下发的会话；要彻底登出可在网页里登出。

## 已知取舍

- **不做 edge-to-edge 沉浸**：会话页把系统栏 insets 作为 padding 收在安全区内。原因是
  targetSdk 35 起 Android 15 会强制 edge-to-edge，而手工收边能在所有版本上得到一致结果，
  且不会与网页自身的 `env(safe-area-inset-*)` 叠加导致双重留白（dsh-bridge 移动端样式
  预留了 52px 顶栏）。想要内容顶到状态栏下，把 `ui/Insets.kt` 里的顶部 padding 去掉即可。
- **右缘手势区宽 24dp**：dsh-bridge 的 Tab 栏本身支持横向滑动，窄带可以避免劫持页面中部
  的横向手势。嫌不好触发可以调 `ui/EdgeBackLayout.kt` 的 `EDGE_WIDTH_DP` / `TRIGGER_DISTANCE_DP`。
- 未做多窗口/分屏特殊适配；未做离线缓存（远程访问场景默认在线）。

## 目录结构

```
app/src/main/java/com/dshbridge/app/
├── MainActivity.kt              首页：列表 + 扫码/手动添加 + 记住/清除密码
├── WebViewActivity.kt           会话页：全屏 WebView + 右缘左滑返回 + 自动登录
├── data/
│   ├── LinkRecord.kt            单条链接记录（含展示用脱敏地址）
│   ├── LinkStore.kt             链接列表持久化（JSON + SharedPreferences）
│   └── CredentialVault.kt       密码/免密 token 的加密存储
├── net/
│   ├── BridgeAuth.kt            原生登录：POST /__dsh_bridge__/login
│   └── WebViewCookies.kt        会话 cookie 注入 WebView
└── ui/
    ├── LinkAdapter.kt           列表适配器
    ├── EdgeBackLayout.kt        右缘左滑返回手势
    └── Insets.kt                系统栏/输入法 insets 处理
```
