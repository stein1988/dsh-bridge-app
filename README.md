# DSH Bridge Android App

远程访问 [dsh-bridge](../dsh-bridge) 网页的 Android 客户端。

手机在外面时，用它在 WebView 里打开家里的 DSH 网页继续干活：首页维护链接记录，
扫码即可添加，登录密码加密保存在本机。

## 功能

| 功能 | 说明 |
|---|---|
| **纯黑首页** | 大标题 + 状态副标题 + 卡片列表；卡片显示「局域网/远程 · 地址」「● 可达/暂不可达 · 最近连接时间」，右侧溢出菜单与箭头 |
| **两个添加入口** | 首页下方并排两个按钮：左「添加链接」（手输地址）、右「扫码添加」 |
| 链接记录列表 | 按最近打开排序；已保存密码的条目带徽标 |
| **扫码页** | 相机实时扫码（自绘取景框、手电筒开关）+ **从相册选择图片识别** |
| 连通性探测 | 并发探测每条链接是否可达（任何 HTTP 响应都算可达，401 也说明服务在线） |
| 全屏会话页 | 无应用内标题栏，网页**铺满整屏**（edge-to-edge，系统栏透明覆盖）；支持网页发消息、传附件（文件选择器已接） |
| **右缘左滑返回** | 在会话页从屏幕**右缘**向左滑 → 回首页 |
| **记住密码 / 清除密码** | 每个链接单独保存 dsh-bridge 访问密码（打开时自动登录），也可随时清除 |
| **应用内更新** | 启动时静默检查 GitHub 最新 Release；底部按钮显示「发现新版本 vX」，点击后自动下载并拉起系统安装器；右下角常驻显示当前版本 |

> 界面为**固定深色**（纯黑）：不跟随系统浅色模式，避免黑底与浅色组件/图标冲突。

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

> **发布约定**：上传到 GitHub Release 的附件名需为 `dsh-bridge-app-<version>.apk`
> （例如 `dsh-bridge-app-1.1.0.apk`），tag 为 `v<version>`。
> App 的更新检查降级通路会按这个约定直接拼下载直链。

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

1. **添加链接**（首页下方并排两个按钮）
   - **扫码添加**：在电脑上打开 dsh-bridge 面板显示二维码，用右边的「扫码添加」扫它；
     扫码页支持相机实时识别、**手电筒**，以及**从相册选择**一张已保存的二维码图片来识别
     （相册入口不需要存储权限，走系统照片选择器）。
   - **添加链接**：手输地址，例如 `192.168.1.10:3082`（自动补 `http://`）。
   - 两种情况都会保存记录并直接进入会话页；扫到的 `?auth=` 免密 token 会被单独加密保管，
     首页不会明文显示。

2. **查看状态**
   - 卡片第二行显示「局域网 / 远程 · 地址」，第三行是「● 可达 / 暂不可达 · 最近连接：…」。
   - 绿点=可达、灰点=不可达、黄点=检测中；结果缓存 30 秒，回到首页不会反复探测。

3. **记住密码**
   - 卡片右侧「⋮」→「记住密码」，输入 dsh-bridge 的**访问密码**；
   - App 会先向服务端验证，通过后才保存（密码错误不会保存）；
   - 之后打开该链接会自动登录，不再出现登录页。

4. **清除密码**
   - 同一条记录「⋮」→「清除密码」。

5. **应用内更新**
   - 启动时自动检查一次 GitHub 最新版本，底部按钮变为「发现新版本 vX.Y.Z，点此更新」；
   - 点击后自动下载（按钮显示百分比），完成后拉起系统安装器；
   - 右下角常驻显示当前版本号；
   - 首次使用需允许「安装未知应用」，App 会直接跳到该授权页。

6. **返回首页**
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

