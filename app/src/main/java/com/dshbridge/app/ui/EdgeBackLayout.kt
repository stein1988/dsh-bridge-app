package com.dshbridge.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 会话页容器：实现「页面右侧左滑 → 返回首页」。
 *
 * 需求原文："在会话页面右侧左滑，就返回首页"，即手势从屏幕**右缘**起手、向左滑过阈值后触发。
 *
 * 为什么在根容器的 dispatchTouchEvent 里判定，而不是覆盖一个透明 View 到右缘：
 * 覆盖 View 会把右缘那条区域的**点击**一并吃掉（网页右侧的按钮就点不动了）。
 * 这里只在"按下点落在右缘窄带内 + 移动方向确为向左的水平滑动"时才触发返回：
 *   - 普通点击：不动，原样交给 WebView；
 *   - 纵向滚动：不动（要求水平位移明显大于垂直位移）；
 *   - 向右滑：立即放弃跟踪，不触发。
 *
 * 触发区默认只占右缘 [EDGE_WIDTH_DP]（24dp）一条窄带：dsh-bridge 的 Tab 栏本身支持
 * 横向滑动，窄带可以避免劫持页面中部的横向手势。
 */
class EdgeBackLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 触发返回时的回调（通常为 finish()） */
    var onBackGesture: (() -> Unit)? = null

    /** 起手点必须落在右缘这条窄带内，单位 px */
    var edgeWidthPx: Int = dp(EDGE_WIDTH_DP)

    /** 向左滑过这个距离才算触发，单位 px */
    var triggerDistancePx: Int = dp(TRIGGER_DISTANCE_DP)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var tracking = false
    private var fired = false
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                fired = false
                // 只有从右缘起手才进入候选状态
                tracking = ev.x >= width - edgeWidthPx
            }

            MotionEvent.ACTION_MOVE -> {
                if (tracking && !fired) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * HORIZONTAL_BIAS) {
                        if (dx > 0) {
                            // 向右滑：不是返回手势，放弃跟踪（事件照常下发）
                            tracking = false
                        } else if (-dx >= triggerDistancePx) {
                            fired = true
                            tracking = false
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onBackGesture?.invoke()
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val EDGE_WIDTH_DP = 24
        const val TRIGGER_DISTANCE_DP = 64

        /** 水平位移需明显大于垂直位移才算横滑，避免斜向滚动误触 */
        const val HORIZONTAL_BIAS = 1.5f
    }
}
