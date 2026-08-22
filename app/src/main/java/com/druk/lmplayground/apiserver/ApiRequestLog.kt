package com.druk.lmplayground.apiserver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory ring buffer of the API traffic the local server has handled, so
 * the user can watch live requests and responses from the phone
 * (Settings → Local API server → Debug log).
 *
 * App-scoped and observable; the newest entry is first. Bounded so a busy
 * server never grows memory without limit. Bodies are truncated defensively
 * to keep large prompts/responses from bloating the buffer.
 */
object ApiRequestLog {

    const val MAX_ENTRIES = 200
    private const val MAX_BODY_CHARS = 8_000

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val method: String,
        val path: String,
        val remoteAddr: String?,
        val statusCode: Int,
        val streamed: Boolean,
        val durationMs: Long,
        val requestBody: String?,
        val responseBody: String?,
        val error: String?,
    )

    private val idCounter = AtomicLong(1)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun nextId(): Long = idCounter.getAndIncrement()

    fun record(
        id: Long,
        method: String,
        path: String,
        remoteAddr: String?,
        statusCode: Int,
        streamed: Boolean,
        durationMs: Long,
        requestBody: String?,
        responseBody: String?,
        error: String? = null,
    ) {
        val entry = Entry(
            id = id,
            timestamp = System.currentTimeMillis(),
            method = method,
            path = path,
            remoteAddr = remoteAddr,
            statusCode = statusCode,
            streamed = streamed,
            durationMs = durationMs,
            requestBody = requestBody?.take(MAX_BODY_CHARS),
            responseBody = responseBody?.take(MAX_BODY_CHARS),
            error = error,
        )
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
