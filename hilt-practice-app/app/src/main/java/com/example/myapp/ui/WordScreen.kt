package com.example.myapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun WordScreen(
    activityViewModel: WordViewModel,
    activitySessionId: Int,
    activitySessionsSame: Boolean,
    composeViewModel: WordViewModel = hiltViewModel(),
    part7ViewModel: Part7ViewModel = hiltViewModel(),
    part8ViewModel: Part8ViewModel = hiltViewModel(),
) {
    val detailViewModel: WordDetailViewModel =
        hiltViewModel<WordDetailViewModel, WordDetailViewModel.Factory>(
            creationCallback = { factory -> factory.create(DETAIL_WORD_ID) },
        )
    val syncState by part8ViewModel.syncState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "Part 8 · Injected coroutine contexts",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        Text("Main → IO · application scope → Default → IO")
        Spacer(Modifier.height(16.dp))
        Text(
            text = composeViewModel.currentWord,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text("Saved changes: ${composeViewModel.savedChanges}")
        Text("Activity delegate === Compose: ${activityViewModel === composeViewModel}")
        Text("Activity session #$activitySessionId · same: $activitySessionsSame")
        Text(detailViewModel.description)
        Spacer(Modifier.height(12.dp))
        Button(onClick = composeViewModel::chooseAndSaveAnotherWord) {
            Text("Choose and save another word")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "ContentProvider → EntryPointAccessors → WordManager",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Provider returned: ${part7ViewModel.providerWord ?: "—"}")
        Spacer(Modifier.height(8.dp))
        Button(onClick = part7ViewModel::queryContentProvider) {
            Text("Query content provider")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "dagger.Lazy<ExpensiveDictionary>",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Definitions: ${part7ViewModel.definitions}")
        Text("Dictionary constructions: ${part7ViewModel.dictionaryConstructionCount}")
        Text("Dictionary instance: ${part7ViewModel.dictionaryInstanceId.asDisplayId()}")
        Text(part7ViewModel.definitionText ?: "Definition: —")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { part7ViewModel.define(composeViewModel.currentWord) }) {
            Text("Define current word")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Provider<GameRound>",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Round IDs: ${part7ViewModel.roundIds.asDisplayIds()}")
        Text("Fresh unscoped instances: ${part7ViewModel.freshRoundInstances ?: "—"}")
        Spacer(Modifier.height(8.dp))
        Button(onClick = part7ViewModel::createTwoRounds) {
            Text("Create two rounds")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "@MainDispatcher → @IoDispatcher",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Async load: ${part8ViewModel.asyncLoadStatus.asDisplayName()}")
        Text("Async words: ${part8ViewModel.asyncWords.asDisplayWords()}")
        Spacer(Modifier.height(8.dp))
        Button(onClick = part8ViewModel::loadWords) {
            Text("Load on injected IO dispatcher")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "@ApplicationScope on @DefaultDispatcher",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Application sync: ${syncState.status.asDisplayName()}")
        Text("Completed syncs: ${syncState.completedSyncs}")
        Text("Synced words: ${syncState.words.asDisplayWords()}")
        Spacer(Modifier.height(8.dp))
        Button(onClick = part8ViewModel::syncInApplicationScope) {
            Text("Start application-scope sync")
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun Int?.asDisplayId(): String = this?.let { "#$it" } ?: "—"

private fun Pair<Int, Int>?.asDisplayIds(): String =
    this?.let { "#${it.first}, #${it.second}" } ?: "—"

private fun Enum<*>.asDisplayName(): String = name.lowercase()

private fun List<String>.asDisplayWords(): String = if (isEmpty()) "—" else joinToString()

private const val DETAIL_WORD_ID = 7
