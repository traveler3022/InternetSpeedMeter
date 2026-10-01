package com.vsp.internetspeedmeter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
 * layout direction, so the newest day is on the left in Persian. Bars have
 * rounded outer corners and the highlighted day (today) gets a soft rounded
 * backdrop behind its slot.
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
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismAccentSoft)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismMuted)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val highlightPaint = Paint(labelPaint).apply {
        color = Palette.color(context, R.attr.ismOnSoft)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private val path = Path()

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
        val barWidth = (slot * 0.58f).coerceAtMost(26 * density)
        val radius = (barWidth / 2.5f).coerceAtLeast(3 * density)
        val max = bars.maxOf { it.mobile + it.wifi }.coerceAtLeast(1L).toFloat()

        bars.forEachIndexed { i, bar ->
            val center = paddingLeft + slot * slotOf(i) + slot / 2
            val left = center - barWidth / 2
            val right = center + barWidth / 2

            // Soft rounded backdrop behind today's bar
            if (bar.highlight) {
                val inset = 3 * density
                rect.set(left - inset, paddingTop + 2 * density, right + inset, bottom)
                canvas.drawRoundRect(rect, 8 * density, 8 * density, backdropPaint)
            }

            if (bar.mobile + bar.wifi <= 0L) {
                // Empty slot: a small flat track at the baseline
                rect.set(left, bottom - 3 * density, right, bottom)
                canvas.drawRoundRect(rect, 2 * density, 2 * density, emptyPaint)
            } else {
                val mobileTop = bottom - chartHeight * (bar.mobile / max)
                val wifiTop = mobileTop - chartHeight * (bar.wifi / max)

                if (bar.mobile > 0L && bar.wifi > 0L) {
                    // Stacked: rounded outer corners, flat joint
                    drawSegment(canvas, left, mobileTop, right, bottom, radius, false, true, mobilePaint)
                    drawSegment(canvas, left, wifiTop, right, mobileTop, radius, true, false, wifiPaint)
                } else if (bar.mobile > 0L) {
                    drawSegment(canvas, left, mobileTop, right, bottom, radius, true, true, mobilePaint)
                } else {
                    drawSegment(canvas, left, wifiTop, right, bottom, radius, true, true, wifiPaint)
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

    /**
     * One bar segment with individually rounded corners, drawn with [paint]:
     * stacked bars round only their outer ends, single bars both ends.
     */
    private fun drawSegment(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, roundTop: Boolean, roundBottom: Boolean, paint: Paint
    ) {
        canvas.drawPath(buildSegmentPath(left, top, right, bottom, radius, roundTop, roundBottom), paint)
    }

    private fun buildSegmentPath(
        left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, roundTop: Boolean, roundBottom: Boolean
    ): Path {
        path.reset()
        val r = radius.coerceAtMost((bottom - top) / 2f).coerceAtLeast(0f)
        when {
            roundTop && roundBottom -> {
                path.moveTo(left, top + r)
                path.quadTo(left, top, left + r, top)
                path.lineTo(right - r, top)
                path.quadTo(right, top, right, top + r)
                path.lineTo(right, bottom - r)
                path.quadTo(right, bottom, right - r, bottom)
                path.lineTo(left + r, bottom)
                path.quadTo(left, bottom, left, bottom - r)
            }
            roundBottom -> {
                path.moveTo(left, top)
                path.lineTo(right, top)
                path.lineTo(right, bottom - r)
                path.quadTo(right, bottom, right - r, bottom)
                path.lineTo(left + r, bottom)
                path.quadTo(left, bottom, left, bottom - r)
            }
            roundTop -> {
                path.moveTo(left, bottom)
                path.lineTo(left, top + r)
                path.quadTo(left, top, left + r, top)
                path.lineTo(right - r, top)
                path.quadTo(right, top, right, top + r)
                path.lineTo(right, bottom)
            }
            else -> {
                path.moveTo(left, top)
                path.lineTo(right, top)
                path.lineTo(right, bottom)
                path.lineTo(left, bottom)
            }
        }
        path.close()
        return path
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
        /** Wi-Fi bars and legend, the same in every theme (matches @color/wifi_tint). */
        const val WIFI_COLOR = 0xFF14B8A6.toInt()
    }
}
