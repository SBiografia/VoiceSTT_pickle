package com.sbiografia.qwenvoice.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sbiografia.qwenvoice.R
import kotlin.math.max

/** 녹음 중 음량을 막대 파형으로 보여주는 뷰. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wave_bar)
        style = Paint.Style.FILL
    }

    private val levels = ArrayDeque<Float>()
    private val maxBars = 56

    fun pushLevel(level: Float) {
        levels.addLast(level.coerceIn(0f, 1f))
        while (levels.size > maxBars) {
            levels.removeFirst()
        }
        invalidate()
    }

    fun clear() {
        levels.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (levels.isEmpty() || width == 0 || height == 0) return

        val count = levels.size
        val slot = width.toFloat() / maxBars
        val barWidth = max(slot * 0.55f, 2f)
        val centerY = height / 2f

        var index = 0
        for (level in levels) {
            val barHeight = max(height * 0.10f, level * height * 0.92f)
            val left = width - (count - index) * slot + (slot - barWidth) / 2f
            canvas.drawRoundRect(
                left,
                centerY - barHeight / 2f,
                left + barWidth,
                centerY + barHeight / 2f,
                barWidth / 2f,
                barWidth / 2f,
                barPaint,
            )
            index++
        }
    }
}
