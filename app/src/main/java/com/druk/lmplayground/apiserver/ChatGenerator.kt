package com.druk.lmplayground.apiserver

import android.util.Log
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.PayloadTooLargeException
import com.druk.lmplayground.conversation.GenerationParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges an OpenAI chat-completion request onto the engine's
 * [com.druk.llamacpp.LlamaGenerationSession].
 *
 * For each request it creates its **own** session on the currently-loaded
 * model (the engine allows multiple sessions per model), so it never touches
 * the UI's session or KV cache. Generation is serialized by [mutex] — on-device
 * CPU can't usefully run two decodes at once, so concurrent API requests queue.
 *
 * The native layer has no first-class `max_tokens` / `stop`; both are enforced
 * here inside the streaming callback (count deltas, scan the buffer) by
 * cancelling the generation coroutine, which the proxy translates into a
 * `cancelGeneration` on the service.
 */
class ChatGenerator {

    private val mutex = Mutex()

    data class Result(
        val text: String,
        val finishReason: String,
        val completionTokens: Int,
    )

    /**
     * Run one completion. [onDelta] receives visible text as it streams (the
     * non-streaming caller passes a no-op and just reads [Result]).
     */
    suspend fun generate(
        loaded: LoadedModelHolder.LoadedModel,
        request: ChatCompletionRequest,
        onDelta: (String) -> Unit,
    ): Result = mutex.withLock {
        val defaults = GenerationParams()
        val session = loaded.model.createSession(
            contextSize = loaded.contextTrainSize.coerceIn(2048, 16384),
            temperature = request.temperature ?: defaults.temperature,
            topP = request.topP ?: defaults.topP,
            repetitionPenalty = defaults.repetitionPenalty,
            topK = defaults.topK,
            minP = defaults.minP,
            seed = request.seed ?: defaults.seed,
            thinkingBudget = defaults.thinkingBudget,
            systemPrompt = systemPromptOf(request.messages),
        ) ?: throw IllegalStateException("Failed to create generation session")

        try {
            // Tool calling is not exposed over the API in v1 — disable it so
            // generateAll never returns the "pending tool calls" status.
            session.setTools("[]")

            val (history, prompt) = splitHistoryAndPrompt(request.messages)
            val (users, assistants) = history
            if (users.isNotEmpty()) {
                session.replayHistory(users.toTypedArray(), assistants.toTypedArray())
            }
            session.addMessage(prompt, enableThinking = false)

            val state = GenerationState()
            // Set once the generation child job exists so the callback (fired
            // from a binder thread) can cancel just that job to enforce
            // stop / max_tokens, without cancelling our own coroutine.
            var genJob: Job? = null
            val callback = object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) {
                    if (state.done) return

                    // Apply stop sequences against the full accumulated text.
                    var full = response
                    var stopHit = false
                    for (s in request.stop) {
                        if (s.isEmpty()) continue
                        val idx = full.indexOf(s)
                        if (idx >= 0) {
                            full = full.substring(0, idx)
                            stopHit = true
                        }
                    }

                    if (full.length > state.emittedLen) {
                        val delta = full.substring(state.emittedLen)
                        state.emittedLen = full.length
                        state.visible = full
                        state.completionTokens += countTokensApprox(delta)
                        onDelta(delta)
                    }

                    val maxTokens = request.maxTokens
                    if (stopHit) {
                        state.finishReason = "stop"
                        state.done = true
                        genJob?.cancel()
                    } else if (maxTokens != null && state.completionTokens >= maxTokens) {
                        state.finishReason = "length"
                        state.done = true
                        genJob?.cancel()
                    }
                }
            }

            try {
                coroutineScope {
                    val job = launch {
                        session.generateAll(callback)
                    }
                    genJob = job
                    job.join()
                }
            } catch (e: CancellationException) {
                // We cancel [genJob] to enforce stop / max_tokens — a normal
                // completion. Re-throw only a cancellation we did not initiate
                // (e.g. the server scope is shutting down).
                if (!state.done) throw e
            }

            Result(
                text = state.visible,
                finishReason = state.finishReason,
                completionTokens = state.completionTokens,
            )
        } catch (e: PayloadTooLargeException) {
            throw BadRequestException("A message exceeds the maximum size the engine accepts")
        } finally {
            try { session.destroy() } catch (t: Throwable) { Log.w(TAG, "session destroy failed", t) }
        }
    }

    /** Mutable state shared between the coroutine and the (serialized) callback. */
    private class GenerationState {
        @Volatile var done = false
        @Volatile var emittedLen = 0
        @Volatile var completionTokens = 0
        @Volatile var visible = ""
        @Volatile var finishReason = "stop"
    }

    private fun systemPromptOf(messages: List<ChatMessage>): String =
        messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }

    /**
     * Split OpenAI `messages` into (paired history, final user prompt).
     * System messages are handled separately. History is strict user→assistant
     * pairs (what `replayHistory` requires); the final user message is the
     * prompt to generate against.
     */
    private fun splitHistoryAndPrompt(
        messages: List<ChatMessage>,
    ): Pair<Pair<List<String>, List<String>>, String> {
        val convo = messages.filter { it.role == "user" || it.role == "assistant" }
        val lastUserIdx = convo.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) throw BadRequestException("`messages` must contain a user message")

        val prompt = convo[lastUserIdx].content
        val users = ArrayList<String>()
        val assistants = ArrayList<String>()
        var pendingUser: String? = null
        for (i in 0 until lastUserIdx) {
            val m = convo[i]
            if (m.role == "user") {
                pendingUser = m.content
            } else if (m.role == "assistant" && pendingUser != null) {
                users.add(pendingUser)
                assistants.add(m.content)
                pendingUser = null
            }
        }
        return Pair(Pair(users, assistants), prompt)
    }

    /** Crude token estimate (no tokenizer is exposed to the app layer). */
    private fun countTokensApprox(text: String): Int {
        if (text.isEmpty()) return 0
        // ~4 chars/token is the common English heuristic; floor at 1 so each
        // non-empty delta advances the counter.
        return (text.length / 4).coerceAtLeast(1)
    }

    private companion object {
        private const val TAG = "ChatGenerator"
    }
}
