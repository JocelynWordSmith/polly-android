package com.robotics.polly

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure functions for frontier detection, clustering, and A* pathfinding
 * on the occupancy grid. No state — all inputs are passed explicitly.
 */
object FrontierExplorer {

    data class Cluster(val cells: List<OccupancyGrid.GridCell>, val centroidIx: Int, val centroidIz: Int)

    private val NEIGHBORS_4 = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

    /**
     * Find frontier cells: FREE cells adjacent to at least one UNKNOWN cell.
     * Unknown = not present in the cells map at all.
     */
    fun findFrontiers(
        cells: Map<OccupancyGrid.GridCell, Float>,
        freeThresh: Float = -0.4f
    ): List<OccupancyGrid.GridCell> {
        val frontiers = mutableListOf<OccupancyGrid.GridCell>()
        for ((cell, logOdds) in cells) {
            if (logOdds > freeThresh) continue // not a free cell
            // Check if any 4-neighbor is unknown (not in map)
            for (n in NEIGHBORS_4) {
                val neighbor = OccupancyGrid.GridCell(cell.ix + n[0], cell.iz + n[1])
                if (neighbor !in cells) {
                    frontiers.add(cell)
                    break
                }
            }
        }
        return frontiers
    }

    /**
     * Group adjacent frontier cells into clusters via flood-fill (4-connected).
     * Returns clusters sorted by size descending.
     */
    fun clusterFrontiers(frontiers: List<OccupancyGrid.GridCell>): List<Cluster> {
        val frontierSet = frontiers.toMutableSet()
        val clusters = mutableListOf<Cluster>()

        while (frontierSet.isNotEmpty()) {
            val seed = frontierSet.first()
            val cluster = mutableListOf<OccupancyGrid.GridCell>()
            val queue = ArrayDeque<OccupancyGrid.GridCell>()
            queue.add(seed)
            frontierSet.remove(seed)

            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                cluster.add(cell)
                for (n in NEIGHBORS_4) {
                    val neighbor = OccupancyGrid.GridCell(cell.ix + n[0], cell.iz + n[1])
                    if (frontierSet.remove(neighbor)) {
                        queue.add(neighbor)
                    }
                }
            }

            val cx = cluster.sumOf { it.ix } / cluster.size
            val cz = cluster.sumOf { it.iz } / cluster.size
            clusters.add(Cluster(cluster, cx, cz))
        }

        return clusters.sortedByDescending { it.cells.size }
    }

    /**
     * Select the nearest cluster centroid to the robot position.
     * Returns null if clusters is empty.
     */
    fun selectTarget(
        clusters: List<Cluster>,
        robotIx: Int,
        robotIz: Int
    ): Cluster? {
        return clusters.minByOrNull { c ->
            val dx = c.centroidIx - robotIx
            val dz = c.centroidIz - robotIz
            dx * dx + dz * dz  // squared distance, no need for sqrt
        }
    }

    /**
     * A* pathfinding from start to goal through passable cells.
     * A cell is passable if its log-odds < navThresh (unknown cells at 0 are passable).
     * Returns the path as a list of grid cells (start to goal), or null if no path.
     * Search is capped at [maxNodes] to prevent stalling on large grids.
     */
    fun planPath(
        startIx: Int, startIz: Int,
        goalIx: Int, goalIz: Int,
        cells: Map<OccupancyGrid.GridCell, Float>,
        navThresh: Float = 1.5f,
        maxNodes: Int = 5000
    ): List<OccupancyGrid.GridCell>? {
        val start = OccupancyGrid.GridCell(startIx, startIz)
        val goal = OccupancyGrid.GridCell(goalIx, goalIz)
        if (start == goal) return listOf(start)

        data class Node(val cell: OccupancyGrid.GridCell, val g: Float, val f: Float)

        val open = PriorityQueue<Node>(compareBy { it.f })
        val gScore = HashMap<OccupancyGrid.GridCell, Float>()
        val parent = HashMap<OccupancyGrid.GridCell, OccupancyGrid.GridCell>()
        var expanded = 0

        fun heuristic(c: OccupancyGrid.GridCell): Float {
            val dx = abs(c.ix - goalIx).toFloat()
            val dz = abs(c.iz - goalIz).toFloat()
            return sqrt(dx * dx + dz * dz)
        }

        gScore[start] = 0f
        open.add(Node(start, 0f, heuristic(start)))

        while (open.isNotEmpty()) {
            val current = open.poll() ?: break
            if (current.cell == goal) {
                // Reconstruct path
                val path = mutableListOf(goal)
                var c = goal
                while (parent.containsKey(c)) {
                    c = parent[c]!!
                    path.add(c)
                }
                path.reverse()
                return path
            }

            expanded++
            if (expanded > maxNodes) return null

            val currentG = gScore[current.cell] ?: continue

            for (n in NEIGHBORS_4) {
                val neighbor = OccupancyGrid.GridCell(current.cell.ix + n[0], current.cell.iz + n[1])
                // Passable if log-odds < navThresh (unknown cells default to 0)
                val logOdds = cells[neighbor] ?: 0f
                if (logOdds >= navThresh) continue

                val tentativeG = currentG + 1f
                if (tentativeG < (gScore[neighbor] ?: Float.MAX_VALUE)) {
                    gScore[neighbor] = tentativeG
                    parent[neighbor] = current.cell
                    open.add(Node(neighbor, tentativeG, tentativeG + heuristic(neighbor)))
                }
            }
        }

        return null // no path
    }
}
