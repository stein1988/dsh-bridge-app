package com.dshbridge.app.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

object Insets {

    /**
     * 把系统栏 / 刘海 / 输入法的 insets 作为 **padding** 施加到根布局。
     *
     * 为什么这么做：targetSdk 35 起，Android 15 会**强制** edge-to-edge，传统的
     * "内容自动排在系统栏之内" 行为不再可靠。用 padding 自己把内容收进安全区，
     * 在旧版本与 Android 15+ 上表现一致，且永远不会与网页内容重叠。
     *
     * 副作用正好是我们想要的：WebView 自己看到的 `env(safe-area-inset-*)` 变为 0，
     * 于是 dsh-bridge 移动端样式里的 `--dsh-mobile-safe-top` 也为 0，它那 52px 顶栏
     * 正好贴在 WebView 顶部 —— 与在手机浏览器里访问时的观感一致，不会出现"双重留白"。
     *
     * 底部取 系统栏 与 输入法 的较大者：聊天输入框不会被软键盘顶住。
     */
    fun applySystemBarsPadding(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 会话页：内容**铺满整屏**（真正的 edge-to-edge），系统栏透明地覆盖在网页之上 ——
     * 也就是用户期望的"背景延伸到全屏，最上方仍有系统状态条"。
     *
     * 与 [applySystemBarsPadding] 的区别：这里**不给顶部/侧边留 padding**，网页会一直画到
     * 状态栏与导航栏下面；只把**软键盘**的高度作为底部 padding 让出来，否则聊天输入框会被
     * 键盘遮住。
     *
     * 代价是网页需要自己躲开状态栏/刘海。dsh-bridge 的移动端样式已经用
     * `--dsh-mobile-safe-top/bottom`（默认取 `env(safe-area-inset-*)`）做了预留，
     * 但不同设备/WebView 对 `env()` 的支持并不一致，所以这里把**原生量到的真实高度**
     * 通过 [onSystemBarInsets] 回传，由调用方注入网页作为兜底（见 WebViewActivity）。
     */
    fun applyEdgeToEdge(root: View, onSystemBarInsets: (topPx: Int, bottomPx: Int) -> Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // 只避让软键盘；系统栏区域交给网页自己绘制（这样背景才能铺满全屏）
            view.setPadding(0, 0, 0, ime.bottom)
            onSystemBarInsets(bars.top, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
