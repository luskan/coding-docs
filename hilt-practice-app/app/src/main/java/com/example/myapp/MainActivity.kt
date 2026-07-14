package com.example.myapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.myapp.core.WordSession
import com.example.myapp.ui.WordScreen
import com.example.myapp.ui.WordViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var wordSession: WordSession
    @Inject lateinit var sameWordSession: WordSession

    private val activityViewModel: WordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WordScreen(
                        activityViewModel = activityViewModel,
                        activitySessionId = wordSession.instanceId,
                        activitySessionsSame = wordSession === sameWordSession,
                    )
                }
            }
        }
    }
}
