package com.druk.lmplayground.apiserver

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * An [InputStream] fed asynchronously from a producer coroutine, letting
 * NanoHTTPD's `newChunkedResponse` stream Server-Sent Events as they are
 * generated. [send] enqueues UTF-8 text; [close] signals end-of-stream.
 *
 * A plain `Piped*Stream` pair is unsuitable here because deltas arrive from
 * varying binder threads and Piped streams tie themselves to a single writer
 * thread; a blocking queue has no such constraint.
 */
class SseInputStream : InputStream() {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val eof = ByteArray(0) // sentinel
    @Volatile private var closed = false
    private var current: ByteArray = ByteArray(0)
    private var pos = 0
    private var finished = false

    fun send(text: String) {
        if (closed) return
        queue.put(text.toByteArray(Charsets.UTF_8))
    }

    override fun close() {
        closed = true
        queue.put(eof)
    }

    override fun read(): Int {
        if (!ensureAvailable()) return -1
        return current[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensureAvailable()) return -1
        val n = minOf(len, current.size - pos)
        System.arraycopy(current, pos, b, off, n)
        pos += n
        return n
    }

    /** Blocks until the current buffer has bytes, or returns false at EOF. */
    private fun ensureAvailable(): Boolean {
        while (pos >= current.size) {
            if (finished) return false
            val next = try {
                queue.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (next === eof) {
                finished = true
                return false
            }
            current = next
            pos = 0
        }
        return true
    }
}
