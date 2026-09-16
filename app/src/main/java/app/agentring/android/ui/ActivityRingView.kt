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
 * 核心展示：剩余百分比、双层同心圆环（主窗口 + 次窗口）、顺滑动画
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

    private var primaryColor: Int = Color.parseColor("#10A37F")
    private var secondaryColor: Int = Color.parseColor("#059669")
    private var trackColor: Int = Color.parseColor("#232733")
    private var textColor: Int = Color.parseColor("#F3F4F6")
    private var subTextColor: Int = Color.parseColor("#9CA3AF")

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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = textColor
        isFakeBoldText = true
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = subTextColor
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

        primaryAnimator?.cancel()
        primaryAnimator = ValueAnimator.ofFloat(displayPrimaryPercent, newPrimary).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayPrimaryPercent = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        targetPrimaryPercent = newPrimary

        if (newSecondary != null) {
            secondaryAnimator?.cancel()
            secondaryAnimator = ValueAnimator.ofFloat(displaySecondaryPercent, newSecondary).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    displaySecondaryPercent = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            targetSecondaryPercent = newSecondary
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        if (size <= 0) return

        val strokeWidth = size * 0.09f
        val gap = strokeWidth * 0.35f

        val centerX = w / 2f
        val centerY = h / 2f

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
        if (primarySweep > 0.5f) {
            canvas.drawArc(primaryBounds, -90f, primarySweep, false, primaryArcPaint)
        }

        // 2. 如果存在次级窗口，绘制内圈 (Secondary Ring)
        if (hasSecondaryRing) {
            val innerStrokeWidth = strokeWidth * 0.75f
            val innerRadius = outerRadius - strokeWidth / 2f - gap - innerStrokeWidth / 2f
            if (innerRadius > 0) {
                secondaryBounds.set(
                    centerX - innerRadius,
                    centerY - innerRadius,
                    centerX + innerRadius,
                    centerY + innerRadius
                )

                trackPaint.strokeWidth = innerStrokeWidth
                canvas.drawArc(secondaryBounds, -90f, 360f, false, trackPaint)

                secondaryArcPaint.strokeWidth = innerStrokeWidth
                secondaryArcPaint.color = secondaryColor
                val secondarySweep = (displaySecondaryPercent / 100f) * 360f
                if (secondarySweep > 0.5f) {
                    canvas.drawArc(secondaryBounds, -90f, secondarySweep, false, secondaryArcPaint)
                }
            }
        }

        // 3. 绘制中心剩余百分比大字
        val mainTextSize = size * 0.22f
        textPaint.textSize = mainTextSize
        val percentText = "${displayPrimaryPercent.toInt()}%"

        val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f - (if (hasSecondaryRing) size * 0.04f else 0f)
        canvas.drawText(percentText, centerX, textY, textPaint)

        // 4. 绘制中心小字标签 "剩余"
        val subTextSize = size * 0.085f
        subTextPaint.textSize = subTextSize
        val labelY = textY + subTextSize * 1.35f
        canvas.drawText("剩余", centerX, labelY, subTextPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        primaryAnimator?.cancel()
        secondaryAnimator?.cancel()
    }
}
