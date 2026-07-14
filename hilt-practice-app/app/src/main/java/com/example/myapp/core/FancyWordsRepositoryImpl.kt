package com.example.myapp.core

import javax.inject.Inject

class FancyWordsRepositoryImpl @Inject constructor() : WordsRepository {
    override fun getWords(): List<String> = listOf("dragonfruit", "kumquat", "persimmon")
}
