package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.FakeWordsRepository
import com.example.myapp.core.WordManager
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.di.FancyWords
import com.example.myapp.core.di.RepositoryModule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
@UninstallModules(RepositoryModule::class)
class WordManagerBindValueTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @field:BindValue
    @field:FancyWords
    @JvmField
    val fancyRepository: WordsRepository = FakeWordsRepository()

    @Inject lateinit var wordManager: WordManager

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun perTestBindingReplacesTheProductionModule() {
        assertEquals("test-word", wordManager.nextWord())
        assertEquals(1, fancyRepository.requestCount)
    }
}
