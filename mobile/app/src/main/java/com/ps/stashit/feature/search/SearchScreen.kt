package com.ps.stashit.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.util.CoilUtils.result
import com.ps.stashit.data.domain.SavedItem
import com.ps.stashit.data.domain.SearchResult

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onOpenUrl: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            if (effect is SearchEffect.OpenUrl) onOpenUrl(effect.url)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onEvent((SearchEvent.QueryChanged(it))) },
            placeholder = { Text("Search your stash") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onEvent(SearchEvent.QueryChanged("")) }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )

        when {
            state.isSearching -> LoadingState()
            !state.hasSearched -> SearchPromptState()
            state.results.isEmpty() -> NoResultsState(state.query)
            else -> SearchResultsList(
                results = state.results,
                onOpen = { viewModel.onEvent(SearchEvent.OpenResult(it)) }
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<SearchResult>,
    onOpen: (SavedItem) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(results, key = { it.item.id }) { result ->
            SearchResultRow(result = result, onClick = {onOpen(result.item)})
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: ()->Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(12.dp)){
        Row(
           modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ){
            AsyncImage(
                model = result.item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.item.extractedTitle?: result.item.caption?: result.item.url,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = result.item.platform.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchPromptState() {
    Box(modifier =Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text ("Search by what you remember - vide, topic, or words from the caption")
    }
}

@Composable
private fun NoResultsState(query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No matches for \"$query\"")
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}