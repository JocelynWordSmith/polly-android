package com.robotics.polly

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OccupancyGridTest {

    private lateinit var grid: OccupancyGrid

    @Before
    fun setUp() {
        grid = OccupancyGrid(cellSize = 0.10f)
    }

    @Test
    fun `update marks endpoint cell as OCCUPIED`() {
        // heading=0 → cos(0)=1, sin(0)=0 → endpoint at (0.30, 0)
        // worldToGrid(0.30)=3, worldToGrid(0.0)=0 → GridCell(3, 0)
        grid.update(0f, 0f, 0f, 0.30f)
        val cells = grid.getCells()
        val endpoint = OccupancyGrid.GridCell(3, 0)
        assertEquals(OccupancyGrid.CellState.OCCUPIED, cells[endpoint])
    }

    @Test
    fun `update marks intermediate cells as FREE`() {
        // Single update gives log-odds of -0.15 per free cell, threshold is -0.4.
        // Need 3 updates to push intermediate cells below FREE_THRESH (-0.45 <= -0.4).
        repeat(3) {
            grid.update(0f, 0f, 0f, 0.30f)
        }
        val cells = grid.getCells()
        // Intermediate cells: (0,0) and (1,0) get free evidence.
        // (2,0) is skipped (stepsToEnd=1), so only (0,0) and (1,0) become FREE.
        val free0 = OccupancyGrid.GridCell(0, 0)
        val free1 = OccupancyGrid.GridCell(1, 0)
        assertEquals(OccupancyGrid.CellState.FREE, cells[free0])
        assertEquals(OccupancyGrid.CellState.FREE, cells[free1])
    }

    @Test
    fun `readings below min range are discarded`() {
        grid.update(0f, 0f, 0f, 0.05f)
        assertTrue(grid.getCells().isEmpty())
    }

    @Test
    fun `readings above max range are discarded`() {
        grid.update(0f, 0f, 0f, 0.90f)
        assertTrue(grid.getCells().isEmpty())
    }

    @Test
    fun `worldToGrid converts correctly`() {
        assertEquals(1, grid.worldToGrid(0.15f))
        assertEquals(-1, grid.worldToGrid(-0.05f))
        assertEquals(0, grid.worldToGrid(0.0f))
    }

    @Test
    fun `trail records positions at 10cm intervals`() {
        // First call always records. Subsequent calls record only if > 10cm from last.
        grid.update(0.00f, 0.00f, 0f, 0.30f)
        grid.update(0.05f, 0.00f, 0f, 0.30f)  // 5cm — too close, no trail
        grid.update(0.12f, 0.00f, 0f, 0.30f)  // 12cm from (0,0) → records
        grid.update(0.15f, 0.00f, 0f, 0.30f)  // 3cm from last trail point — too close
        grid.update(0.25f, 0.00f, 0f, 0.30f)  // 13cm from (0.12,0) → records
        assertEquals(3, grid.getTrail().size)
    }

    @Test
    fun `clear resets all state`() {
        grid.update(0f, 0f, 0f, 0.30f)
        assertTrue(grid.getCells().isNotEmpty())
        assertTrue(grid.getTrail().isNotEmpty())
        grid.clear()
        assertTrue(grid.getCells().isEmpty())
        assertTrue(grid.getTrail().isEmpty())
        assertEquals(0, grid.cellCount())
    }

    @Test
    fun `toJson produces valid structure`() {
        grid.update(0f, 0f, 0f, 0.30f)
        val json = grid.toJson()
        assertTrue(json.has("cell_size"))
        assertTrue(json.has("occupied"))
        assertTrue(json.has("free"))
        assertTrue(json.has("log_odds"))
        assertTrue(json.has("trail"))
        assertEquals(0.10, json.getDouble("cell_size"), 0.01)
    }

    @Test
    fun `setRobotPose updates position without cells`() {
        grid.setRobotPose(1.5f, 2.5f, 0.5f)
        assertEquals(1.5f, grid.robotX, 0.01f)
        assertEquals(2.5f, grid.robotZ, 0.01f)
        assertEquals(0.5f, grid.robotHeadingRad, 0.01f)
        assertTrue(grid.getCells().isEmpty())
    }

    @Test
    fun `isPathClear returns true for empty grid`() {
        assertTrue(grid.isPathClear(0f, 0f, 0f, 1.0f, 0.10f))
    }

    @Test
    fun `log odds are clamped`() {
        // Fire many updates at the same endpoint — log-odds should clamp at MAX_LOG_ODDS (3.5)
        // and not crash or produce unexpected behavior.
        repeat(100) {
            grid.update(0f, 0f, 0f, 0.30f)
        }
        val cells = grid.getCells()
        val endpoint = OccupancyGrid.GridCell(3, 0)
        assertEquals(OccupancyGrid.CellState.OCCUPIED, cells[endpoint])
    }
}
