package io.spinnaker.spinrel.cli.jenkins

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.WritableByteChannel

internal class WritableByteChannelOutputStream(private val channel: WritableByteChannel, bufSize: Int = 1024) :
    OutputStream() {

    private val buf = ByteBuffer.allocate(bufSize)

    @Throws(IOException::class)
    override fun write(b: Int) {
        checkOpen()
        if (buf.hasRemaining()) {
            buf.put(b.toByte())
        }
        if (!buf.hasRemaining()) {
            flush()
        }
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        checkOpen()
        var written = 0
        while (written != len) {
            val toWrite = (len - written).coerceAtMost(buf.remaining())
            buf.put(b, off + written, toWrite)
            written += toWrite
            if (!buf.hasRemaining()) {
                flush()
            }
        }
    }

    @Throws(IOException::class)
    override fun flush() {
        checkOpen()
        if (buf.position() > 0) {
            buf.flip()
            channel.write(buf)
            buf.clear()
        }
    }

    @Throws(IOException::class)
    override fun close() {
        flush()
        channel.close()
    }

    @Throws(ClosedChannelException::class)
    private fun checkOpen() {
        if (!channel.isOpen) throw ClosedChannelException()
    }
}
