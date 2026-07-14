package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.di.FancyWords
import com.example.myapp.feature.words.FeatureWordsReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class MultiModuleGraphTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var reader: FeatureWordsReader

    @field:FancyWords
    @Inject
    lateinit var fancyRepository: WordsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun appComponentInjectsFeatureConsumerFromCoreBinding() {
        val requestsBefore = fancyRepository.requestCount

        assertTrue(reader.usesRepository(fancyRepository))
        assertTrue(reader.nextWord() in listOf("dragonfruit", "kumquat", "persimmon"))
        assertEquals(requestsBefore + 1, fancyRepository.requestCount)
    }
}
