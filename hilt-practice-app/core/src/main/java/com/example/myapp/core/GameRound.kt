package com.example.myapp.core

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoundIdSource @Inject constructor() {
    private val nextId = AtomicInteger()

    fun nextId(): Int = nextId.incrementAndGet()
}

class GameRound @Inject constructor(
    idSource: RoundIdSource,
) {
    val instanceId: Int = idSource.nextId()
}
