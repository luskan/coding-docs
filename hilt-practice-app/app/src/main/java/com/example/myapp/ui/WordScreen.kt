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
) {
    val detailViewModel: WordDetailViewModel =
        hiltViewModel<WordDetailViewModel, WordDetailViewModel.Factory>(
            creationCallback = { factory -> factory.create(DETAIL_WORD_ID) },
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Part 5 · Hilt ViewModels",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        Text("ViewModelProvider owns when; Hilt owns how")
        Spacer(Modifier.height(24.dp))
        Text(
            text = composeViewModel.currentWord,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text("SavedStateHandle key: word")
        Text("Saved changes: ${composeViewModel.savedChanges}")
        Spacer(Modifier.height(16.dp))
        Text("Activity delegate === Compose: ${activityViewModel === composeViewModel}")
        Text("ViewModel instance #${composeViewModel.instanceId}")
        Text("@ActivityScoped session #$activitySessionId")
        Text("Two session injections same: $activitySessionsSame")
        Spacer(Modifier.height(16.dp))
        Text(detailViewModel.description)
        Spacer(Modifier.height(24.dp))
        Button(onClick = composeViewModel::chooseAndSaveAnotherWord) {
            Text("Choose and save another word")
        }
    }
}

private const val DETAIL_WORD_ID = 7
