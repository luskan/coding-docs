package com.example.myapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapp.core.AsyncWordsLoader
import com.example.myapp.core.WordSyncManager
import com.example.myapp.core.WordSyncState
import com.example.myapp.core.di.MainDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class Part8ViewModel @Inject constructor(
    private val loader: AsyncWordsLoader,
    private val syncManager: WordSyncManager,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    var asyncLoadStatus: AsyncLoadStatus by mutableStateOf(AsyncLoadStatus.IDLE)
        private set

    var asyncWords: List<String> by mutableStateOf(emptyList())
        private set

    val syncState: StateFlow<WordSyncState> = syncManager.state

    fun loadWords() {
        if (asyncLoadStatus == AsyncLoadStatus.RUNNING) return

        viewModelScope.launch(mainDispatcher) {
            asyncLoadStatus = AsyncLoadStatus.RUNNING
            asyncWords = loader.load()
            asyncLoadStatus = AsyncLoadStatus.COMPLETE
        }
    }

    fun syncInApplicationScope() {
        syncManager.syncInBackground()
    }
}

enum class AsyncLoadStatus {
    IDLE,
    RUNNING,
    COMPLETE,
}
