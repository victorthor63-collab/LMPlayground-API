package com.druk.lmplayground.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

class ApiServerFragment : Fragment() {

    private val viewModel: ApiServerViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val status by viewModel.status.collectAsStateWithLifecycle()
                val loadedModel by viewModel.loadedModel.collectAsStateWithLifecycle()
                val port by viewModel.port.collectAsStateWithLifecycle()
                val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
                ApiServerScreen(
                    status = status,
                    loadedModel = loadedModel,
                    port = port,
                    apiKey = apiKey,
                    onEnabledChange = { viewModel.setEnabled(it) },
                    onPortChange = { viewModel.setPort(it) },
                    onRegenerateKey = { viewModel.regenerateKey() },
                    onCopy = { copyToClipboard(it) },
                    onOpenLogClick = {
                        findNavController().navigate(R.id.action_api_server_to_log)
                    },
                    onBackClick = { findNavController().popBackStack() },
                )
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        cm.setPrimaryClip(ClipData.newPlainText("LM Playground API", text))
        Toast.makeText(requireContext(), R.string.api_server_copied, Toast.LENGTH_SHORT).show()
    }
}
