package com.example.myapp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class WordsProviderClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun nextWord(): String {
        val cursor = requireNotNull(
            context.contentResolver.query(
                WordsProvider.randomWordUri(context),
                arrayOf(WordsProvider.COLUMN_WORD),
                null,
                null,
                null,
            ),
        )

        cursor.use {
            check(it.moveToFirst()) { "WordsProvider returned no rows" }
            return checkNotNull(
                it.getString(it.getColumnIndexOrThrow(WordsProvider.COLUMN_WORD)),
            )
        }
    }
}
