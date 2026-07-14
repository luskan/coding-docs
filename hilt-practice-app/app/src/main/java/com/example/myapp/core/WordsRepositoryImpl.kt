package com.example.myapp.core

import javax.inject.Inject

class WordsRepositoryImpl @Inject constructor() : WordsRepository {
    override fun getWords(): List<String> = listOf("apple", "banana", "cherry")
}
