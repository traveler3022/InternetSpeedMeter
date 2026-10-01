package com.vsp.internetspeedmeter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.util.Palette

/**
 * Daily bars, mobile at the bottom and Wi-Fi stacked on top. Time runs in the
 * layout direction, so the newest day is on the left in Persian.
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    class Bar(val label: String, val mobile: Long, val wifi: Long, val highlight: Boolean = false)

    var bars: List<Bar> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Called with the index into [bars] of a tapped bar. */
    var onBarClick: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val mobilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismAccent)
    }
    private val wifiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WIFI_COLOR }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismGrid)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismMuted)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val highlightPaint = Paint(labelPaint).apply {
        color = Palette.color(context, R.attr.ismAccent)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    private fun slotWidth() = (width - paddingLeft - paddingRight).toFloat() / bars.size

    /** Screen slot of bar [index]: the first bar sits at the start of the layout. */
    private fun slotOf(index: Int) =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) bars.size - 1 - index else index

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty() || width == 0) return

        val labelArea = if (bars.any { it.label.isNotEmpty() }) labelPaint.textSize + 6 * density else 0f
        val bottom = height - paddingBottom - labelArea
        val chartHeight = bottom - paddingTop - 2 * density
        val slot = slotWidth()
        val barWidth = (slot * 0.6f).coerceAtMost(28 * density)
        val max = bars.maxOf { it.mobile + it.wifi }.coerceAtLeast(1L).toFloat()
        val radius = 3 * density

        bars.forEachIndexed { i, bar ->
            val center = paddingLeft + slot * slotOf(i) + slot / 2
            val left = center - barWidth / 2
            val right = center + barWidth / 2
            if (bar.mobile + bar.wifi <= 0L) {
                rect.set(left, bottom - 2 * density, right, bottom)
                canvas.drawRoundRect(rect, radius, radius, emptyPaint)
            } else {
                val mobileTop = bottom - chartHeight * (bar.mobile / max)
                val wifiTop = mobileTop - chartHeight * (bar.wifi / max)
                if (bar.mobile > 0L) {
                    rect.set(left, mobileTop, right, bottom)
                    canvas.drawRect(rect, mobilePaint)
                }
                if (bar.wifi > 0L) {
                    rect.set(left, wifiTop, right, mobileTop)
                    canvas.drawRect(rect, wifiPaint)
                }
            }
            if (bar.label.isNotEmpty()) {
                canvas.drawText(
                    bar.label, center, height - paddingBottom - 2 * density,
                    if (bar.highlight) highlightPaint else labelPaint
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val click = onBarClick ?: return super.onTouchEvent(event)
        if (bars.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val slot = ((event.x - paddingLeft) / slotWidth()).toInt().coerceIn(0, bars.size - 1)
            performClick()
            click(slotOf(slot))
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        /** Wi-Fi bars and legend, the same in every theme. */
        const val WIFI_COLOR = 0xFF26A69A.toInt()
    }
}
