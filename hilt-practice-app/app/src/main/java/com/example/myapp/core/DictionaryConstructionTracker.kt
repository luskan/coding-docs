package com.example.myapp.core

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictionaryConstructionTracker @Inject constructor() {
    private val constructions = AtomicInteger()

    val constructionCount: Int
        get() = constructions.get()

    fun recordConstruction(): Int = constructions.incrementAndGet()
}
