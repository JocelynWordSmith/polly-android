package com.robotics.polly

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sparse 2D occupancy grid using log-odds probability model.
 *
 * Each cell stores a log-odds value:
 *   positive = likely occupied, negative = likely free, 0 = unknown.
 * Free rays through previously-occupied cells gradually erase them,
 * which self-corrects stale wall positions caused by pose drift.
 *
 * Coordinate system: ARCore world X,Z plane (Y is up/gravity).
 */
class OccupancyGrid(
    val cellSize: Float = 0.10f // 10cm resolution — matches ~5cm drift so walls stay 1 cell thick
) {
    enum class CellState { FREE, OCCUPIED }

    data class GridCell(val ix: Int, val iz: Int)

    private val cells = ConcurrentHashMap<GridCell, Float>()
    private val trail = CopyOnWriteArrayList<Pair<Float, Float>>()

    var robotX = 0f
        private set
    var robotZ = 0f
        private set
    var robotHeadingRad = 0f
        private set

    /** Update robot position/heading without performing a grid update (raycasting). */
    fun setRobotPose(x: Float, z: Float, heading: Float) {
        robotX = x
        robotZ = z
        robotHeadingRad = heading
    }

    /** Latest ultrasonic hit endpoint (world coords), or NaN if out of range. */
    @Volatile var lastHitX = Float.NaN
        private set
    @Volatile var lastHitZ = Float.NaN
        private set

    /**
     * Update grid with a single ultrasonic reading.
     *
     * @param x       Robot X position in world frame (meters)
     * @param z       Robot Z position in world frame (meters)
     * @param heading Robot heading in XZ plane (radians)
     * @param distM   Ultrasonic reading in meters
     */
    fun update(x: Float, z: Float, heading: Float, distM: Float) {
        robotX = x
        robotZ = z
        robotHeadingRad = heading

        // Record trail every ~10cm of movement
        val last = trail.lastOrNull()
        if (last == null || hypot(x - last.first, z - last.second) > 0.10f) {
            trail.add(Pair(x, z))
        }

        // Filter unreliable readings (cap at 80cm to reduce heading error amplification)
        if (distM < 0.10f || distM > 0.80f) {
            lastHitX = Float.NaN
            lastHitZ = Float.NaN
            return
        }

        val endX = x + distM * cos(heading)
        val endZ = z + distM * sin(heading)

        lastHitX = endX
        lastHitZ = endZ

        val ix0 = worldToGrid(x)
        val iz0 = worldToGrid(z)
        val ix1 = worldToGrid(endX)
        val iz1 = worldToGrid(endZ)

        bresenham(ix0, iz0, ix1, iz1) { ix, iz, isEndpoint, stepsToEnd ->
            val cell = GridCell(ix, iz)
            // Skip free update on the cell just before the wall to prevent erosion
            val increment = if (isEndpoint) L_OCC
                            else if (stepsToEnd <= 1) 0f
                            else -L_FREE
            if (increment != 0f) {
                cells[cell] = ((cells[cell] ?: 0f) + increment).coerceIn(-MAX_LOG_ODDS, MAX_LOG_ODDS)
            }
        }
    }

    /**
     * Check if the path ahead is clear of occupied cells.
     * Casts 3 rays (center + left/right offset by halfWidth) from (x,z)
     * in the given heading direction out to checkDist meters.
     */
    fun isPathClear(x: Float, z: Float, heading: Float, checkDist: Float, halfWidth: Float): Boolean {
        val cosH = cos(heading)
        val sinH = sin(heading)
        // Perpendicular direction: (-sinH, cosH)
        for (offset in floatArrayOf(0f, -halfWidth, halfWidth)) {
            val sx = x + (-sinH) * offset
            val sz = z + cosH * offset
            val ex = sx + cosH * checkDist
            val ez = sz + sinH * checkDist
            val ix0 = worldToGrid(sx)
            val iz0 = worldToGrid(sz)
            val ix1 = worldToGrid(ex)
            val iz1 = worldToGrid(ez)
            bresenham(ix0, iz0, ix1, iz1) { ix, iz, _, _ ->
                if ((cells[GridCell(ix, iz)] ?: 0f) > NAV_OCC_THRESH) return false
            }
        }
        return true
    }

    /** Return cell states by thresholding log-odds. Unknown cells are omitted. */
    fun getCells(): Map<GridCell, CellState> {
        val result = HashMap<GridCell, CellState>(cells.size)
        for ((cell, logOdds) in cells) {
            if (logOdds >= OCC_THRESH) {
                result[cell] = CellState.OCCUPIED
            } else if (logOdds <= FREE_THRESH) {
                result[cell] = CellState.FREE
            }
        }
        return result
    }

    /** Raw log-odds map for path planning and frontier detection. */
    fun getRawCells(): Map<GridCell, Float> = cells

    fun getTrail(): List<Pair<Float, Float>> = trail
    fun cellCount(): Int = cells.count { it.value >= OCC_THRESH || it.value <= FREE_THRESH }

    fun clear() {
        cells.clear()
        trail.clear()
    }

    fun worldToGrid(v: Float): Int = floor((v / cellSize).toDouble()).toInt()

    private inline fun bresenham(
        x0: Int, y0: Int, x1: Int, y1: Int,
        visitor: (ix: Int, iz: Int, isEndpoint: Boolean, stepsToEnd: Int) -> Unit
    ) {
        // Count total steps first
        var totalSteps = 0
        run {
            var cx = x0; var cy = y0
            val dx = abs(x1 - x0); val dy = abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
            var err = dx - dy
            while (!(cx == x1 && cy == y1)) {
                totalSteps++
                val e2 = 2 * err
                if (e2 > -dy) { err -= dy; cx += sx }
                if (e2 < dx) { err += dx; cy += sy }
            }
        }

        // Walk and visit with stepsToEnd
        var cx = x0
        var cy = y0
        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        var step = 0

        while (true) {
            val isEnd = cx == x1 && cy == y1
            visitor(cx, cy, isEnd, totalSteps - step)
            if (isEnd) break
            step++
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                cx += sx
            }
            if (e2 < dx) {
                err += dx
                cy += sy
            }
        }
    }

    private fun hypot(a: Float, b: Float): Float = sqrt(a * a + b * b)

    fun toJson(): JSONObject {
        val occupied = JSONArray()
        val free = JSONArray()
        val logOddsArr = JSONArray()
        for ((cell, logOdds) in cells) {
            logOddsArr.put(JSONArray().put(cell.ix).put(cell.iz).put(logOdds.toDouble()))
            if (logOdds >= OCC_THRESH) {
                occupied.put(JSONArray().put(cell.ix).put(cell.iz))
            } else if (logOdds <= FREE_THRESH) {
                free.put(JSONArray().put(cell.ix).put(cell.iz))
            }
        }
        val trailArr = JSONArray()
        for ((x, z) in trail) {
            trailArr.put(JSONArray().put(x).put(z))
        }
        return JSONObject().apply {
            put("cell_size", cellSize.toDouble())
            put("occupied", occupied)
            put("free", free)
            put("log_odds", logOddsArr)
            put("trail", trailArr)
        }
    }

    companion object {
        // Log-odds update increments
        private const val L_OCC = 0.85f      // occupied evidence (one reading → shows occupied)
        private const val L_FREE = 0.15f     // free evidence (~6 rays to erase a single-hit wall)
        private const val MAX_LOG_ODDS = 3.5f // clamp to ~97% confidence

        // Display thresholds
        private const val OCC_THRESH = 0.4f       // show as occupied above this
        private const val FREE_THRESH = -0.4f      // show as free below this
        private const val NAV_OCC_THRESH = 1.5f    // navigation: treat as obstacle (needs 2+ readings)
    }
}
