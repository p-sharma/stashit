package com.ps.stashit.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CaptureScreen(viewModel: CaptureViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when(state.status) {
                    CaptureStatus.Processing -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Saving to Stash It..")
                    }

                    CaptureStatus.Saved -> {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Saved")
                    }
                    CaptureStatus.Failed -> {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Couldn't find a link in that")
                    }
                }
            }
        }
    }
}