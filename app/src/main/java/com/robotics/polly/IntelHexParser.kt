package com.robotics.polly

/**
 * Parses Intel HEX format into page-aligned byte arrays for STK500 programming.
 * Supports record types 00 (data), 01 (EOF), 02 (extended segment), 04 (extended linear).
 */
object IntelHexParser {

    data class FirmwareImage(
        val data: ByteArray,
        val pages: List<Page>
    ) {
        data class Page(val address: Int, val data: ByteArray)
    }

    fun parse(hexContent: String, pageSize: Int = 128): FirmwareImage {
        val flash = ByteArray(32768) { 0xFF.toByte() }
        var baseAddress = 0
        var maxAddress = -1

        for (rawLine in hexContent.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.startsWith(":")) continue

            val bytes = line.drop(1).chunked(2).map { it.toInt(16) }
            require(bytes.size >= 5) { "Record too short: $line" }

            // Verify checksum: sum of all bytes (including checksum) & 0xFF == 0
            require(bytes.sum() and 0xFF == 0) { "Checksum failed: $line" }

            val byteCount = bytes[0]
            val address = (bytes[1] shl 8) or bytes[2]
            val recordType = bytes[3]
            val data = bytes.subList(4, 4 + byteCount).map { it.toByte() }.toByteArray()

            when (recordType) {
                0x00 -> { // Data
                    val absAddr = baseAddress + address
                    data.copyInto(flash, absAddr)
                    maxAddress = maxOf(maxAddress, absAddr + byteCount - 1)
                }
                0x01 -> break // EOF
                0x02 -> { // Extended Segment Address
                    baseAddress = ((data[0].toInt() and 0xFF shl 8) or
                        (data[1].toInt() and 0xFF)) shl 4
                }
                0x04 -> { // Extended Linear Address
                    baseAddress = ((data[0].toInt() and 0xFF shl 8) or
                        (data[1].toInt() and 0xFF)) shl 16
                }
                // 0x03, 0x05: start address records — ignore
            }
        }

        require(maxAddress >= 0) { "No data records found in hex file" }

        // Round up to page boundary
        val totalBytes = ((maxAddress + 1 + pageSize - 1) / pageSize) * pageSize
        val firmware = flash.copyOf(totalBytes)

        // Slice into pages, skipping all-0xFF pages (already erased)
        val pages = mutableListOf<FirmwareImage.Page>()
        for (offset in firmware.indices step pageSize) {
            val page = firmware.copyOfRange(offset, offset + pageSize)
            if (page.any { it != 0xFF.toByte() }) {
                pages.add(FirmwareImage.Page(offset, page))
            }
        }

        return FirmwareImage(firmware, pages)
    }
}
