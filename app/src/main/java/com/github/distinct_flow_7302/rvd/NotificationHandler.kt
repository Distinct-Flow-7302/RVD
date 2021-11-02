package com.github.distinct_flow_7302.rvd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.content.Intent

class NotificationHandler(private val applicationContext: Context) {
    companion object {
        private const val NAME = "RVD"
        private const val CHANNEL_ID = "com.github.distinct_flow_7302.rvd"
        private const val IMPORTANCE = NotificationManager.IMPORTANCE_LOW
        private const val DESCRIPTION = "Reddit video downloader"
        private const val TAG = "NotificationHandler"
    }

    private var notificationId = -1
    private lateinit var builder: NotificationCompat.Builder
    private var prevProgress = 0

    private val notificationManager = NotificationManagerCompat.from(applicationContext)

    init {
        val channel = NotificationChannel(CHANNEL_ID, NAME, IMPORTANCE).apply {
            description = DESCRIPTION
        }

        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(channel)
    }

    fun initBuilder(startId: Int, title: String) {
        notificationId = startId
        builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID).apply {
            setSmallIcon(R.drawable.download_notification_icon)
            setContentTitle(title)
            setContentText("Starting download")
            setProgress(0, 100, false)
            setOngoing(true)
            priority = NotificationCompat.PRIORITY_LOW
        }
        prevProgress = 0
        showNotification()
    }

    fun showProgressPercentage(currentProgress: Int) {
        val progress = currentProgress.coerceAtMost(100)
        if (progress - prevProgress < 10) return
        builder
            .setContentText("$progress %")
            .setProgress(100, progress, false)
        showNotification()
        prevProgress = progress
    }

    fun showFinished(fileUri: Uri, mime: String) {
        val shareIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent.createChooser(
                Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    type = mime
                },
                "Share video"
            ),
            PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent().apply {
                action = Intent.ACTION_VIEW
                putExtra(Intent.EXTRA_STREAM, fileUri)
                type = mime
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        builder
            .setContentText("Download complete")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.open_notification_icon, "Open", openIntent)
            .addAction(R.drawable.share_notification_icon, "Share", shareIntent)
        showNotification()
    }

    fun showFailed() {
        builder
            .setContentText("Download failed")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
        showNotification()
    }

    private fun showNotification() {
        notificationManager.notify(notificationId, builder.build())
    }
}
