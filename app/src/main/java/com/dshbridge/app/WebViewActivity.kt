package com.dshbridge.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dshbridge.app.data.CredentialVault
import com.dshbridge.app.data.LinkRecord
import com.dshbridge.app.data.LinkStore
import com.dshbridge.app.databinding.ActivityWebviewBinding
import com.dshbridge.app.net.BridgeAuth
import com.dshbridge.app.net.WebViewCookies
import com.dshbridge.app.ui.Insets
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 会话页：全屏 WebView 展示 dsh-bridge 网页。
 *
 * 自动登录有两条路，按优先级：
 *  1. **扫码免密 token**（`?auth=<secretToken>`）：直接带参数打开，服务端会下发会话
 *     cookie 并 302 到干净地址；
 *  2. **已保存的访问密码**：先原生 `POST /__dsh_bridge__/login` 换 cookie 写进
 *     CookieManager，再打开页面 —— 用户完全看不到登录页。
 *
 * 若两条都不可用，页面上的官方登录页会正常显示；此时还会尝试一次 DOM 自动填表作为兜底
 * （仅一次，且密码被服务端 401 拒绝过就不再重试，避免反复提交错误密码）。
 *
 * 返回首页：右缘左滑（见 [com.dshbridge.app.ui.EdgeBackLayout]）；系统返回键在网页有历史时
 * 先回退历史，否则也回首页。
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding
    private lateinit var store: LinkStore
    private lateinit var vault: CredentialVault

    private var record: LinkRecord? = null

    /** DOM 兜底自动填表是否已尝试过（整个页面生命周期只做一次） */
    private var autoFillDone = false

    /** 密码已被服务端明确拒绝（401）：不再用同一密码去填表 */
    private var autoFillSuppressed = false

    private var missingPasswordHintShown = false

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** 原生量到的状态栏 / 导航栏高度。WindowInsets 的单位是**物理像素**，注入网页前必须换算 */
    private var safeTopDevicePx = 0
    private var safeBottomDevicePx = 0

    /** 页面是否已加载完成：insets 回调可能早于首次加载，避免对着空文档注入 */
    private var pageReady = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            fileChooserCallback = null
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFullscreen()

        store = LinkStore(this)
        vault = CredentialVault(this)

        val link = intent.getStringExtra(EXTRA_LINK_ID)?.let { store.find(it) }
        if (link == null) {
            finish()
            return
        }
        record = link

        WebViewCookies.acceptCookies()
        setupWebView()
        setupBackHandling()

        binding.edgeBack.onBackGesture = { finish() }
        binding.btnRetry.setOnClickListener {
            binding.errorView.visibility = View.GONE
            startSession(link)
        }

        startSession(link)
    }

    /**
     * 会话页做成真正的 edge-to-edge：网页一直画到状态栏/导航栏下面，系统栏透明覆盖其上，
     * 于是"背景延伸满屏、最上方仍有系统状态条"。
     *
     * 系统栏区域由网页自己避让：把原生系统栏高度注入网页（`--dsh-mobile-safe-top/bottom`），
     * 因为不同设备/WebView 对 `env(safe-area-inset-*)` 的支持不一致，注入真实值才能保证
     * dsh-bridge 那 52px 顶栏不会钻到状态栏底下。
     *
     * **软键盘（IME）必须由原生让位**：顶部系统栏可以不占 padding（网页自己画到状态栏下），
     * 但键盘不行 —— 宿主输入区是 `position: sticky; bottom: 0`，位置取决于滚动容器底边，
     * 只有缩短 WebView 才能让它上移。详见 ui/Insets.kt 里 applyEdgeToEdge 的说明。
     */
    @Suppress("DEPRECATION")
    private fun setupFullscreen() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 不要系统给透明栏加的半透明遮罩，否则又会出现一条灰边
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        Insets.applyEdgeToEdge(binding.root) { top, bottom ->
            safeTopDevicePx = top
            safeBottomDevicePx = bottom
            // 顶部进度条要避开状态栏，否则 3dp 细条会被压在状态栏下面看不见
            (binding.progress.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != top) {
                    lp.topMargin = top
                    binding.progress.layoutParams = lp
                }
            }
            if (pageReady) injectPageChrome(binding.webView)
        }
    }

    /**
     * WindowInsets 给的是**物理像素**，而 CSS 里的 `px` 是**逻辑像素**（即 dp）：
     * WebView 会按屏幕密度缩放。直接把物理像素当 CSS px 注入，等于把值放大 `density` 倍
     * （本机 2.75 倍）—— 曾导致会话顶栏被整体推下去一大截。
     */
    private fun toCssPx(devicePx: Int): Int =
        (devicePx / resources.displayMetrics.density).roundToInt()

    // ---- 会话启动 ----

    /**
     * 决定首次加载用哪条路。
     *
     * **密码优先于免密 token**，这不是随意排的：dsh-bridge 只有在
     * `enabled && mode !== 'password_only' && token` 时才把 `?auth=` 拼进二维码
     * （见 `lib/index.js` 的 `getStatus()`），并且服务端在
     * `mode === 'password_only'` 时会**直接忽略** query token
     * （见 `lib/auth/manager.js` 第 4 步的 `if (this.mode !== 'password_only')`）。
     * 也就是说 token 在某些配置下是"看着有、其实不生效"的，而原生登录换 cookie
     * 与认证模式无关、永远可靠 —— 所以有密码就先走密码。
     */
    private fun startSession(link: LinkRecord) {
        val password = vault.password(link.id)
        val token = vault.token(link.id)

        if (password.isNullOrBlank()) {
            // 没存密码：能靠的只有免密 token；都没有就正常打开（页面会要求手动登录）
            loadPage(link.url, token)
            return
        }

        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            when (val result = BridgeAuth.login(link.url, password)) {
                is BridgeAuth.Result.Ok -> {
                    WebViewCookies.applySession(link.url, result.sessionToken)
                    loadPage(link.url, token = null)
                }

                is BridgeAuth.Result.Unauthorized -> {
                    // 密码被服务端明确拒绝：不再拿同一个密码去填表，改为退回免密 token（若有）
                    autoFillSuppressed = true
                    toast(getString(R.string.autologin_failed, result.message))
                    loadPage(link.url, token)
                }

                is BridgeAuth.Result.Failed -> {
                    // 网络/协议问题，不代表密码错：仍打开页面，用户可手动登录
                    toast(getString(R.string.autologin_failed, result.message))
                    loadPage(link.url, token)
                }
            }
        }
    }

    private fun loadPage(url: String, token: String?) {
        binding.webView.loadUrl(if (token.isNullOrBlank()) url else withAuthToken(url, token))
    }

    /** 把免密 token 拼回地址（保存时被摘出来单独加密保管了） */
    private fun withAuthToken(url: String, token: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.queryParameter("auth") != null) return url
        return parsed.newBuilder().addQueryParameter("auth", token).build().toString()
    }

    // ---- WebView 配置 ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 多窗口关闭：target=_blank 的链接会在本 WebView 内打开，不会变成"点了没反应"
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString $UA_SUFFIX"
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return when (request.url.scheme?.lowercase()) {
                    "http", "https" -> false // 站内导航留在 WebView
                    else -> {
                        openExternally(request.url)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
                binding.errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.progress.visibility = View.GONE
                pageReady = true
                injectPageChrome(view)
                runLoginProbe(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                binding.progress.visibility = View.GONE
                binding.errorView.visibility = View.VISIBLE
                binding.tvErrorDetail.text = error.description
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                if (newProgress >= 100) binding.progress.visibility = View.GONE
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null
                    false
                }
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    // ---- 全屏（edge-to-edge）配套：安全区变量注入 + 状态栏图标配色 ----

    /**
     * 每次页面加载完成后注入两件事：
     *
     * 1. **安全区兜底**：把原生量到的状态栏/导航栏高度写进
     *    `--dsh-mobile-safe-top` / `--dsh-mobile-safe-bottom`。
     *    dsh-bridge 的移动端样式对这两个变量的默认值是 `env(safe-area-inset-*)`，
     *    但各设备/WebView 对 `env()` 的支持并不一致；注入真实值可保证它那 52px 顶栏
     *    与底部输入区不会钻到系统栏底下。顺带补上 `viewport-fit=cover`，
     *    让页面自己的 `env()` 也能拿到非零值。
     *
     * 2. **状态栏图标配色**：内容铺到状态栏下面后，图标颜色必须与网页底色形成对比，
     *    否则深色主题下会出现"浅色图标压在浅色网页上"看不见的情况。
     *    DSH 会把当前主题写到 `documentElement.style.colorScheme`（light/dark，
     *    见 dsh-client-ui-layout 的 apply()），直接读它最准；读不到时退回按底色亮度推断。
     */
    private fun injectPageChrome(view: WebView) {
        // 注意换算：注入的是 CSS px（= dp），不是 WindowInsets 的物理像素
        val topCss = toCssPx(safeTopDevicePx)
        val bottomCss = toCssPx(safeBottomDevicePx)

        val script = """
            (function(){
              try{
                var d = document.documentElement;
                d.style.setProperty('--dsh-mobile-safe-top', '${topCss}px');
                d.style.setProperty('--dsh-mobile-safe-bottom', '${bottomCss}px');

                var meta = document.querySelector('meta[name=viewport]');
                if (meta && String(meta.content).indexOf('viewport-fit') < 0) {
                  meta.content = meta.content + ',viewport-fit=cover';
                }

                // 优先用 DSH 自己写的主题标记
                var cs = (getComputedStyle(d).colorScheme || d.style.colorScheme || '').toLowerCase();
                if (cs.indexOf('dark') >= 0) return 'dark';
                if (cs.indexOf('light') >= 0) return 'light';

                // 兜底：按背景色亮度推断
                function rgb(c){
                  c = String(c || '').trim();
                  var m6 = /^#([0-9a-f]{6})${'$'}/i.exec(c);
                  if (m6) { var n = parseInt(m6[1],16); return [(n>>16)&255,(n>>8)&255,n&255,1]; }
                  var m3 = /^#([0-9a-f]{3})${'$'}/i.exec(c);
                  if (m3) { var h=m3[1]; return [parseInt(h[0]+h[0],16),parseInt(h[1]+h[1],16),parseInt(h[2]+h[2],16),1]; }
                  var g = /rgba?\(([^)]+)\)/.exec(c);
                  if (g) { var p = g[1].split(','); return [parseFloat(p[0])||0, parseFloat(p[1])||0, parseFloat(p[2])||0, p[3]===undefined?1:parseFloat(p[3])]; }
                  return null;
                }
                function lum(c){ var r = rgb(c); if (!r || r[3] === 0) return null; return (0.299*r[0] + 0.587*r[1] + 0.114*r[2]) / 255; }

                var l = lum(getComputedStyle(document.body).backgroundColor);
                if (l === null) l = lum(getComputedStyle(d).backgroundColor);
                if (l === null) l = lum(getComputedStyle(d).getPropertyValue('--dsw-alias-bg-base'));
                return (l === null) ? 'light' : (l < 0.5 ? 'dark' : 'light');
              }catch(e){ return 'light'; }
            })();
        """.trimIndent()

        view.evaluateJavascript(script) { raw ->
            // 网页是深色 -> 状态栏图标要用浅色，即 isAppearanceLightStatusBars = false
            val pageIsDark = raw?.trim('"') == "dark"
            val useDarkIcons = !pageIsDark
            WindowInsetsControllerCompat(window, binding.root).apply {
                isAppearanceLightStatusBars = useDarkIcons
                isAppearanceLightNavigationBars = useDarkIcons
            }
        }
    }

    // ---- 登录兜底 ----

    /**
     * 页面加载完成后探测登录表单。
     *
     *  - 有已保存密码且未尝试过：填表并提交（一次）；
     *  - 没有密码但页面确实是登录页：提示一次，告诉用户可用首页的「记住密码」。
     *
     * 注入的是 dsh-bridge 登录页的真实 DOM（`#loginForm` / `#password`，见
     * `lib/auth/login-template.js`）。这只是**兜底**——主路径是原生登录换 cookie。
     */
    private fun runLoginProbe(view: WebView) {
        val link = record ?: return
        val password = vault.password(link.id)
        val canFill = password != null && !autoFillDone && !autoFillSuppressed

        val script = if (canFill) {
            """
            (function(){
              try{
                var f=document.getElementById('loginForm');
                var p=document.getElementById('password');
                if(!f||!p){ return 'none'; }
                p.value=${JSONObject.quote(password)};
                p.dispatchEvent(new Event('input',{bubbles:true}));
                if(typeof f.requestSubmit==='function'){ f.requestSubmit(); } else { f.submit(); }
                return 'filled';
              }catch(e){ return 'error'; }
            })();
            """.trimIndent()
        } else {
            "(function(){ return document.getElementById('loginForm') ? 'form' : 'none'; })();"
        }

        view.evaluateJavascript(script) { raw ->
            when (raw?.trim('"')) {
                "filled" -> autoFillDone = true
                "form" -> if (password == null && !missingPasswordHintShown) {
                    missingPasswordHintShown = true
                    toast(getString(R.string.autologin_no_password))
                }
                else -> Unit
            }
        }
    }

    // ---- 其它 ----

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(uri.toString())
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        WebViewCookies.flush()
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LINK_ID = "link_id"
        private const val UA_SUFFIX = "DshBridgeApp/1.0"

        fun intent(context: Context, linkId: String): Intent =
            Intent(context, WebViewActivity::class.java).putExtra(EXTRA_LINK_ID, linkId)
    }
}
