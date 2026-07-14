package com.example.myapp.core

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WordsRepositoryImpl @Inject constructor() : WordsRepository {
    override val instanceId: Int = nextInstanceId.incrementAndGet()

    private val requests = AtomicInteger()

    override val requestCount: Int
        get() = requests.get()

    override fun getWords(): List<String> {
        requests.incrementAndGet()
        return listOf("apple", "banana", "cherry")
    }

    private companion object {
        val nextInstanceId = AtomicInteger()
    }
}
