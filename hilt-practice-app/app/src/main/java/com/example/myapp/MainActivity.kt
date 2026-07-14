package com.example.myapp

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.myapp.core.WordComparison
import com.example.myapp.core.WordManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var wordManager: WordManager
    @Inject lateinit var wordComparison: WordComparison

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val managerWordView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
        }
        val basicWordView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
        }
        val fancyWordView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
        }
        val nextButton = Button(this).apply {
            setText(R.string.compare_again)
            setOnClickListener {
                showWords(managerWordView, basicWordView, fancyWordView)
            }
        }

        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                setText(R.string.part_3_title)
                textSize = 22f
            })
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                setPadding(0, padding, 0, padding)
                setText(R.string.part_3_key)
                textSize = 16f
            })
            addView(managerWordView)
            addView(basicWordView)
            addView(fancyWordView)
            addView(nextButton)
        })

        showWords(managerWordView, basicWordView, fancyWordView)
    }

    private fun showWords(
        managerWordView: TextView,
        basicWordView: TextView,
        fancyWordView: TextView,
    ) {
        val sample = wordComparison.nextSample()
        managerWordView.text = getString(R.string.manager_uses_fancy, wordManager.nextWord())
        basicWordView.text = getString(R.string.basic_word, sample.basic)
        fancyWordView.text = getString(R.string.fancy_word, sample.fancy)
    }
}
