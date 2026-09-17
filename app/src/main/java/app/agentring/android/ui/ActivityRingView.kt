package app.agentring.android.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * 仿 AgentRing 原生圆环指示器
 * 核心展示：剩余百分比、双层同心圆环（主窗口 + 次窗口）、顺滑动画、纯净居中百分比
 */
class ActivityRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 当前动画显示值 (0.0 - 100.0)
    private var displayPrimaryPercent: Float = 0f
    private var targetPrimaryPercent: Float = 100f

    private var displaySecondaryPercent: Float = 0f
    private var targetSecondaryPercent: Float = 100f
    private var hasSecondaryRing: Boolean = false

    private var primaryColor: Int = Color.parseColor("#617FA8")
    private var secondaryColor: Int = Color.parseColor("#4E6B93")
    private var trackColor: Int = Color.parseColor("#EDF0F5")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val primaryArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val secondaryArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val primaryBounds = RectF()
    private val secondaryBounds = RectF()

    private var primaryAnimator: ValueAnimator? = null
    private var secondaryAnimator: ValueAnimator? = null

    fun setColors(primary: Int, secondary: Int) {
        this.primaryColor = primary
        this.secondaryColor = secondary
        invalidate()
    }

    fun setValues(primaryRemainingPercent: Double, secondaryRemainingPercent: Double? = null, animate: Boolean = true) {
        val newPrimary = primaryRemainingPercent.toFloat().coerceIn(0f, 100f)
        val newSecondary = secondaryRemainingPercent?.toFloat()?.coerceIn(0f, 100f)

        hasSecondaryRing = (newSecondary != null)

        if (!animate) {
            displayPrimaryPercent = newPrimary
            targetPrimaryPercent = newPrimary
            if (newSecondary != null) {
                displaySecondaryPercent = newSecondary
                targetSecondaryPercent = newSecondary
            }
            invalidate()
            return
        }

        // 仅在目标值发生实质变化时触发动画，避免重复刷新导致的频繁闪烁
        if (Math.abs(newPrimary - targetPrimaryPercent) > 0.05f || displayPrimaryPercent == 0f) {
            primaryAnimator?.cancel()
            primaryAnimator = ValueAnimator.ofFloat(displayPrimaryPercent, newPrimary).apply {
                duration = 500
                interpolator = AppleSpringInterpolator()
                addUpdateListener {
                    displayPrimaryPercent = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            targetPrimaryPercent = newPrimary
        }

        if (newSecondary != null) {
            if (Math.abs(newSecondary - targetSecondaryPercent) > 0.05f || displaySecondaryPercent == 0f) {
                secondaryAnimator?.cancel()
                secondaryAnimator = ValueAnimator.ofFloat(displaySecondaryPercent, newSecondary).apply {
                    duration = 500
                    interpolator = AppleSpringInterpolator()
                    addUpdateListener {
                        displaySecondaryPercent = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
                targetSecondaryPercent = newSecondary
            }
        }
    }

    /**
     * 仿 macOS / Apple Watch 物理弹簧插值器
     * 与 SwiftUI .spring(response: 0.42, dampingFraction: 0.78) 动力学特性严格一致：
     * 起始平滑发力，自然阻尼收敛，带有 ~2% 极轻微回弹质感。
     */
    private class AppleSpringInterpolator : android.view.animation.Interpolator {
        override fun getInterpolation(input: Float): Float {
            if (input <= 0f) return 0f
            if (input >= 1f) return 1f
            val t = input.toDouble() * 0.46
            val omegaD = 9.36
            val decay = 11.67
            val env = Math.exp(-decay * t)
            val v = 1.0 - env * (Math.cos(omegaD * t) + (decay / omegaD) * Math.sin(omegaD * t))
            return v.toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        if (size <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f

        // 仿 macOS agentRing 最新 HIG 规范：线宽加粗至约 12%，内外环预留 4.5% 呼吸间距
        val strokeWidth = size * 0.12f
        val gap = size * 0.045f

        // 1. 绘制外圈 (Primary Ring)
        val outerRadius = (size - strokeWidth) / 2f
        primaryBounds.set(
            centerX - outerRadius,
            centerY - outerRadius,
            centerX + outerRadius,
            centerY + outerRadius
        )

        trackPaint.strokeWidth = strokeWidth
        trackPaint.color = trackColor
        canvas.drawArc(primaryBounds, -90f, 360f, false, trackPaint)

        primaryArcPaint.strokeWidth = strokeWidth
        primaryArcPaint.color = primaryColor
        val primarySweep = (displayPrimaryPercent / 100f) * 360f
        if (primarySweep >= 0.5f) {
            canvas.drawArc(primaryBounds, -90f, primarySweep, false, primaryArcPaint)
        } else {
            // 0% 用量圆环保留占位端点（12 点钟位置以 round 线帽绘制与线宽同一直径的起点圆点）
            canvas.drawArc(primaryBounds, -90f, 0.1f, false, primaryArcPaint)
        }

        // 2. 如果存在次级窗口，绘制内圈 (Secondary Ring)
        if (hasSecondaryRing) {
            val innerRadius = outerRadius - strokeWidth - gap
            if (innerRadius > strokeWidth / 2f) {
                secondaryBounds.set(
                    centerX - innerRadius,
                    centerY - innerRadius,
                    centerX + innerRadius,
                    centerY + innerRadius
                )

                trackPaint.strokeWidth = strokeWidth
                canvas.drawArc(secondaryBounds, -90f, 360f, false, trackPaint)

                secondaryArcPaint.strokeWidth = strokeWidth
                secondaryArcPaint.color = secondaryColor
                val secondarySweep = (displaySecondaryPercent / 100f) * 360f
                if (secondarySweep >= 0.5f) {
                    canvas.drawArc(secondaryBounds, -90f, secondarySweep, false, secondaryArcPaint)
                } else {
                    // 0% 用量圆环保留占位端点
                    canvas.drawArc(secondaryBounds, -90f, 0.1f, false, secondaryArcPaint)
                }
            }
        }

        // 最新版 agentRing 规范：移除圆环中心百分比大字，圆环仅作纯视觉仪表，百分比数值由下方明细行统一承载
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        primaryAnimator?.cancel()
        secondaryAnimator?.cancel()
    }
}
