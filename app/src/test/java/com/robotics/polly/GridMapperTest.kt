package com.robotics.polly

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.atan2

class GridMapperTest {

    private lateinit var mapper: GridMapper

    @Before
    fun setUp() {
        mapper = GridMapper()
    }

    @Test
    fun `onPose updates grid robot position`() {
        mapper.onPose(1.0f, 0f, 2.0f, 0f, 0f, 0f, 1f)
        assertEquals(1.0f, mapper.grid.robotX, 0.01f)
        assertEquals(2.0f, mapper.grid.robotZ, 0.01f)
    }

    @Test
    fun `onUltrasonic increments updateCount when pose available`() {
        mapper.onPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        mapper.onUltrasonic(30)
        assertEquals(1, mapper.updateCount)
    }

    @Test
    fun `onUltrasonic is ignored without pose`() {
        mapper.onUltrasonic(30)
        assertEquals(0, mapper.updateCount)
    }

    @Test
    fun `onUltrasonic ignores negative distance`() {
        mapper.onPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        mapper.onUltrasonic(-1)
        assertEquals(0, mapper.updateCount)
    }

    @Test
    fun `onArduinoData parses dist_f`() {
        mapper.onPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        mapper.onArduinoData("{\"dist_f\":25}")
        assertEquals(25, mapper.lastDistCm)
    }

    @Test
    fun `onArduinoData ignores non-JSON`() {
        val before = mapper.lastDistCm
        mapper.onArduinoData("hello")
        assertEquals(before, mapper.lastDistCm)
    }

    @Test
    fun `extractHeading for identity quaternion`() {
        // Identity quaternion (0,0,0,1): camera faces -Z.
        // fwdX = -(2*(0*0 + 1*0)) = 0
        // fwdZ = 2*(0+0) - 1 = -1
        // atan2(-1, 0) = -PI/2
        val heading = GridMapper.extractHeading(0f, 0f, 0f, 1f)
        val expected = atan2(-1f, 0f)
        assertEquals(expected, heading, 0.01f)
    }

    @Test
    fun `clear resets state`() {
        mapper.onPose(1f, 0f, 1f, 0f, 0f, 0f, 1f)
        mapper.onUltrasonic(30)
        assertTrue(mapper.updateCount > 0)

        mapper.clear()
        assertEquals(0, mapper.updateCount)
        // After clear, hasPose is false so onUltrasonic should not increment
        mapper.onUltrasonic(30)
        assertEquals(0, mapper.updateCount)
    }
}
