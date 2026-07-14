package com.example.myapp.core

interface WordsRepository {
    val instanceId: Int
    val requestCount: Int

    fun getWords(): List<String>
}
