package com.example.myapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.myapp.core.di.MainDispatcher
import com.example.myapp.work.SyncWordsWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@HiltViewModel
class Part9ViewModel @Inject constructor(
    private val workManagerProvider: Provider<WorkManager>,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    var workerState: WorkInfo.State? by mutableStateOf(null)
        private set

    var workerWords: List<String> by mutableStateOf(emptyList())
        private set

    private var observationJob: Job? = null

    fun enqueueSync() {
        observationJob?.cancel()

        val workManager = workManagerProvider.get()
        val request = OneTimeWorkRequestBuilder<SyncWordsWorker>().build()

        workerState = WorkInfo.State.ENQUEUED
        workerWords = emptyList()
        observationJob = viewModelScope.launch(mainDispatcher) {
            val finishedInfo = workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .onEach { info ->
                    workerState = info.state
                }
                .first { info -> info.state.isFinished }
            workerWords = finishedInfo.outputData
                .getStringArray(SyncWordsWorker.OUTPUT_WORDS)
                ?.toList()
                .orEmpty()
        }

        workManager.enqueue(request)
    }
}