- **会话页是真正的 edge-to-edge**：网页一直画到状态栏/导航栏下面，系统栏透明覆盖其上 ——
  也就是"背景铺满全屏、最上方仍有系统状态条"。系统栏区域由网页自己避让：把**原生量到的**
  状态栏/导航栏高度注入网页的 `--dsh-mobile-safe-top/bottom`（各设备 WebView 对
  `env(safe-area-inset-*)` 的支持不一致，dsh-bridge 移动端样式预留的 52px 顶栏需要这个兜底
  才不会钻到状态栏底下），同时补 `viewport-fit=cover`。

  **但软键盘（IME）必须由原生让位**：顶部系统栏可以不占 padding（网页自己画到状态栏下），
  键盘不行 —— 宿主输入区 `.wSkVaW_composerSeat` 是 `position: sticky; bottom: 0`，
  位置取决于其滚动容器的底边，而该容器高度来自 100% 链、最终取决于 WebView 高度；
  edge-to-edge 下窗口又不会为 IME 让出空间。所以只有原生按 IME 高度缩短 WebView，
  输入框才会随键盘上移。

  网页侧的两处 `visualViewport` 逻辑**都不能替代**它：桥接端 `client/index.js` 的键盘适配
  带 UA 守卫（`if (!/iPhone|iPad|iPod/.test(...)) return;`，只在 iOS 生效）；DSH 核心那段是
  `scrollIntoView` 辅助，只能把流内元素滚进可视区，无法移动 sticky/absolute bottom 的底部固定元素。

  状态栏/导航栏图标颜色跟随**网页主题**：优先读 DSH 写在
  `documentElement.style.colorScheme` 的 `light`/`dark`，读不到则按页面底色亮度推断 ——
  避免深色主题下"浅色图标压在浅色网页上"看不见。
- **首页仍按安全区收边**：首页是原生界面，系统栏区域显示纯黑背景即可，不需要内容延伸过去。
- **扫码页相反：预览全屏铺满**（含系统栏下方），只给顶部/底部控件加 insets padding ——
  相机预览留黑边会很突兀。
- **固定深色主题**：应用外壳用 `Theme.Material3.Dark`，不跟随系统浅色模式。纯黑设计下若跟随，
  系统浅色模式会把对话框/按钮渲染成浅色、状态栏图标也会变深色，压在黑底上直接看不见。
  会话页例外：那里的状态栏图标颜色跟随**网页**主题动态调整。
- **右缘手势区宽 24dp**：dsh-bridge 的 Tab 栏本身支持横向滑动，窄带可以避免劫持页面中部
  的横向手势。嫌不好触发可以调 `ui/EdgeBackLayout.kt` 的 `EDGE_WIDTH_DP` / `TRIGGER_DISTANCE_DP`。
- **连通性探测宽松判定**：只要服务端返回任何 HTTP 响应就算"可达"。dsh-bridge 开了访问认证时
  未登录请求本来就是 401，把它当"不可达"会让状态点永远是灰的、反而误导。
- **更新检查走两条通路**：优先 GitHub Releases API（能一次拿到版本号、更新说明、附件真实直链），
  失败时自动降级为 `/releases/latest` 的重定向（从 `Location` 头读出 tag，**没有速率限制**，
  再按附件命名约定拼直链）。之所以必须有兜底：未认证的 API 限额是 **60 次/小时/IP**，
  运营商 CGNAT 共享出口很容易被打满 —— 实测就撞到过 403。
  因此仓库的 Release 附件名**必须**保持 `dsh-bridge-app-<version>.apk` 这一约定，否则兜底通路拼不出直链。
- 未授权"安装未知应用"时，点更新会先引导去系统设置授权，避免下载完装不上白费流量。
- 未做多窗口/分屏特殊适配；未做离线缓存（远程访问场景默认在线）。

## 目录结构

```
app/src/main/java/com/dshbridge/app/
├── MainActivity.kt              首页：卡片列表 + 两个添加入口 + 连通性探测 + 版本更新
├── ScanActivity.kt              扫码页：相机实时扫码 + 从相册选图识别 + 手电筒
├── WebViewActivity.kt           会话页：全屏 WebView + 右缘左滑返回 + 自动登录
├── data/
│   ├── LinkRecord.kt            单条链接记录（含展示用脱敏地址、局域网/远程判定）
│   ├── LinkStore.kt             链接列表持久化（JSON + SharedPreferences）
│   └── CredentialVault.kt       密码/免密 token 的加密存储
├── net/
│   ├── BridgeAuth.kt            原生登录：POST /__dsh_bridge__/login
│   ├── WebViewCookies.kt        会话 cookie 注入 WebView
│   ├── Reachability.kt          连通性探测（供卡片状态点）
│   ├── UpdateChecker.kt         查 GitHub 最新 Release + 下载 APK
│   └── ApkInstaller.kt          拉起系统安装器（FileProvider）
├── ui/
│   ├── LinkAdapter.kt           卡片列表适配器
│   ├── ScanOverlayView.kt       自绘扫码取景框（库自带的无定制属性）
│   ├── EdgeBackLayout.kt        右缘左滑返回手势
│   └── Insets.kt                系统栏/输入法 insets 处理
└── util/
    ├── GalleryQrDecoder.kt      从相册图片解码二维码（ZXing core，含降采样与多角度重试）
    └── TimeText.kt              「刚刚 / N 分钟前 / N 天前」
```
