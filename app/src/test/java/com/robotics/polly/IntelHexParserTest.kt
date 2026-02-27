package com.robotics.polly

import org.junit.Test
import org.junit.Assert.*

class IntelHexParserTest {

    @Test
    fun `parse simple data record`() {
        // 4 bytes (01 02 03 04) at address 0x0000, type 00
        // Sum = 04+00+00+00+01+02+03+04 = 0x0E, checksum = (0x100 - 0x0E) & 0xFF = 0xF2
        val hex = ":0400000001020304F2\n:00000001FF\n"
        val result = IntelHexParser.parse(hex)

        assertTrue(result.pages.isNotEmpty())
        assertEquals(0, result.pages[0].address)
        assertEquals(0x01, result.pages[0].data[0].toInt() and 0xFF)
        assertEquals(0x02, result.pages[0].data[1].toInt() and 0xFF)
        assertEquals(0x03, result.pages[0].data[2].toInt() and 0xFF)
        assertEquals(0x04, result.pages[0].data[3].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `checksum validation fails`() {
        // Valid line with corrupted checksum (F0 instead of F2)
        val hex = ":0400000001020304F0\n:00000001FF\n"
        IntelHexParser.parse(hex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `no data records throws`() {
        val hex = ":00000001FF\n"
        IntelHexParser.parse(hex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `record too short throws`() {
        val hex = ":0100\n"
        IntelHexParser.parse(hex)
    }

    @Test
    fun `pages skip all-0xFF`() {
        // Data only in first page (address 0x0000)
        val hex = ":0400000001020304F2\n:00000001FF\n"
        val result = IntelHexParser.parse(hex)

        assertEquals(1, result.pages.size)
    }

    @Test
    fun `page boundary creates multiple pages`() {
        // Write 1 byte at address 0x0000 (page 0) and 1 byte at address 0x0080 (page 1)
        // Line 1: 1 byte (0xAA) at address 0x0000
        // Sum = 01+00+00+00+AA = 0xAB, checksum = (0x100 - 0xAB) & 0xFF = 0x55
        // Line 2: 1 byte (0xBB) at address 0x0080
        // Sum = 01+00+80+00+BB = 0x3C, checksum = (0x100 - 0x3C) & 0xFF = 0xC4
        val hex = ":01000000AA55\n:01008000BBC4\n:00000001FF\n"
        val result = IntelHexParser.parse(hex)

        assertEquals(2, result.pages.size)
        assertEquals(0x00, result.pages[0].address)
        assertEquals(0x80, result.pages[1].address)
    }

    @Test
    fun `extended segment address shifts base`() {
        // Type 02 record: set segment base to 0x1000 (shifted left 4 = 0x10000)
        // :02000002100 -> bytecount=02, addr=0000, type=02, data=1000
        // Sum = 02+00+00+02+10+00 = 0x14, checksum = (0x100 - 0x14) & 0xFF = 0xEC
        // Then data record: 1 byte (0x55) at offset 0x0000 => absolute address 0x10000
        // But flash is only 32768 bytes so use a smaller segment.
        // Type 02 with data=0x0100 => base = 0x0100 << 4 = 0x1000
        // Sum = 02+00+00+02+01+00 = 0x05, checksum = (0x100 - 0x05) & 0xFF = 0xFB
        // Data: 1 byte (0x55) at offset 0x0000 => absolute address 0x1000
        // Sum = 01+00+00+00+55 = 0x56, checksum = (0x100 - 0x56) & 0xFF = 0xAA
        val hex = ":020000020100FB\n:0100000055AA\n:00000001FF\n"
        val result = IntelHexParser.parse(hex)

        // Data should be at address 0x1000
        val page = result.pages.find { it.address <= 0x1000 && it.address + it.data.size > 0x1000 }
        assertNotNull(page)
        val offsetInPage = 0x1000 - page!!.address
        assertEquals(0x55, page.data[offsetInPage].toInt() and 0xFF)
    }
}
