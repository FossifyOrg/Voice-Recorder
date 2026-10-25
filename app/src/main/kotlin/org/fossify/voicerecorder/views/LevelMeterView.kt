package org.fossify.voicerecorder.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.voicerecorder.R
import kotlin.math.log10

/** Horizontal peak meter from -60 dBFS to 0 dBFS, with a peak hold marker. Turns red when the limiter is reached. */
class LevelMeterView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    companion object {
        private const val MIN_DB = -60f
        private const val CLIP_DB = -1f
        private const val DB_PER_DECADE = 20f
        private const val PEAK_HOLD_MS = 1500L
        private const val TRACK_ALPHA = 0.2f
        private const val PEAK_MARKER_WIDTH_DP = 2
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipColor = ContextCompat.getColor(context, R.color.color_error)
    private val rect = RectF()
    private val peakMarkerWidth = PEAK_MARKER_WIDTH_DP * resources.displayMetrics.density

    private var levelColor = 0
    private var peakColor = 0
    private var levelDb = MIN_DB
    private var peakDb = MIN_DB
    private var peakTime = 0L

    fun setColors(textColor: Int, primaryColor: Int) {
        trackPaint.color = textColor.adjustAlpha(TRACK_ALPHA)
        levelColor = primaryColor
        peakColor = textColor
        invalidate()
    }

    /** [amplitude] is a peak sample value in the 0..32767 range */
    fun setAmplitude(amplitude: Int) {
        levelDb = if (amplitude <= 0) {
            MIN_DB
        } else {
            (DB_PER_DECADE * log10(amplitude / Short.MAX_VALUE.toFloat())).coerceIn(MIN_DB, 0f)
        }

        val now = SystemClock.elapsedRealtime()
        if (levelDb >= peakDb || now - peakTime > PEAK_HOLD_MS) {
            peakDb = levelDb
            peakTime = now
        }
        invalidate()
    }

    fun reset() {
        levelDb = MIN_DB
        peakDb = MIN_DB
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        if (levelDb > MIN_DB) {
            levelPaint.color = if (peakDb >= CLIP_DB) clipColor else levelColor
            rect.set(0f, 0f, width * fraction(levelDb), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, levelPaint)
        }

        if (peakDb > MIN_DB) {
            val x = (width * fraction(peakDb)).coerceAtLeast(peakMarkerWidth)
            peakPaint.color = if (peakDb >= CLIP_DB) clipColor else peakColor
            canvas.drawRect(x - peakMarkerWidth, 0f, x, height.toFloat(), peakPaint)
        }
    }

    private fun fraction(db: Float) = (db - MIN_DB) / -MIN_DB
}
