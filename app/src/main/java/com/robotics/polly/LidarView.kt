package com.robotics.polly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class LidarPoint(val angle: Float, val distance: Float, val quality: Int)

class LidarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Store points with timestamps for fading
    private data class TimedPoint(
        val angle: Float,
        val distance: Float,
        val quality: Int,
        val timestamp: Long
    )

    private val timedPoints = mutableListOf<TimedPoint>()
    private val pointLifetimeMs = 3000L // Points visible for 3 seconds

    private val pointPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val linePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#C8D8E8")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#4A7BA8")
        textSize = 20f
        isAntiAlias = true
    }

    private val robotPaint = Paint().apply {
        color = Color.parseColor("#0055AA")
        isAntiAlias = true
    }

    private var centerX = 0f
    private var centerY = 0f
    private var scale = 1f
    private val maxDistance = 6000f // 6 meters

    fun updatePoints(newPoints: List<LidarPoint>) {
        val now = System.currentTimeMillis()
        synchronized(timedPoints) {
            // Remove expired points
            timedPoints.removeAll { now - it.timestamp > pointLifetimeMs }

            // Add new points with current timestamp
            for (p in newPoints) {
                if (p.quality > 0 && p.distance > 50 && p.distance < maxDistance) {
                    timedPoints.add(TimedPoint(p.angle, p.distance, p.quality, now))
                }
            }

            // Cap total points
            if (timedPoints.size > 3000) {
                val excess = timedPoints.size - 3000
                repeat(excess) { timedPoints.removeAt(0) }
            }
        }
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        scale = (w.coerceAtMost(h) / 2f) * 0.85f / maxDistance
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        canvas.drawColor(Color.parseColor("#EBE8E0"))

        // Grid circles (1m intervals)
        for (i in 1..6) {
            val radius = i * 1000f * scale
            canvas.drawCircle(centerX, centerY, radius, gridPaint)
            textPaint.textSize = 16f
            canvas.drawText("${i}m", centerX + 4f, centerY - radius + 14f, textPaint)
        }

        // Crosshairs
        gridPaint.strokeWidth = 0.5f
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, gridPaint)
        canvas.drawLine(centerX, 0f, centerX, height.toFloat(), gridPaint)
        gridPaint.strokeWidth = 1f

        val now = System.currentTimeMillis()

        synchronized(timedPoints) {
            if (timedPoints.isEmpty()) {
                // Show "no data" message
                textPaint.textSize = 18f
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("Waiting for scan data...", centerX, centerY + 60f, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
            } else {
                // Sort by angle for contour drawing
                val sorted = timedPoints.sortedBy { it.angle }

                // Draw contour lines connecting nearby points
                drawContours(canvas, sorted, now)

                // Draw individual points (recent ones brighter)
                for (tp in sorted) {
                    val age = now - tp.timestamp
                    if (age > pointLifetimeMs) continue

                    val freshness = 1f - (age.toFloat() / pointLifetimeMs)
                    val alpha = (freshness * 200 + 55).toInt().coerceIn(30, 255)

                    val angleRad = Math.toRadians(tp.angle.toDouble()).toFloat()
                    val dist = tp.distance * scale
                    val x = centerX + dist * cos(angleRad)
                    val y = centerY - dist * sin(angleRad)

                    // Size by quality, fade by age
                    val radius = if (tp.quality > 30) 4f else 2.5f
                    pointPaint.color = Color.argb(alpha, 0, 0x55, 0xAA)
                    canvas.drawCircle(x, y, radius, pointPaint)
                }
            }
        }

        // Robot position (center)
        robotPaint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, 8f, robotPaint)
        robotPaint.style = Paint.Style.STROKE
        robotPaint.strokeWidth = 2f
        canvas.drawCircle(centerX, centerY, 8f, robotPaint)
        // Forward indicator
        canvas.drawLine(centerX, centerY, centerX, centerY - 14f, robotPaint)
        robotPaint.style = Paint.Style.FILL

        // Point count (small, top-left)
        textPaint.textSize = 16f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${timedPoints.size} pts", 8f, 20f, textPaint)
    }

    private fun drawContours(canvas: Canvas, sorted: List<TimedPoint>, now: Long) {
        if (sorted.size < 2) return

        // Connect points that are close in angle and reasonable in distance
        val maxAngleGap = 5f // degrees
        val maxDistRatio = 2.0f // max ratio between consecutive distances

        var prevX = Float.NaN
        var prevY = Float.NaN
        var prevDist = 0f
        var prevAngle = -999f

        for (tp in sorted) {
            val age = now - tp.timestamp
            if (age > pointLifetimeMs) continue

            val freshness = 1f - (age.toFloat() / pointLifetimeMs)
            val alpha = (freshness * 140 + 30).toInt().coerceIn(20, 170)

            val angleRad = Math.toRadians(tp.angle.toDouble()).toFloat()
            val dist = tp.distance * scale
            val x = centerX + dist * cos(angleRad)
            val y = centerY - dist * sin(angleRad)

            // Draw line segment if consecutive points are close
            if (!prevX.isNaN()) {
                val angleDiff = abs(tp.angle - prevAngle)
                val distRatio = if (prevDist > 0) tp.distance / prevDist else 999f

                if (angleDiff < maxAngleGap && distRatio < maxDistRatio && distRatio > 1f / maxDistRatio) {
                    linePaint.color = Color.argb(alpha, 0x00, 0x44, 0x88)
                    canvas.drawLine(prevX, prevY, x, y, linePaint)
                }
            }

            prevX = x
            prevY = y
            prevDist = tp.distance
            prevAngle = tp.angle
        }
    }
}
