package com.example.myapp

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(padding, padding, padding, padding)
                val applicationRegistered = if (application is MyApplication) "yes" else "no"
                text = getString(R.string.setup_ready, applicationRegistered)
                textSize = 20f
            },
        )
    }
}
