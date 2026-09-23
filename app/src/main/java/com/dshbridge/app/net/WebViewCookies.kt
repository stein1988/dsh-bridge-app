package com.dshbridge.app.net

import android.webkit.CookieManager

/**
 * 把 [BridgeAuth] 拿到的会话令牌写进 WebView 的 cookie 存储。
 *
 * dsh-bridge 的会话 cookie 是 `dsh_bridge_auth=<token>; Path=/; HttpOnly; SameSite=Lax`
 * （30 天）。HttpOnly 只约束网页里的 JS，原生侧仍可写入；写进去之后 WebView 加载页面
 * 就是已登录状态，用户不会看到登录页。
 *
 * 必须在有 Looper 的线程（主线程）调用 —— CookieManager 的要求。
 */
object WebViewCookies {

    fun acceptCookies() {
        CookieManager.getInstance().setAcceptCookie(true)
    }

    fun applySession(pageUrl: String, sessionToken: String) {
        val origin = BridgeAuth.originOf(pageUrl) ?: return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setCookie(origin, "$COOKIE_NAME=$sessionToken; Path=/")
        manager.flush()
    }

    fun flush() {
        CookieManager.getInstance().flush()
    }

    private const val COOKIE_NAME = "dsh_bridge_auth"
}
