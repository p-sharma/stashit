package com.ps.stashit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ps.stashit.feature.library.LibraryScreen
import com.ps.stashit.ui.theme.StashItTheme
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.ps.stashit.core.EmbeddingScheduler
import com.ps.stashit.feature.search.SearchScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StashItTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.GridView, contentDescription = null) },
                                label = { Text("Library") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                                label = { Text("Search") }
                            )
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        val onOpenUrl: (String) -> Unit = { url ->
                            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }
                        when (selectedTab) {
                            0 -> LibraryScreen(onOpenUrl = onOpenUrl)
                            1 -> SearchScreen(onOpenUrl = onOpenUrl)
                        }
                    }
                }
            }
        }
    }
}
