package com.tapoutguardian.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The big circular action from the reference design: soft concentric rings around a solid disc. It is purely presentational:
 * the host decides the label/colour from the persisted workflow state and what a tap does (it only navigates to the screen that
 * owns the real confirmation, it never confirms anything itself). The slow pulse runs only while something needs the user.
 */
class RingButton(context: Context) : View(context) {
    var title: String = ""; private set
    var caption: String = ""; private set
    private var color = Ui.CORAL
    private var pulse = false

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 1).toFloat() }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 3).toFloat() }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = Ui.dp(context, 28).toFloat()
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = Ui.dp(context, 12).toFloat()
    }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null); isClickable = true }

    fun bind(title: String, caption: String, color: Int, pulse: Boolean, description: String) {
        this.title = title; this.caption = caption; this.color = color; this.pulse = pulse
        contentDescription = description
        updateAnimation(); invalidate()
    }

    fun setTitleOnly(t: String) { if (t != title) { title = t; invalidate() } }

    private fun updateAnimation() {
        if (pulse && isAttachedToWindow) { if (!animator.isStarted) animator.start() } else { animator.cancel(); phase = 0f }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        val size = minOf(MeasureSpec.getSize(w).takeIf { it > 0 } ?: Ui.dp(context, 280), Ui.dp(context, 280))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - Ui.dp(context, 6)
        // soft neumorphic rings: white discs with a gentle shadow on the warm page
        listOf(1f to "#FFFFFF", .82f to "#FBF9F7", .66f to "#FFFFFF").forEach { (k, c) ->
            ringPaint.color = Color.parseColor(c)
            ringPaint.setShadowLayer(Ui.dp(context, 10).toFloat(), 0f, Ui.dp(context, 3).toFloat(), Color.argb(34, 90, 60, 50))
            canvas.drawCircle(cx, cy, r * k, ringPaint)
            edgePaint.color = Ui.BORDER; canvas.drawCircle(cx, cy, r * k, edgePaint)
        }
        if (pulse) {
            val pr = r * (.5f + .5f * phase)
            pulsePaint.color = Color.argb(((1f - phase) * 140).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(cx, cy, pr, pulsePaint)
        }
        discPaint.color = color
        discPaint.setShadowLayer(Ui.dp(context, 12).toFloat(), 0f, Ui.dp(context, 4).toFloat(), Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
        canvas.drawCircle(cx, cy, r * .5f, discPaint)
        // title: shrink to fit the disc
        titlePaint.textSize = Ui.dp(context, 28).toFloat()
        val maxW = r * .86f
        while (titlePaint.measureText(title) > maxW && titlePaint.textSize > Ui.dp(context, 14)) titlePaint.textSize -= 1f
        val hasCaption = caption.isNotBlank()
        val ty = cy + (if (hasCaption) -Ui.dp(context, 2).toFloat() else titlePaint.textSize / 3f)
        canvas.drawText(title, cx, ty, titlePaint)
        if (hasCaption) canvas.drawText(caption, cx, cy + captionPaint.textSize + Ui.dp(context, 4), captionPaint)
    }
}
