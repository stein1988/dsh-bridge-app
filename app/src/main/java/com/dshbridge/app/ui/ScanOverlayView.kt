package com.dshbridge.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.dshbridge.app.R
import kotlin.math.min

/**
 * 自绘扫码取景框。
 *
 * 为什么不用 zxing-android-embedded 自带的 `ViewfinderView`：它没有暴露任何 XML 可定制属性
 * （外观是 protected 硬编码字段，只能改库源码），默认那套"半透明蒙层 + 激光线"观感偏旧。
 * 这里把库自带的隐藏掉，自己画：四周压暗 + 四个圆角括号，配色跟随应用品牌色。
 *
 * 取景框是**纯视觉**的：库默认解码整幅预览，不依赖这个框，所以自绘不会影响识别率。
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val maskPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.scanner_mask)
        style = Paint.Style.FILL
    }

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val frame = RectF()
    private val path = Path()

    /** 取景框位置，供外部（如提示文案）对齐使用 */
    val frameRect: RectF get() = RectF(frame)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w, h) * FRAME_RATIO
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        frame.set(left, top, left + side, top + side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame.isEmpty) return

        // 框外压暗（四条矩形，避免用 Path 的奇偶填充规则踩坑）
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, maskPaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, maskPaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, maskPaint)

        // 四个圆角括号
        val arm = ARM_DP * density
        path.reset()
        path.moveTo(frame.left, frame.top + arm)
        path.lineTo(frame.left, frame.top)
        path.lineTo(frame.left + arm, frame.top)

        path.moveTo(frame.right - arm, frame.top)
        path.lineTo(frame.right, frame.top)
        path.lineTo(frame.right, frame.top + arm)

        path.moveTo(frame.right, frame.bottom - arm)
        path.lineTo(frame.right, frame.bottom)
        path.lineTo(frame.right - arm, frame.bottom)

        path.moveTo(frame.left + arm, frame.bottom)
        path.lineTo(frame.left, frame.bottom)
        path.lineTo(frame.left, frame.bottom - arm)

        canvas.drawPath(path, bracketPaint)
    }

    private companion object {
        /** 取景框边长占屏幕短边的比例 */
        const val FRAME_RATIO = 0.68f
        const val ARM_DP = 30f
    }
}
