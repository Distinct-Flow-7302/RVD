package com.github.distinct_flow_7302.rvd

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerDownloadRequest(intent)
        setContentView(R.layout.activity_main)
    }

    override fun onNewIntent(intent: Intent?) {
        registerDownloadRequest(intent)
        super.onNewIntent(intent)
    }

    private fun jsonUrl(uri: Uri): String {
        return uri.buildUpon().clearQuery().appendPath(".json").build().toString()
    }

    private fun registerDownloadRequest(intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { url ->
                val uri = Uri.parse(url)

                val params = Data.Builder()
                    .putInt(DownloadWorker.TIMESTAMP, System.currentTimeMillis().toInt())
                    .putString(DownloadWorker.TITLE, uri.lastPathSegment)
                    .putString(DownloadWorker.JSON_URL, jsonUrl(uri))
                    .build()

                val downloadReq = OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(params)
                    .build()

                WorkManager.getInstance(applicationContext).enqueue(downloadReq)
            }
        }
    }
}
