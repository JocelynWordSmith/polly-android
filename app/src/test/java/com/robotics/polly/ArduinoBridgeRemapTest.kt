package com.robotics.polly

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class ArduinoBridgeRemapTest {

    @Test
    fun `remaps basic keys`() {
        val result = ArduinoBridge.remapKeys("{\"t\":123,\"d\":45}")
        val json = JSONObject(result)
        assertTrue(json.has("ts"))
        assertTrue(json.has("dist_f"))
        assertEquals(123, json.getInt("ts"))
        assertEquals(45, json.getInt("dist_f"))
    }

    @Test
    fun `preserves unknown keys`() {
        val result = ArduinoBridge.remapKeys("{\"unknown\":1}")
        val json = JSONObject(result)
        assertTrue(json.has("unknown"))
        assertEquals(1, json.getInt("unknown"))
    }

    @Test
    fun `non-JSON returns unchanged`() {
        val input = "hello world"
        val result = ArduinoBridge.remapKeys(input)
        assertEquals(input, result)
    }

    @Test
    fun `empty JSON object`() {
        val result = ArduinoBridge.remapKeys("{}")
        val json = JSONObject(result)
        assertEquals(0, json.length())
    }

    @Test
    fun `all key mappings work`() {
        val input = JSONObject().apply {
            put("t", 1)
            put("d", 2)
            put("a", 3)
            put("g", 4)
            put("b", 5)
            put("fv", "1.0")
        }.toString()

        val result = ArduinoBridge.remapKeys(input)
        val json = JSONObject(result)

        assertTrue(json.has("ts"))
        assertTrue(json.has("dist_f"))
        assertTrue(json.has("accel"))
        assertTrue(json.has("gyro"))
        assertTrue(json.has("battery"))
        assertTrue(json.has("fw_version"))
        assertFalse(json.has("t"))
        assertFalse(json.has("d"))
        assertFalse(json.has("a"))
        assertFalse(json.has("g"))
        assertFalse(json.has("b"))
        assertFalse(json.has("fv"))
    }
}
