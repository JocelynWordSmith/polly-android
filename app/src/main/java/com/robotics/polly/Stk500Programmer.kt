package com.robotics.polly

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * STK500v1 protocol implementation for programming ATmega328P via Optiboot bootloader.
 * Operates directly on a UsbSerialPort — caller must stop normal read/write threads first.
 */
class Stk500Programmer(
    private val port: UsbSerialPort,
    private val onProgress: (phase: String, percent: Int) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "STK500"

        // Commands
        private const val CMD_GET_SYNC: Byte = 0x30
        private const val CMD_ENTER_PROGMODE: Byte = 0x50
        private const val CMD_LEAVE_PROGMODE: Byte = 0x51
        private const val CMD_LOAD_ADDRESS: Byte = 0x55
        private const val CMD_PROG_PAGE: Byte = 0x64
        private const val CMD_READ_SIGN: Byte = 0x75

        // Responses
        private const val RESP_INSYNC: Byte = 0x14
        private const val RESP_OK: Byte = 0x10

        // Framing
        private const val SYNC_CRC_EOP: Byte = 0x20

        // ATmega328P signature
        private val ATMEGA328P_SIG = byteArrayOf(0x1E.toByte(), 0x95.toByte(), 0x0F.toByte())

        // Page size for ATmega328P
        const val PAGE_SIZE = 128

        // Timeouts
        private const val READ_TIMEOUT_MS = 500
        private const val WRITE_TIMEOUT_MS = 500
        private const val INTER_PAGE_DELAY_MS = 5L
    }

    private val readBuf = ByteArray(256)

    /**
     * Program firmware pages to flash. Pages must be PAGE_SIZE bytes each.
     * @throws Stk500Exception on protocol errors
     */
    fun program(pages: List<IntelHexParser.FirmwareImage.Page>) {
        resetArduino()
        sync()
        enterProgramming()
        verifySignature()
        writePages(pages)
        leaveProgramming()
    }

    private fun resetArduino() {
        onProgress("Resetting Arduino", 0)
        Log.d(TAG, "Toggling DTR to reset Arduino into bootloader")

        // Pulse DTR low to trigger reset via the 100nF capacitor
        port.dtr = true
        Thread.sleep(50)
        port.dtr = false
        Thread.sleep(50)
        port.dtr = true
        Thread.sleep(50)
        port.dtr = false

        // Wait for bootloader to initialize
        Thread.sleep(200)

        // Drain any garbage from the reset
        drain()
    }

    private fun sync() {
        onProgress("Syncing with bootloader", 5)
        Log.d(TAG, "Attempting sync with bootloader")

        // Try sync up to 10 times — first few may get garbage from reset
        for (attempt in 1..10) {
            drain()
            try {
                send(byteArrayOf(CMD_GET_SYNC, SYNC_CRC_EOP))
                val resp = receive(2)
                if (resp[0] == RESP_INSYNC && resp[1] == RESP_OK) {
                    Log.d(TAG, "Sync achieved on attempt $attempt")
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, "Sync attempt $attempt failed: ${e.message}")
            }
            Thread.sleep(50)
        }
        throw Stk500Exception("Failed to sync with bootloader after 10 attempts")
    }

    private fun enterProgramming() {
        onProgress("Entering programming mode", 10)
        sendAndExpectOk(byteArrayOf(CMD_ENTER_PROGMODE, SYNC_CRC_EOP))
        Log.d(TAG, "Entered programming mode")
    }

    private fun verifySignature() {
        onProgress("Verifying device signature", 12)
        send(byteArrayOf(CMD_READ_SIGN, SYNC_CRC_EOP))
        val resp = receive(5) // INSYNC + 3 sig bytes + OK

        if (resp[0] != RESP_INSYNC || resp[4] != RESP_OK) {
            throw Stk500Exception("Bad response to READ_SIGN")
        }

        val sig = resp.copyOfRange(1, 4)
        if (!sig.contentEquals(ATMEGA328P_SIG)) {
            val sigHex = sig.joinToString(":") { String.format("%02X", it) }
            throw Stk500Exception("Wrong device signature: $sigHex (expected ATmega328P)")
        }
        Log.d(TAG, "Device signature verified: ATmega328P")
    }

    private fun writePages(pages: List<IntelHexParser.FirmwareImage.Page>) {
        Log.d(TAG, "Writing ${pages.size} pages (${pages.size * PAGE_SIZE} bytes)")

        pages.forEachIndexed { index, page ->
            val percent = 15 + (index * 80 / pages.size)
            onProgress("Writing page ${index + 1}/${pages.size}", percent)

            // Load address (word address, little-endian)
            val wordAddr = page.address / 2
            sendAndExpectOk(byteArrayOf(
                CMD_LOAD_ADDRESS,
                (wordAddr and 0xFF).toByte(),
                (wordAddr shr 8 and 0xFF).toByte(),
                SYNC_CRC_EOP
            ))

            // Program page: CMD + size_hi + size_lo + 'F' + data + EOP
            val cmd = ByteArray(4 + PAGE_SIZE + 1)
            cmd[0] = CMD_PROG_PAGE
            cmd[1] = 0x00 // size high
            cmd[2] = PAGE_SIZE.toByte() // size low (128 = 0x80)
            cmd[3] = 0x46 // 'F' for flash
            page.data.copyInto(cmd, 4)
            cmd[cmd.size - 1] = SYNC_CRC_EOP
            sendAndExpectOk(cmd)

            Thread.sleep(INTER_PAGE_DELAY_MS)
        }

        Log.d(TAG, "All pages written successfully")
    }

    private fun leaveProgramming() {
        onProgress("Leaving programming mode", 97)
        sendAndExpectOk(byteArrayOf(CMD_LEAVE_PROGMODE, SYNC_CRC_EOP))
        Log.d(TAG, "Left programming mode, Arduino will reset")
        onProgress("Complete", 100)
    }

    private fun send(data: ByteArray) {
        port.write(data, WRITE_TIMEOUT_MS)
    }

    private fun receive(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS * 3

        while (offset < count) {
            if (System.currentTimeMillis() > deadline) {
                throw Stk500Exception("Timeout waiting for $count bytes (got $offset)")
            }
            val n = port.read(readBuf, READ_TIMEOUT_MS)
            if (n > 0) {
                val toCopy = minOf(n, count - offset)
                readBuf.copyInto(result, offset, 0, toCopy)
                offset += toCopy
            }
        }
        return result
    }

    private fun sendAndExpectOk(data: ByteArray) {
        send(data)
        val resp = receive(2)
        if (resp[0] != RESP_INSYNC || resp[1] != RESP_OK) {
            val hex = resp.joinToString(",") { String.format("0x%02X", it) }
            throw Stk500Exception("Expected INSYNC+OK, got: $hex")
        }
    }

    private fun drain() {
        try {
            while (true) {
                val n = port.read(readBuf, 50)
                if (n <= 0) break
            }
        } catch (_: Exception) {
            // Ignore drain errors
        }
    }

    class Stk500Exception(message: String) : IOException(message)
}
