package com.example.myapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.DictionaryConstructionTracker
import com.example.myapp.core.RoundManager
import com.example.myapp.core.WordDefiner
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class EntryPointLazyProviderTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var tracker: DictionaryConstructionTracker
    @Inject lateinit var wordDefiner: WordDefiner
    @Inject lateinit var roundManager: RoundManager

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun lazyDefersThenCachesTheDictionary() {
        assertEquals(0, tracker.constructionCount)
        assertEquals(0, wordDefiner.constructionCount)

        val first = wordDefiner.define("apple")
        assertEquals(1, tracker.constructionCount)
        assertEquals(1, wordDefiner.constructionCount)

        val second = wordDefiner.define("banana")

        assertEquals(1, tracker.constructionCount)
        assertEquals(1, wordDefiner.constructionCount)
        assertEquals(first.dictionaryInstanceId, second.dictionaryInstanceId)
        assertEquals("definition of apple", first.text)
        assertEquals("definition of banana", second.text)
    }

    @Test
    fun providerCreatesFreshUnscopedRounds() {
        val first = roundManager.startRound()
        val second = roundManager.startRound()

        assertNotSame(first, second)
        assertNotEquals(first.instanceId, second.instanceId)
        assertEquals(1, first.instanceId)
        assertEquals(2, second.instanceId)
    }

    @Test
    fun contentResolverQueryReachesTheProductionGraph() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = WordsProvider.randomWordUri(context)

        assertEquals("${context.packageName}.words", uri.authority)
        assertEquals("/random", uri.path)
        assertEquals(
            WordsProvider.wordMimeType(context),
            context.contentResolver.getType(uri),
        )

        val cursor = requireNotNull(
            context.contentResolver.query(
                uri,
                arrayOf(WordsProvider.COLUMN_WORD),
                null,
                null,
                null,
            ),
        )

        cursor.use {
            assertArrayEquals(arrayOf(WordsProvider.COLUMN_WORD), it.columnNames)
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            assertTrue(
                it.getString(it.getColumnIndexOrThrow(WordsProvider.COLUMN_WORD)) in FANCY_WORDS,
            )
            assertFalse(it.moveToNext())
        }
    }

    private companion object {
        val FANCY_WORDS = setOf("dragonfruit", "kumquat", "persimmon")
    }
}
