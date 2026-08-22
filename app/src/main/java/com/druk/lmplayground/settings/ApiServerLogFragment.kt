package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.apiserver.ApiRequestLog
import com.druk.lmplayground.theme.PlaygroundTheme

class ApiServerLogFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val entries by ApiRequestLog.entries.collectAsStateWithLifecycle()
                ApiServerLogScreen(
                    entries = entries,
                    onClear = { ApiRequestLog.clear() },
                    onBackClick = { findNavController().popBackStack() },
                )
            }
        }
    }
}
