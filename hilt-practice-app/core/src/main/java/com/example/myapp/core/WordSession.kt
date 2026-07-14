package com.example.myapp.core

import dagger.hilt.android.scopes.ActivityScoped
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@ActivityScoped
class WordSession @Inject constructor() {
    val instanceId: Int = nextInstanceId.incrementAndGet()
    var wordsSeen: Int = 0

    private companion object {
        val nextInstanceId = AtomicInteger()
    }
}
