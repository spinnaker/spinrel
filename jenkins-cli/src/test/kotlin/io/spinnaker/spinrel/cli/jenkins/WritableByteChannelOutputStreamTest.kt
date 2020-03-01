package io.spinnaker.spinrel.cli.jenkins

import com.google.common.collect.Iterables
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.WritableByteChannel
import org.junit.jupiter.api.Test
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.failed
import strikt.assertions.isA
import strikt.assertions.isEqualTo

object WritableByteChannelOutputStreamTest {

    private const val BUF_SIZE = 3

    @Test
    fun `write single bytes`() {
        val testChannel =
            TestByteChannel()
        val subject = WritableByteChannelOutputStream(
            testChannel,
            BUF_SIZE
        )

        subject.use {
            for (i in 1..100) {
                subject.write(i)
            }
        }

        val expectedWriteOperations = Iterables.partition(1..100,
            BUF_SIZE
        ).toByteLists()
        expectThat(testChannel.writeOperations).isEqualTo(expectedWriteOperations)
        expectThat(testChannel).isClosed()
    }

    @Test
    fun `write single bytes as byte arrays`() {
        val testChannel =
            TestByteChannel()
        val subject = WritableByteChannelOutputStream(
            testChannel,
            BUF_SIZE
        )

        subject.use {
            for (i in 1..100) {
                subject.write(byteArrayOf(i.toByte()))
            }
        }

        val expectedWriteOperations = Iterables.partition(1..100,
            BUF_SIZE
        ).toByteLists()
        expectThat(testChannel.writeOperations).isEqualTo(expectedWriteOperations)
        expectThat(testChannel).isClosed()
    }

    @Test
    fun `write from middle of byte array (smaller than buf)`() {
        val testChannel =
            TestByteChannel()
        val subject = WritableByteChannelOutputStream(
            testChannel,
            BUF_SIZE
        )

        val bytes = ByteArray(100)
        for (i in 1..100) {
            bytes[i - 1] = i.toByte()
        }

        subject.use {
            for (i in 0..49)
                subject.write(bytes, i * 2, 2)
        }

        val expectedWriteOperations = Iterables.partition(1..100,
            BUF_SIZE
        ).toByteLists()
        expectThat(testChannel.writeOperations).isEqualTo(expectedWriteOperations)
        expectThat(testChannel).isClosed()
    }

    @Test
    fun `write from middle of byte array (larger than buf)`() {
        val testChannel =
            TestByteChannel()
        val subject = WritableByteChannelOutputStream(
            testChannel,
            BUF_SIZE
        )

        val bytes = ByteArray(100)
        for (i in 1..100) {
            bytes[i - 1] = i.toByte()
        }

        subject.use {
            for (i in 0..9)
                subject.write(bytes, i * 10, 10)
        }

        val expectedWriteOperations = Iterables.partition(1..100,
            BUF_SIZE
        ).toByteLists()
        expectThat(testChannel.writeOperations).isEqualTo(expectedWriteOperations)
        expectThat(testChannel).isClosed()
    }

    @Test
    fun `flush writes bytes immediately`() {
        val testChannel =
            TestByteChannel()
        val subject = WritableByteChannelOutputStream(
            testChannel,
            BUF_SIZE
        )

        subject.use {
            for (i in 1..100) {
                subject.write(i)
                subject.flush()
            }
        }

        for (i in 1..100) {
            expectThat(testChannel.writeOperations[i - 1]).isEqualTo(listOf(i.toByte()))
        }
        expectThat(testChannel).isClosed()
    }

    @Test
    fun `can't write after close`() {
        val testChannel =
            TestByteChannel()
        val subject = WritableByteChannelOutputStream(
            testChannel,
            BUF_SIZE
        )

        subject.close()

        expectCatching { subject.write(3) }
            .failed()
            .isA<ClosedChannelException>()

        expectCatching { subject.write(byteArrayOf(0x03)) }
            .failed()
            .isA<ClosedChannelException>()

        expectCatching { subject.write(byteArrayOf(0x01, 0x02, 0x03), 0, 1) }
            .failed()
            .isA<ClosedChannelException>()

        expectCatching { subject.flush() }
            .failed()
            .isA<ClosedChannelException>()
    }

    private fun Iterable<List<Int>>.toByteLists(): List<List<Byte>> =
        toList().map { it.map { it.toByte() } }

    private class TestByteChannel : WritableByteChannel {

        private val _writeOperations: MutableList<List<Byte>> = ArrayList()

        val writeOperations: List<List<Byte>>
            get() = _writeOperations
        var closed = false

        override fun isOpen(): Boolean {
            return !closed
        }

        override fun write(src: ByteBuffer): Int {
            val remaining = src.remaining()
            val bytes = ByteArray(remaining)
            src.get(bytes)
            _writeOperations.add(bytes.toList())
            return src.remaining()
        }

        override fun close() {
            closed = true
        }
    }
}
