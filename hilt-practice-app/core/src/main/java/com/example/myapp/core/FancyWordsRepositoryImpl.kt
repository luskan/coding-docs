package com.example.myapp.core

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class FancyWordsRepositoryImpl @Inject constructor() : WordsRepository {
    override val instanceId: Int = nextInstanceId.incrementAndGet()

    private val requests = AtomicInteger()

    override val requestCount: Int
        get() = requests.get()

    override fun getWords(): List<String> {
        requests.incrementAndGet()
        return listOf("dragonfruit", "kumquat", "persimmon")
    }

    private companion object {
        val nextInstanceId = AtomicInteger()
    }
}
