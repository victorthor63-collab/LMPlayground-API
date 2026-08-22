package com.druk.lmplayground.apiserver

import org.json.JSONArray
import org.json.JSONObject

/**
 * Request/response shapes for the subset of the OpenAI REST API this server
 * implements (`/v1/models`, `/v1/chat/completions`). Parsing and serialization
 * use `org.json` (bundled with Android) to stay consistent with the rest of
 * the app (see `com.druk.lmplayground.tools`), adding no new dependency.
 */

data class ChatMessage(val role: String, val content: String)

/** Thrown for a malformed request body; mapped to HTTP 400 by the server. */
class BadRequestException(message: String) : Exception(message)

data class ChatCompletionRequest(
    val model: String?,
    val messages: List<ChatMessage>,
    val stream: Boolean,
    val temperature: Float?,
    val topP: Float?,
    val seed: Int?,
    val maxTokens: Int?,
    val stop: List<String>,
) {
    companion object {
        fun parse(body: String): ChatCompletionRequest {
            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw BadRequestException("Request body is not valid JSON")
            }

            val messagesJson = root.optJSONArray("messages")
                ?: throw BadRequestException("`messages` is required")
            val messages = ArrayList<ChatMessage>(messagesJson.length())
            for (i in 0 until messagesJson.length()) {
                val m = messagesJson.optJSONObject(i)
                    ?: throw BadRequestException("messages[$i] is not an object")
                val role = m.optString("role", "")
                if (role.isEmpty()) throw BadRequestException("messages[$i].role is required")
                messages.add(ChatMessage(role, extractContent(m.opt("content"))))
            }
            if (messages.isEmpty()) throw BadRequestException("`messages` must not be empty")

            return ChatCompletionRequest(
                model = root.optStringOrNull("model"),
                messages = messages,
                stream = root.optBoolean("stream", false),
                temperature = root.optFloatOrNull("temperature"),
                topP = root.optFloatOrNull("top_p"),
                seed = if (root.has("seed") && !root.isNull("seed")) root.optInt("seed") else null,
                maxTokens = firstIntOrNull(root, "max_completion_tokens", "max_tokens"),
                stop = parseStop(root.opt("stop")),
            )
        }

        /** Content may be a plain string or an array of typed parts (text only here). */
        private fun extractContent(content: Any?): String = when (content) {
            null, JSONObject.NULL -> ""
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }
            else -> content.toString()
        }

        private fun parseStop(stop: Any?): List<String> = when (stop) {
            null, JSONObject.NULL -> emptyList()
            is String -> listOf(stop)
            is JSONArray -> (0 until stop.length()).mapNotNull { stop.optStringOrNull(it) }
            else -> emptyList()
        }

        private fun firstIntOrNull(root: JSONObject, vararg keys: String): Int? {
            for (k in keys) if (root.has(k) && !root.isNull(k)) return root.optInt(k)
            return null
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONArray.optStringOrNull(index: Int): String? =
    if (!isNull(index)) optString(index) else null

private fun JSONObject.optFloatOrNull(key: String): Float? =
    if (has(key) && !isNull(key)) optDouble(key).toFloat() else null

/** Serializers for the response bodies. */
object OpenAiJson {

    fun error(message: String, type: String, code: String? = null): String =
        JSONObject().put(
            "error",
            JSONObject()
                .put("message", message)
                .put("type", type)
                .put("code", code ?: JSONObject.NULL),
        ).toString()

    fun modelList(model: LoadedModelHolder.LoadedModel?): String {
        val data = JSONArray()
        if (model != null) data.put(modelObject(model))
        return JSONObject().put("object", "list").put("data", data).toString()
    }

    private fun modelObject(model: LoadedModelHolder.LoadedModel): JSONObject =
        JSONObject()
            .put("id", model.id)
            .put("object", "model")
            .put("created", 0)
            .put("owned_by", "lmplayground")

    /** Non-streaming `chat.completion`. */
    fun chatCompletion(
        id: String,
        created: Long,
        modelId: String,
        content: String,
        finishReason: String,
        completionTokens: Int,
    ): String {
        val message = JSONObject().put("role", "assistant").put("content", content)
        val choice = JSONObject()
            .put("index", 0)
            .put("message", message)
            .put("finish_reason", finishReason)
        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", modelId)
            .put("choices", JSONArray().put(choice))
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", 0)
                    .put("completion_tokens", completionTokens)
                    .put("total_tokens", completionTokens),
            )
            .toString()
    }

    /**
     * One streaming `chat.completion.chunk`. Pass [role] on the first chunk,
     * [content] for a delta, and [finishReason] on the terminating chunk.
     */
    fun chatCompletionChunk(
        id: String,
        created: Long,
        modelId: String,
        role: String? = null,
        content: String? = null,
        finishReason: String? = null,
    ): String {
        val delta = JSONObject()
        if (role != null) delta.put("role", role)
        if (content != null) delta.put("content", content)
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", modelId)
            .put("choices", JSONArray().put(choice))
            .toString()
    }
}
