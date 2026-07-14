package com.example.myapp.core

import com.example.myapp.core.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Singleton
class WordSyncManager @Inject constructor(
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val loader: AsyncWordsLoader,
) {
    private val mutableState = MutableStateFlow(WordSyncState())
    val state: StateFlow<WordSyncState> = mutableState.asStateFlow()

    private val syncLock = Any()
    private var activeJob: Job? = null

    fun syncInBackground(): Job = synchronized(syncLock) {
        activeJob?.takeIf { it.isActive } ?: appScope.launch {
            mutableState.update { it.copy(status = WordSyncStatus.RUNNING) }
            val words = loader.load()
            mutableState.update {
                WordSyncState(
                    status = WordSyncStatus.COMPLETE,
                    completedSyncs = it.completedSyncs + 1,
                    words = words,
                )
            }
        }.also {
            activeJob = it
        }
    }
}

data class WordSyncState(
    val status: WordSyncStatus = WordSyncStatus.IDLE,
    val completedSyncs: Int = 0,
    val words: List<String> = emptyList(),
)

enum class WordSyncStatus {
    IDLE,
    RUNNING,
    COMPLETE,
}
