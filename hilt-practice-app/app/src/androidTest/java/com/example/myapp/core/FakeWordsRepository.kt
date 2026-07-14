package com.example.myapp.core

class FakeWordsRepository(
    private val words: List<String>,
) : WordsRepository {

    override val instanceId: Int = 0

    private var requests: Int = 0

    override val requestCount: Int
        get() = requests

    override fun getWords(): List<String> {
        requests++
        return words
    }
}
