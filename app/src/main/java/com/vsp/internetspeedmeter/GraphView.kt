package com.vsp.internetspeedmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.vsp.internetspeedmeter.util.Palette

class GraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = LongArray(60)
    private val path = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismAccent)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismGrid)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismMuted)
        textSize = 24f
    }

    fun setData(points: LongArray, index: Int) {
        // Unroll the circular buffer
        for (i in 0 until 60) {
            dataPoints[i] = points[(index + i + 1) % 60]
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val paddingLeft = 100f
        val paddingBottom = 60f
        val w = width - paddingLeft
        val h = height - paddingBottom

        var maxVal = dataPoints.maxOrNull() ?: 1000L
        if (maxVal < 100000L) maxVal = 200000L // Min 200 KB/s scale

        // Draw horizontal grid lines (4 ticks)
        for (i in 0..4) {
            val y = h - (i * h / 4f)
            canvas.drawLine(paddingLeft, y, width.toFloat(), y, gridPaint)
            val label = "${(maxVal * i / 4) / 1000} KB/s"
            canvas.drawText(label, 10f, y + 10f, textPaint)
        }

        // Draw line chart
        path.reset()
        for (i in 0 until 60) {
            val x = paddingLeft + (i * w / 59f)
            val y = h - (dataPoints[i].toFloat() / maxVal * h).coerceIn(0f, h)
            if (i == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
        
        // Draw X axis labels
        for (i in 0..60 step 10) {
            val x = paddingLeft + (i * w / 60f)
            canvas.drawText(i.toString(), x - 10f, height - 20f, textPaint)
        }
    }
}
