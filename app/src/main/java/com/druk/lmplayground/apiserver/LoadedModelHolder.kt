package com.druk.lmplayground.apiserver

import com.druk.llamacpp.LlamaModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped registry of the single model currently loaded in the engine.
 *
 * The loaded [LlamaModel] proxy is owned by
 * [com.druk.lmplayground.inference.ModelRuntime], which lives in the
 * chat ViewModel's scope. The local API server needs to reach that model
 * without depending on any UI object, so [ModelRuntime] publishes here on a
 * successful load and clears it on unload / crash. The server reads
 * [current] and creates its **own** generation session on the model (the
 * engine allows multiple sessions per model), so it never disturbs the UI's
 * session or KV cache.
 *
 * Main process only — the `:llama` process never touches this.
 */
object LoadedModelHolder {

    /**
     * Snapshot of the loaded model plus the capability flags and metadata
     * that were resolved from its chat template at load time (each of those
     * would otherwise cost an AIDL round trip to re-read).
     */
    data class LoadedModel(
        val model: LlamaModel,
        /** OpenAI `model` id we advertise — the GGUF filename. */
        val id: String,
        val displayName: String,
        val contextTrainSize: Int,
        val supportsThinking: Boolean,
        val supportsVision: Boolean,
        val supportsToolCalling: Boolean,
    )

    private val _current = MutableStateFlow<LoadedModel?>(null)
    val current: StateFlow<LoadedModel?> = _current.asStateFlow()

    fun publish(model: LoadedModel) {
        _current.value = model
    }

    /**
     * Clear the loaded model. [model] guards against a stale clear racing a
     * newer load: only clears when the held handle is still the one passed
     * (or when null is passed to force a clear on teardown).
     */
    fun clear(model: LlamaModel? = null) {
        if (model == null) {
            _current.value = null
            return
        }
        _current.value = _current.value?.takeIf { it.model !== model }
    }
}
