package com.ps.stashit.feature.library

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ps.stashit.core.database.Platform
import com.ps.stashit.data.domain.SavedItem

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onOpenUrl: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackBarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is LibraryEffect.OpenUrl -> onOpenUrl(effect.url)
                is LibraryEffect.ShowUndoDelete -> {
                    val result = snackBarHostState.showSnackbar(
                        message = "Removed from stash",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost((snackBarHostState)) }) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))
            state.isEmpty -> EmptyLibraryState(Modifier.padding(padding))
            else -> LibraryGrid(
                items = state.items,
                onOpen = { viewModel.onEvent(LibraryEvent.OpenItem(it)) },
                onDelete = { viewModel.onEvent(LibraryEvent.DeleteItem(it)) },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LibraryGrid(
    items: List<SavedItem>,
    onOpen: (SavedItem) -> Unit,
    onDelete: (SavedItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id }) { item ->
            SavedItemCard(
                item = item,
                onClick = { onOpen(item) },
                onDelete = { onDelete(item) }
            )
        }
    }
}

@Composable
private fun SavedItemCard(
    item: SavedItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                PlatformBadge(
                    platform = item.platform,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
                if (!item.isEmbedded) {
                    ProcessingDot(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = Color.White
                    )
                }
            }

            Text(
                text = item.extractedTitle ?: item.caption ?: item.url,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun ProcessingDot(modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.tertiary, CircleShape)
    )
}

@Composable
private fun PlatformBadge(platform: Platform, modifier: Modifier = Modifier) {
    val (label, color) = when(platform) {
        Platform.YOUTUBE_SHORTS -> "YouTube" to Color(0xFFFF0000)
        Platform.TIKTOK -> "TikTok" to Color(0xFF000000)
        Platform.INSTAGRAM_REELS -> "Reels" to Color(0xFFC13584)
        Platform.UNKNOWN -> "Link" to Color.Gray
    }

    Box(
        modifier = modifier.background(color.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ){
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun EmptyLibraryState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text("Nothing stashed yet")
            Text(
                "Share a reel or short to get started",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}