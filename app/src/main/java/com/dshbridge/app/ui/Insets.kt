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
     * 与 [applySystemBarsPadding] 的区别：**顶部/侧边不加 padding**，网页一直画到状态栏与
     * 导航栏下面（这是 v1.0.1 修掉"上下白条"的做法）；但仍**消费 IME inset**，否则键盘会
     * 盖住输入框 —— 顶部系统栏与软键盘必须区别对待，不能一起省掉。
     *
     * 系统栏区域由网页自己用 `--dsh-mobile-safe-top/bottom` 避让：各设备 WebView 对
     * `env(safe-area-inset-*)` 的支持不一致，所以这里把**原生量到的真实高度**通过
     * [onSystemBarInsets] 回传，由调用方注入网页作为兜底（见 WebViewActivity）。
     */
    fun applyEdgeToEdge(root: View, onSystemBarInsets: (topPx: Int, bottomPx: Int) -> Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            // 软键盘弹起时，把 IME 高度从布局里让出来 —— 即 v1.0.0 / v1.0.1 的行为。
            //
            // 这一步**必须由原生做**，2026-09 实机确认（此前误判为"网页自己会处理"而删掉，
            // 结果键盘一弹输入框就被挡住）：
            //   1. 宿主输入区 `.wSkVaW_composerSeat` 是 `position: sticky; bottom: 0`
            //      （见 dsh-client-ui-conversation 的 CSS），位置由"滚动容器底边"决定，
            //      **不随滚动偏移变化** —— 所以滚动无法把它抬到键盘之上；
            //   2. 该容器高度来自 100% 链，最终取决于 WebView 高度（布局视口）；
            //   3. 本页是 edge-to-edge（`setDecorFitsSystemWindows(false)`），
            //      窗口不会为 IME 让出空间，IME 只能作为 inset 交给应用消费。
            // 于是只有原生缩短 WebView，输入框才会随之上移。
            //
            // 网页侧那两处 visualViewport 逻辑**都不能替代**它：
            //   - 桥接端 `client/index.js` 的键盘适配带 UA 守卫
            //     `if (!/iPhone|iPad|iPod/.test(navigator.userAgent)) return;` —— 只在 iOS 生效，
            //     Android 上第一行就返回；
            //   - DSH 核心那段是 scrollIntoView 辅助，只能把**流内元素**滚进可视区，
            //     无法重新定位 sticky/absolute bottom 的底部固定元素。
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, 0, 0, ime.bottom)

            onSystemBarInsets(bars.top, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 只把**顶部**系统栏/刘海高度作为 padding 加到某个控件上。
     *
     * 用于扫码页这类"内容要全屏铺满、但控件不能钻进状态栏"的场景：
     * 相机预览必须整屏，不能给根布局加 padding；只让顶栏控件自己让位即可。
     */
    fun applyTopInsetPadding(view: View) {
        // 先记下 XML 里声明的原始 padding：insets 回调可能触发多次，
        // 若每次都以"当前 padding"为基准叠加，会越加越多；覆盖式赋值又会丢掉 XML 里的留白。
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            target.setPadding(baseLeft, baseTop + top, baseRight, baseBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 只把**底部** insets（含软键盘）作为 padding 加到某个控件上。 */
    fun applyBottomInsetPadding(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(
                baseLeft,
                baseTop,
                baseRight,
                baseBottom + max(bars.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
