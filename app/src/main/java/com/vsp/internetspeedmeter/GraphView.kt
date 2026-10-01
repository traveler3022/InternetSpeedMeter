package com.vsp.internetspeedmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.vsp.internetspeedmeter.util.Palette

/**
 * Live speed line chart of the last 60 seconds. The area under the line gets a
 * soft accent gradient and the grid lines are dashed, so the redrawn line
 * reads clearly on every palette.
 */
class GraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = LongArray(60)
    private val path = Path()
    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismAccent)
        strokeWidth = 3 * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismGrid)
        strokeWidth = 1 * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.color(context, R.attr.ismMuted)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
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

        val paddingLeft = 52 * density
        val paddingBottom = 24 * density
        val w = width - paddingLeft
        val h = height - paddingBottom

        var maxVal = dataPoints.maxOrNull() ?: 1000L
        if (maxVal < 100000L) maxVal = 200000L // Min 200 KB/s scale

        // Horizontal grid lines with KB/s labels (4 ticks)
        for (i in 0..4) {
            val y = h - (i * h / 4f)
            canvas.drawLine(paddingLeft, y, width.toFloat(), y, gridPaint)
            val label = "${(maxVal * i / 4) / 1000} KB/s"
            canvas.drawText(label, 6 * density, y - 4 * density, textPaint)
        }

        // Line chart path
        path.reset()
        for (i in 0 until 60) {
            val x = paddingLeft + (i * w / 59f)
            val y = h - (dataPoints[i].toFloat() / maxVal * h).coerceIn(0f, h)
            if (i == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }

        // Soft gradient under the line
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Palette.color(context, R.attr.ismAccent).let { c ->
                android.graphics.Color.argb(46, android.graphics.Color.red(c),
                    android.graphics.Color.green(c), android.graphics.Color.blue(c))
            },
            android.graphics.Color.TRANSPARENT,
            Shader.TileMode.CLAMP)
        val fillPath = Path(path)
        fillPath.lineTo(paddingLeft + w, h)
        fillPath.lineTo(paddingLeft, h)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        canvas.drawPath(path, linePaint)

        // X axis labels (seconds)
        for (i in 0..60 step 10) {
            val x = paddingLeft + (i * w / 60f)
            canvas.drawText(i.toString(), x - 8 * density, height - 6 * density, textPaint)
        }
    }
}
