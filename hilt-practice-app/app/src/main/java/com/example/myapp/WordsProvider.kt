package com.example.myapp

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.example.myapp.core.di.WordsEntryPoint
import dagger.hilt.android.EntryPointAccessors

class WordsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val providerContext = requireNotNull(context)
        requireSupportedUri(providerContext, uri)

        val columns = projection ?: arrayOf(COLUMN_WORD)
        require(columns.contentEquals(arrayOf(COLUMN_WORD))) {
            "Unsupported projection: ${columns.contentToString()}"
        }

        val wordManager = EntryPointAccessors
            .fromApplication(providerContext.applicationContext, WordsEntryPoint::class.java)
            .wordManager()

        return MatrixCursor(columns, 1).apply {
            addRow(arrayOf(wordManager.nextWord()))
        }
    }

    override fun getType(uri: Uri): String {
        val providerContext = requireNotNull(context)
        requireSupportedUri(providerContext, uri)
        return wordMimeType(providerContext)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun requireSupportedUri(context: Context, uri: Uri) {
        require(uri == randomWordUri(context)) { "Unsupported URI: $uri" }
    }

    companion object {
        const val COLUMN_WORD = "word"

        private const val AUTHORITY_SUFFIX = ".words"
        private const val PATH_RANDOM = "random"

        fun randomWordUri(context: Context): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(context.packageName + AUTHORITY_SUFFIX)
            .appendPath(PATH_RANDOM)
            .build()

        fun wordMimeType(context: Context): String =
            "${ContentResolver.CURSOR_ITEM_BASE_TYPE}/vnd.${context.packageName}.word"
    }
}
