package com.github.distinct_flow_7302.rvd

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import java.io.*

class DownloadService : Service() {
    companion object {
        const val DATA = "data"

        const val TIMESTAMP = "timestamp"
        const val TITLE = "title"
        const val JSON_URL = "json-url"
    }

    private lateinit var serviceLooper: Looper
    private lateinit var serviceHandler: DownloadHandler

    override fun onCreate() {
        HandlerThread("ServiceStartArguments", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = DownloadHandler(applicationContext, looper)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        intent.getBundleExtra(DATA)?.let { data ->
            serviceHandler.obtainMessage().also { msg ->
                msg.arg1 = startId
                msg.data = data
                serviceHandler.sendMessage(msg)
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder? = null
}
