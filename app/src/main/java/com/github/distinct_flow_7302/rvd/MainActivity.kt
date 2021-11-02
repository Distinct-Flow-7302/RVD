package com.github.distinct_flow_7302.rvd

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import java.util.*

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val df by lazy {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startDownloadService(intent)
        setContentView(R.layout.activity_main)
    }

    override fun onNewIntent(intent: Intent?) {
        startDownloadService(intent)
        super.onNewIntent(intent)
    }

    private fun jsonUrl(uri: Uri): String {
        return uri.buildUpon().clearQuery().appendPath(".json").build().toString()
    }

    private fun startDownloadService(intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { url ->
                val uri = Uri.parse(url)

                startService(Intent(this, DownloadService::class.java).apply {
                    putExtra(DownloadService.DATA, Bundle().apply {
                        putString(DownloadService.TITLE, uri.lastPathSegment)
                        putString(DownloadService.TIMESTAMP, df.format(Date()))
                        putString(DownloadService.JSON_URL, jsonUrl(uri))
                    })
                })
            }
        }
    }
}
