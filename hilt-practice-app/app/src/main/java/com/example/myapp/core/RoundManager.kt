package com.example.myapp.core

import javax.inject.Inject
import javax.inject.Provider

class RoundManager @Inject constructor(
    private val roundProvider: Provider<GameRound>,
) {
    fun startRound(): GameRound = roundProvider.get()
}
