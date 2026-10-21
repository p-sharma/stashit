package com.ps.stashit.feature.capture

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.ps.stashit.ui.theme.StashItTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {
    private val viewModel: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent
            .takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)

        if(sharedText.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            StashItTheme {
                CaptureScreen(viewModel)
            }
        }

        viewModel.onEvent(CaptureEvent.ShareReceived(sharedText))

        lifecycleScope.launch {
            viewModel.effect.collect { effect ->
                if (effect is CaptureEffect.Dismiss) finish()
            }
        }
    }
}