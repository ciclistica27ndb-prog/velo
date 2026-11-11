package it.velo.traduttore.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import it.velo.traduttore.R

/** Bolla flottante circolare: mostra lo stato di Velo e fa da comando rapido. */
@SuppressLint("ViewConstructor")
class BubbleButton(context: Context) : View(context) {

    enum class State { ACTIVE, PAUSED }

    var state: State = State.ACTIVE
        set(value) {
            field = value
            invalidate()
        }

    var working: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) spinner.start() else spinner.cancel()
            invalidate()
        }

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

    private val sizePx = dp(60f).toInt()
    private val radius = dp(25f)
    private val ringWidth = dp(2.5f)

    private val fillColor = ContextCompat.getColor(context, R.color.bubble_fill)
    private val pausedColor = ContextCompat.getColor(context, R.color.bubble_fill_paused)
    private val accent = ContextCompat.getColor(context, R.color.bubble_accent)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(dp(4f), 0f, dp(1.5f), 0x55000000)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, dm)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.04f
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6E6E6.toInt() }
    private val arcRect = RectF()
    private var sweepStart = 0f

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepStart = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        contentDescription = context.getString(R.string.bubble_description)
        isClickable = true
        isLongClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val paused = state == State.PAUSED

        fill.color = if (paused) pausedColor else fillColor
        canvas.drawCircle(cx, cy, radius, fill)

        val r = radius - ringWidth
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        when {
            working -> {
                ring.color = accent
                canvas.drawArc(arcRect, sweepStart, 100f, false, ring)
            }
            !paused -> {
                ring.color = accent
                canvas.drawCircle(cx, cy, r, ring)
            }
        }

        if (paused) {
            val bw = dp(3.5f)
            val bh = dp(14f)
            val gap = dp(3.5f)
            canvas.drawRoundRect(cx - gap - bw, cy - bh / 2, cx - gap, cy + bh / 2, bw / 2, bw / 2, bar)
            canvas.drawRoundRect(cx + gap, cy - bh / 2, cx + gap + bw, cy + bh / 2, bw / 2, bw / 2, bar)
        } else {
            val baseline = cy - (label.descent() + label.ascent()) / 2f
            canvas.drawText("IT", cx, baseline, label)
        }
    }
}
