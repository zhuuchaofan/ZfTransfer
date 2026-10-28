package io.github.noisetta.nikonpull.ptp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import javax.net.SocketFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpIpClientTransferTest {
    @Test(timeout = 3000)
    fun abortClosesCommandSocketWithoutWaitingForCommandMonitor() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val socketClosed = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val client = PtpIpClient()
        val socket = object : Socket() { override fun close() { socketClosed.countDown() } }
        client.javaClass.getDeclaredField("commandSocket").apply { isAccessible = true }.set(client, socket)
        val reader = kotlin.concurrent.thread(isDaemon = true) {
            synchronized(client) { entered.countDown(); socketClosed.await(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
        try {
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            kotlin.concurrent.thread(isDaemon = true) { client.abort(); finished.countDown() }
            assertTrue("abort must not wait behind an active command", finished.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            socketClosed.countDown()
            reader.join(1000)
        }
    }
    @Test
    fun zfHandshakeAndEnumerationUseSmartDeviceDialect() {
        val deviceData = byteArrayOf(1, 2, 3)
        val handleData = littleEndian(12).putInt(2).putInt(41).putInt(42).array()
        val cmd = ScriptedSocket(join(
            packet(TYPE_INIT_COMMAND_ACK, littleEndian(20).putInt(7).put(ByteArray(16)).array()),
            dataResponse(0, deviceData), operationResponse(0),
            operationResponse(0), // OpenSession also uses transaction 0
            dataResponse(1, handleData), operationResponse(1),
        ))
        val evt = ScriptedSocket(packet(TYPE_INIT_EVENT_ACK))
        val client = PtpIpClient(zfMode = true, socketFactory = QueuedSocketFactory(cmd, evt))
        try {
            client.connect()
            assertEquals(listOf(41L, 42L), client.listObjectHandles())
            val sent = cmd.capturedOutput.toByteArray()
            assertArrayEquals(byteArrayOf(0,17,34,51,68,85,102,119,
                -120,-103,-86,-69,-52,-35,-18,-1), sent.copyOfRange(8,24))
            // Decode every operation emitted: no GetStorageIDs probe is allowed.
            val requests = mutableListOf<ByteBuffer>()
            var off = 0
            while (off < sent.size) {
                val header = ByteBuffer.wrap(sent, off, 8).order(ByteOrder.LITTLE_ENDIAN)
                val length = header.int
                if (header.int == 6) requests += ByteBuffer.wrap(sent.copyOfRange(off+8,off+length)).order(ByteOrder.LITTLE_ENDIAN)
                off += length
            }
            assertEquals(listOf(0x1001,0x1002,0x1007), requests.map { it.getShort(4).toInt() and 0xffff })
            val enum = requests.last()
            assertEquals(-1, enum.getInt(10)) // all storages
            assertEquals(0, enum.getInt(14))
            assertEquals(0, enum.getInt(18)) // all descendants in Zf smart-device mode
        } finally { client.abort() }
    }

    @Test(expected = PtpException::class)
    fun truncatedOriginalMustNotBeReportedAsComplete() {
        val cmd = ScriptedSocket(join(
            packet(TYPE_INIT_COMMAND_ACK, littleEndian(20).putInt(7).put(ByteArray(16)).array()),
            operationResponse(0),
            packet(TYPE_START_DATA, littleEndian(12).putInt(1).putLong(100).array()),
            packet(TYPE_END_DATA, littleEndian(7).putInt(1).put(byteArrayOf(1,2,3)).array()),
            operationResponse(1),
        ))
        val client = PtpIpClient(socketFactory = QueuedSocketFactory(cmd, ScriptedSocket(packet(TYPE_INIT_EVENT_ACK))))
        try { client.connect(); client.downloadObject(42L, ByteArrayOutputStream()) }
        finally { client.abort() }
    }

    private fun dataResponse(tid: Int, bytes: ByteArray) = join(
        packet(TYPE_START_DATA, littleEndian(12).putInt(tid).putLong(bytes.size.toLong()).array()),
        packet(TYPE_END_DATA, littleEndian(bytes.size+4).putInt(tid).put(bytes).array()),
    )

    @Test
    fun eventChannelEofDoesNotAbortActiveCommandChannel() {
        val objectBytes = "complete-video-data".toByteArray()
        val commandSocket = ScriptedSocket(commandConversation(objectBytes))
        val eventSocket = ScriptedSocket(packet(TYPE_INIT_EVENT_ACK))
        val client = PtpIpClient(
            socketFactory = QueuedSocketFactory(commandSocket, eventSocket),
        )

        client.connect()
        assertTrue(waitUntil { eventSocket.closed })
        assertFalse(commandSocket.closed)

        val destination = ByteArrayOutputStream()
        client.downloadObject(handle = 42L, destination = destination)

        assertArrayEquals(objectBytes, destination.toByteArray())
        assertFalse(commandSocket.closed)
        client.abort()
    }

    @Test
    fun partialObjectDownloadConcatenatesBoundedTransactions() {
        val objectBytes = "a-video-split-into-several-small-ptp-responses".toByteArray()
        val chunkSize = 11
        val commandSocket = ScriptedSocket(partialObjectConversation(objectBytes, chunkSize))
        val eventSocket = ScriptedSocket(packet(TYPE_INIT_EVENT_ACK))
        val client = PtpIpClient(
            socketFactory = QueuedSocketFactory(commandSocket, eventSocket),
        )

        client.connect()
        val destination = ByteArrayOutputStream()
        var lastProgress = TransferProgress(0, null)
        client.downloadObjectInChunks(
            handle = 42L,
            objectSize = objectBytes.size.toLong(),
            destination = destination,
            onProgress = { lastProgress = it },
            chunkSize = chunkSize,
        )

        assertArrayEquals(objectBytes, destination.toByteArray())
        assertEquals(objectBytes.size.toLong(), lastProgress.bytesTransferred)
        assertEquals(objectBytes.size.toLong(), lastProgress.totalBytes)
        assertFalse(commandSocket.closed)
        client.abort()
    }

    @Test
    fun partialObjectDownloadResumesFromCompletedOffset() {
        val objectBytes = "prefix-already-saved-and-the-remaining-video-data".toByteArray()
        val startOffset = 21
        val remainingBytes = objectBytes.copyOfRange(startOffset, objectBytes.size)
        val commandSocket = ScriptedSocket(partialObjectConversation(remainingBytes, chunkSize = 8))
        val eventSocket = ScriptedSocket(packet(TYPE_INIT_EVENT_ACK))
        val client = PtpIpClient(
            socketFactory = QueuedSocketFactory(commandSocket, eventSocket),
        )

        client.connect()
        val destination = ByteArrayOutputStream().apply {
            write(objectBytes, 0, startOffset)
        }
        val checkpoints = mutableListOf<Long>()
        client.downloadObjectInChunks(
            handle = 42L,
            objectSize = objectBytes.size.toLong(),
            destination = destination,
            startOffset = startOffset.toLong(),
            onChunkCompleted = checkpoints::add,
            chunkSize = 8,
        )

        assertArrayEquals(objectBytes, destination.toByteArray())
        assertTrue(checkpoints.all { it > startOffset })
        assertEquals(objectBytes.size.toLong(), checkpoints.last())
        client.abort()
    }

    private fun commandConversation(objectBytes: ByteArray): ByteArray = join(
        packet(
            TYPE_INIT_COMMAND_ACK,
            littleEndian(20).putInt(7).put(ByteArray(16)).array(),
        ),
        operationResponse(transactionId = 0),
        packet(
            TYPE_START_DATA,
            littleEndian(12).putInt(1).putLong(objectBytes.size.toLong()).array(),
        ),
        packet(
            TYPE_END_DATA,
            littleEndian(4 + objectBytes.size).putInt(1).put(objectBytes).array(),
        ),
        operationResponse(transactionId = 1),
    )

    private fun partialObjectConversation(objectBytes: ByteArray, chunkSize: Int): ByteArray {
        val responses = mutableListOf(
            packet(
                TYPE_INIT_COMMAND_ACK,
                littleEndian(20).putInt(7).put(ByteArray(16)).array(),
            ),
            operationResponse(transactionId = 0),
        )
        objectBytes.asList().chunked(chunkSize).forEachIndexed { index, values ->
            val transactionId = index + 1
            val chunk = values.toByteArray()
            responses += packet(
                TYPE_START_DATA,
                littleEndian(12).putInt(transactionId).putLong(chunk.size.toLong()).array(),
            )
            responses += packet(
                TYPE_END_DATA,
                littleEndian(4 + chunk.size).putInt(transactionId).put(chunk).array(),
            )
            responses += operationResponse(transactionId)
        }
        return join(*responses.toTypedArray())
    }

    private fun operationResponse(transactionId: Int): ByteArray = packet(
        TYPE_OPERATION_RESPONSE,
        littleEndian(6).putShort(RESPONSE_OK.toShort()).putInt(transactionId).array(),
    )

    private fun packet(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        littleEndian(8 + payload.size)
            .putInt(8 + payload.size)
            .putInt(type)
            .put(payload)
            .array()

    private fun littleEndian(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun join(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        repeat(100) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    private class QueuedSocketFactory(vararg sockets: Socket) : SocketFactory() {
        private val remaining = ArrayDeque(sockets.toList())

        override fun createSocket(): Socket = remaining.removeFirst()
        override fun createSocket(host: String?, port: Int): Socket = createSocket()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket()
        override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = createSocket()
    }

    private class ScriptedSocket(input: ByteArray) : Socket() {
        private val scriptedInput = ByteArrayInputStream(input)
        val capturedOutput = ByteArrayOutputStream()
        @Volatile var closed: Boolean = false
            private set

        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
        override fun getInputStream(): InputStream = scriptedInput
        override fun getOutputStream(): OutputStream = capturedOutput
        override fun isConnected(): Boolean = true
        override fun isClosed(): Boolean = closed
        override fun close() {
            closed = true
            scriptedInput.close()
            capturedOutput.close()
        }
    }

    private companion object {
        const val TYPE_INIT_COMMAND_ACK = 2
        const val TYPE_INIT_EVENT_ACK = 4
        const val TYPE_OPERATION_RESPONSE = 7
        const val TYPE_START_DATA = 9
        const val TYPE_END_DATA = 12
        const val RESPONSE_OK = 0x2001
    }
}
