package com.example.myapp

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.myapp.core.WordManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var wordManager: WordManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val wordView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 28f
        }
        val nextButton = Button(this).apply {
            setText(R.string.next_word)
            setOnClickListener { wordView.showNextWord() }
        }

        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                setText(R.string.part_2_title)
                textSize = 22f
            })
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                setPadding(0, padding, 0, padding)
                setText(R.string.part_2_graph)
                textSize = 16f
            })
            addView(wordView)
            addView(nextButton)
        })

        wordView.showNextWord()
    }

    private fun TextView.showNextWord() {
        text = getString(R.string.basic_word, wordManager.nextWord())
    }
}
