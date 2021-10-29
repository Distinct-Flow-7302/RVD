package com.github.distinct_flow_7302.rvd

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startDownloadService(intent)
        setContentView(R.layout.activity_main)
    }

    override fun onNewIntent(intent: Intent?) {
        startDownloadService(intent)
        super.onNewIntent(intent)
    }

    private fun startDownloadService(intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { url ->
                startService(Intent(this, DownloadService::class.java).apply {
                    putExtra("url", url)
                })
            }
        }
    }
}
