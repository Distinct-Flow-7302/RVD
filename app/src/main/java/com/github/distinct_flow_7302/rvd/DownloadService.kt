package com.github.distinct_flow_7302.rvd

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.*
import android.os.Process
import org.json.JSONArray
import java.io.*
import com.arthenica.ffmpegkit.FFmpegKit
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadService : Service() {
    private lateinit var serviceLooper: Looper
    private lateinit var serviceHandler: ServiceHandler

    private lateinit var client: OkHttpClient

    private lateinit var video: File
    private lateinit var audio: File
    private lateinit var download: File

    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        private fun getJsonUrl(msg: Message): String? {
            val url = msg.data.getString("url") ?: return null
            return Uri.parse(url)
                .buildUpon()
                .clearQuery()
                .appendPath(".json")
                .toString()
        }

        private fun getVideoUrl(jsonUrl: String): String? {
            val res = client.newCall(Request.Builder().url(jsonUrl).build()).execute()
            return JSONArray(res.body!!.string())
                .getJSONObject(0)
                .getJSONObject("data")
                .getJSONArray("children")
                .getJSONObject(0)
                .getJSONObject("data")
                .getJSONObject("secure_media")
                .getJSONObject("reddit_video")
                .getString("fallback_url")
        }

        private fun getAudioUrl(videoUrl: String): String {
            val uri = Uri.parse(videoUrl)
            val path = uri.path?.replaceAfterLast('/', "DASH_audio.mp4")
            return uri.buildUpon().path(path).toString()
        }

        override fun handleMessage(msg: Message) {
            val url = getJsonUrl(msg) ?: return
            val videoUrl = getVideoUrl(url) ?: return
            val audioUrl = getAudioUrl(videoUrl)

            Request.Builder().url(videoUrl).build().let { req ->
                val res = client.newCall(req).execute()
                FileOutputStream(video).use { fos ->
                    res.body!!.byteStream().copyTo(fos)
                }
            }

            val hasAudio: Boolean
            Request.Builder().url(audioUrl).build().let { req ->
                val res = client.newCall(req).execute()
                hasAudio = res.code == 200
                if (hasAudio) {
                    FileOutputStream(audio).use { output ->
                        res.body!!.byteStream().copyTo(output)
                    }
                }
            }

            if (hasAudio) {
                FFmpegKit.execute("-y -i \"${video.path}\" -i \"${audio.path}\" -c:v copy -c:a aac \"${download.path}\"")
            } else {
                video.renameTo(download)
            }

            stopSelf(msg.arg1)
        }
    }

    override fun onCreate() {
        applicationContext.cacheDir.let { dir ->
            video = File(dir, "video.mp4")
            audio = File(dir, "audio.mp4")
        }

        download = File(applicationContext.getExternalFilesDir(null), "download.mp4")

        client = OkHttpClient()

        HandlerThread("ServiceStartArguments", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = ServiceHandler(looper)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        intent.getStringExtra("url")?.let { url ->
            serviceHandler.obtainMessage().also { msg ->
                msg.arg1 = startId
                msg.data = Bundle().apply { putString("url", url) }
                serviceHandler.sendMessage(msg)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null
}
