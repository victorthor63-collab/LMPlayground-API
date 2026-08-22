package com.druk.lmplayground.apiserver

import android.util.Log
import com.druk.llamacpp.InferenceUnavailableException
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Embedded HTTP server exposing an OpenAI-compatible subset
 * (`GET /v1/models`, `POST /v1/chat/completions`) over the WiFi interface.
 *
 * Bound to a specific [hostAddress] (the WiFi IPv4 address) so it listens on
 * that interface only — never `0.0.0.0`. Every request is authenticated
 * against the current bearer key. All traffic is recorded to [ApiRequestLog]
 * for the in-app debug view.
 */
class OpenAiApiServer(
    private val hostAddress: InetAddress,
    port: Int,
    private val apiKeyProvider: () -> String,
    private val generator: ChatGenerator,
    private val scope: CoroutineScope,
) : NanoHTTPD(hostAddress.hostAddress, port) {

    /** `http://<ip>:<port>` this instance is bound to; set by the service. */
    var listeningUrl: String? = null

    override fun serve(session: IHTTPSession): Response {
        val started = System.currentTimeMillis()
        val logId = ApiRequestLog.nextId()
        val method = session.method?.name ?: "?"
        val uri = session.uri ?: ""
        val remote = session.remoteIpAddress

        // Read the body up front (NanoHTTPD requires parseBody to consume the
        // input stream for POSTs).
        val body: String? = try {
            if (session.method == Method.POST || session.method == Method.PUT) {
                val files = HashMap<String, String>()
                session.parseBody(files)
                files["postData"]
            } else null
        } catch (t: Throwable) {
            Log.w(TAG, "parseBody failed", t)
            null
        }

        fun finish(resp: Response, statusCode: Int, respBody: String?, streamed: Boolean, error: String? = null): Response {
            ApiRequestLog.record(
                id = logId, method = method, path = uri, remoteAddr = remote,
                statusCode = statusCode, streamed = streamed,
                durationMs = System.currentTimeMillis() - started,
                requestBody = body, responseBody = respBody, error = error,
            )
            return resp
        }

        // --- Auth ---
        if (!isAuthorized(session)) {
            val json = OpenAiJson.error("Incorrect API key provided.", "invalid_request_error", "invalid_api_key")
            return finish(jsonResponse(Response.Status.UNAUTHORIZED, json), 401, json, false)
        }

        // --- Routing ---
        return when {
            method == "GET" && uri == "/v1/models" -> {
                val json = OpenAiJson.modelList(LoadedModelHolder.current.value)
                finish(jsonResponse(Response.Status.OK, json), 200, json, false)
            }

            method == "POST" && uri == "/v1/chat/completions" ->
                handleChatCompletions(body, logId, started, method, uri, remote)

            else -> {
                val json = OpenAiJson.error("Unknown endpoint: $method $uri", "invalid_request_error", "not_found")
                finish(jsonResponse(Response.Status.NOT_FOUND, json), 404, json, false)
            }
        }
    }

    private fun handleChatCompletions(
        body: String?,
        logId: Long,
        started: Long,
        method: String,
        uri: String,
        remote: String?,
    ): Response {
        fun record(statusCode: Int, respBody: String?, streamed: Boolean, error: String? = null) {
            ApiRequestLog.record(
                id = logId, method = method, path = uri, remoteAddr = remote,
                statusCode = statusCode, streamed = streamed,
                durationMs = System.currentTimeMillis() - started,
                requestBody = body, responseBody = respBody, error = error,
            )
        }

        val request = try {
            ChatCompletionRequest.parse(body ?: "")
        } catch (e: BadRequestException) {
            val json = OpenAiJson.error(e.message ?: "Bad request", "invalid_request_error")
            record(400, json, false, e.message)
            return jsonResponse(Response.Status.BAD_REQUEST, json)
        }

        val loaded = LoadedModelHolder.current.value
        if (loaded == null) {
            val json = OpenAiJson.error(
                "No model is currently loaded. Load a model in LM Playground first.",
                "server_error", "model_not_loaded",
            )
            record(503, json, false, "no model loaded")
            return jsonResponse(SERVICE_UNAVAILABLE, json)
        }

        val id = "chatcmpl-${System.nanoTime()}"
        val created = System.currentTimeMillis() / 1000

        return if (request.stream) {
            streamChatCompletion(request, loaded, id, created, ::record)
        } else {
            blockingChatCompletion(request, loaded, id, created, ::record)
        }
    }

    private fun blockingChatCompletion(
        request: ChatCompletionRequest,
        loaded: LoadedModelHolder.LoadedModel,
        id: String,
        created: Long,
        record: (Int, String?, Boolean, String?) -> Unit,
    ): Response {
        return try {
            val result = runBlocking { generator.generate(loaded, request) { /* non-streaming */ } }
            val json = OpenAiJson.chatCompletion(
                id = id, created = created, modelId = loaded.id,
                content = result.text, finishReason = result.finishReason,
                completionTokens = result.completionTokens,
            )
            record(200, json, false, null)
            jsonResponse(Response.Status.OK, json)
        } catch (e: InferenceUnavailableException) {
            val json = OpenAiJson.error("The inference engine is unavailable.", "server_error", "engine_unavailable")
            record(503, json, false, e.message)
            jsonResponse(SERVICE_UNAVAILABLE, json)
        } catch (e: BadRequestException) {
            val json = OpenAiJson.error(e.message ?: "Bad request", "invalid_request_error")
            record(400, json, false, e.message)
            jsonResponse(Response.Status.BAD_REQUEST, json)
        } catch (t: Throwable) {
            Log.e(TAG, "chat completion failed", t)
            val json = OpenAiJson.error(t.message ?: "Internal error", "server_error")
            record(500, json, false, t.message)
            jsonResponse(Response.Status.INTERNAL_ERROR, json)
        }
    }

    private fun streamChatCompletion(
        request: ChatCompletionRequest,
        loaded: LoadedModelHolder.LoadedModel,
        id: String,
        created: Long,
        record: (Int, String?, Boolean, String?) -> Unit,
    ): Response {
        val stream = SseInputStream()
        val accumulated = StringBuilder()

        scope.launch {
            try {
                // First chunk announces the assistant role (OpenAI convention).
                stream.send("data: ${OpenAiJson.chatCompletionChunk(id, created, loaded.id, role = "assistant")}\n\n")
                val result = generator.generate(loaded, request) { delta ->
                    accumulated.append(delta)
                    stream.send("data: ${OpenAiJson.chatCompletionChunk(id, created, loaded.id, content = delta)}\n\n")
                }
                stream.send("data: ${OpenAiJson.chatCompletionChunk(id, created, loaded.id, finishReason = result.finishReason)}\n\n")
                stream.send("data: [DONE]\n\n")
                record(200, accumulated.toString(), true, null)
            } catch (t: Throwable) {
                Log.e(TAG, "streaming chat completion failed", t)
                val err = OpenAiJson.error(t.message ?: "Internal error", "server_error")
                try { stream.send("data: $err\n\n") } catch (_: Throwable) {}
                record(500, accumulated.toString(), true, t.message)
            } finally {
                stream.close()
            }
        }

        return newChunkedResponse(Response.Status.OK, "text/event-stream", stream).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val header = session.headers["authorization"] ?: return false
        val prefix = "Bearer "
        if (!header.startsWith(prefix)) return false
        val provided = header.substring(prefix.length).trim()
        val expected = apiKeyProvider()
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun jsonResponse(status: Response.IStatus, json: String): Response =
        newFixedLengthResponse(status, "application/json", json)

    companion object {
        private const val TAG = "OpenAiApiServer"

        /** 503 — not present as an enum constant in every NanoHTTPD release. */
        private val SERVICE_UNAVAILABLE = object : Response.IStatus {
            override fun getRequestStatus() = 503
            override fun getDescription() = "503 Service Unavailable"
        }
    }
}
