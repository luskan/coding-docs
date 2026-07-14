package com.example.myapp.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapp.core.AsyncWordsLoader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWordsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val loader: AsyncWordsLoader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val words = loader.load()
        return Result.success(
            workDataOf(OUTPUT_WORDS to words.toTypedArray()),
        )
    }

    companion object {
        const val OUTPUT_WORDS = "output_words"
    }
}
