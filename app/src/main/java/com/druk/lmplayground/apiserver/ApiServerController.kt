package com.druk.lmplayground.apiserver

import android.content.Context
import androidx.core.content.ContextCompat
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin app-facing entry point for the local API server: flips the persisted
 * enable flag, starts/stops [ApiServerService], and exposes the live
 * [status] the Settings screen observes.
 */
object ApiServerController {

    data class Status(
        val enabled: Boolean = false,
        val running: Boolean = false,
        /** `http://<wifi-ip>:<port>` when listening, else null. */
        val url: String? = null,
        /** Human-readable reason the server isn't listening (e.g. no WiFi). */
        val message: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Called by the service as its state changes. */
    internal fun publishStatus(status: Status) {
        _status.value = status
    }

    /** Turn the server on/off (persists the choice and (un)starts the service). */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = StoragePreferences(context.applicationContext)
        prefs.apiServerEnabled = enabled
        _status.value = _status.value.copy(enabled = enabled)
        if (enabled) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                ApiServerService.startIntent(context.applicationContext),
            )
        } else {
            context.applicationContext.startService(
                ApiServerService.stopIntent(context.applicationContext),
            )
        }
    }

    /**
     * Reconcile the running service with the persisted flag. Call from a
     * foreground entry point (MainActivity) so a Foreground Service start is
     * permitted after the app is relaunched.
     */
    fun syncFromPrefs(context: Context) {
        val prefs = StoragePreferences(context.applicationContext)
        _status.value = _status.value.copy(enabled = prefs.apiServerEnabled)
        if (prefs.apiServerEnabled) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                ApiServerService.startIntent(context.applicationContext),
            )
        }
    }
}
