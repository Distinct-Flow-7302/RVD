package com.github.distinct_flow_7302.rvd

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.headersContentLength
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import android.os.Environment

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import java.nio.ByteBuffer

class DownloadHandler(
    private val applicationContext: Context,
    looper: Looper,
) : Handler(looper) {

    companion object {
        private const val TAG = "DownloadHandler"
        private const val MIME_TYPE = "video/mp4"
    }

    private val client = OkHttpClient()
    private val notificationHandler = NotificationHandler(applicationContext)

    private fun videoFallbackUrl(jsonUrl: String): String? {
        val response = Request.Builder().url(jsonUrl).build().let { req ->
            client.newCall(req).execute()
        }

        return if (response.code == 200) {
            JSONArray(response.body!!.string())
                .getJSONObject(0)
                .getJSONObject("data")
                .getJSONArray("children")
                .getJSONObject(0)
                .getJSONObject("data")
                .getJSONObject("secure_media")
                .getJSONObject("reddit_video")
                .getString("fallback_url")
        } else {
            null
        }
    }

    private fun audioUrl(videoUrl: String): String {
        val uri = Uri.parse(videoUrl)
        return uri.buildUpon()
            .path(uri.path?.replaceAfterLast('/', "DASH_audio.mp4"))
            .build()
            .toString()
    }

    private fun copyStream(
        ins: InputStream,
        outs: OutputStream,
        length: Long,
        offset: Int,
        width: Int
    ) {
        var bytesCopied: Long = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = ins.read(buffer)
        while (bytes >= 0) {
            outs.write(buffer, 0, bytes)
            bytesCopied += bytes
            val progress = bytesCopied.coerceAtMost(length).toDouble() * width / length
            notificationHandler.showProgressPercentage(offset + progress.toInt())
            bytes = ins.read(buffer)
        }
    }

    private fun downloadsFileUri(filename: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS
            )
        }

        return applicationContext.contentResolver.insert(
            MediaStore.Downloads.getContentUri("external"),
            values
        )!!
    }

    override fun handleMessage(msg: Message) {
        val startId = msg.arg1
        val timestamp = msg.data.getString(DownloadService.TIMESTAMP)!!
        val title = msg.data.getString(DownloadService.TITLE)!!
        val jsonUrl = msg.data.getString(DownloadService.JSON_URL)!!
        val videoFile = File(applicationContext.cacheDir, "${timestamp}_video.mp4")
        val audioFile = File(applicationContext.cacheDir, "${timestamp}_audio.mp4")
        val mergeFile = File(applicationContext.cacheDir, "${timestamp}_merge.mp4")

        notificationHandler.initBuilder(startId, title)

        try {
            val videoUrl = videoFallbackUrl(jsonUrl) ?: return
            val audioUrl = audioUrl(videoUrl)

            val hasAudio = Request.Builder().url(audioUrl).build().let { req ->
                val response = client.newCall(req).execute()
                if (response.code == 200) {
                    response.body!!.byteStream().use { input ->
                        audioFile.outputStream().use { output ->
                            copyStream(input, output, response.headersContentLength(), 0, 40)
                        }
                    }
                    true
                } else {
                    false
                }
            }

            Request.Builder().url(videoUrl).build().let { req ->
                val response = client.newCall(req).execute()
                response.body!!.byteStream().use { input ->
                    videoFile.outputStream().use { output ->
                        copyStream(input, output, response.headersContentLength(), 40, 40)
                    }
                }
            }

            val fileToWrite = if (hasAudio) {
                mux(videoFile, audioFile, mergeFile)
                mergeFile
            } else {
                videoFile
            }

            val fileLength = fileToWrite.length()
            val fileUri = downloadsFileUri("${timestamp}_${title}.mp4")
            applicationContext.contentResolver.openOutputStream(fileUri).use { output ->
                fileToWrite.inputStream().use { input ->
                    copyStream(input, output!!, fileLength, 80, 20)
                }
            }

            notificationHandler.showFinished(fileUri, MIME_TYPE)
        } catch (e: Throwable) {
            notificationHandler.showFailed()
            throw e
        } finally {
            videoFile.delete()
            audioFile.delete()
            mergeFile.delete()
        }
    }

    private fun mux(video: File, audio: File, output: File) {
        val videoEx = MediaExtractor().apply {
            setDataSource(video.absolutePath)
            selectTrack(0)
            seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        val audioEx = MediaExtractor().apply {
            setDataSource(audio.absolutePath)
            selectTrack(0)
            seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoTrack = muxer.addTrack(videoEx.getTrackFormat(0))
        val audioTrack = muxer.addTrack(audioEx.getTrackFormat(0))

        var sawEOS = false
        val offset = 100
        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val bufInfo = MediaCodec.BufferInfo()

        muxer.start()

        while (!sawEOS) {
            bufInfo.offset = offset;
            bufInfo.size = videoEx.readSampleData(buf, offset);
            if (bufInfo.size < 0) {
                sawEOS = true;
                bufInfo.size = 0;
            } else {
                bufInfo.presentationTimeUs = videoEx.sampleTime;
                bufInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                muxer.writeSampleData(videoTrack, buf, bufInfo);
                videoEx.advance();
            }
        }

        sawEOS = false
        while (!sawEOS) {
            bufInfo.offset = offset;
            bufInfo.size = audioEx.readSampleData(buf, offset);

            if (bufInfo.size < 0) {
                sawEOS = true;
                bufInfo.size = 0;
            } else {
                bufInfo.presentationTimeUs = audioEx.sampleTime;
                bufInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                muxer.writeSampleData(audioTrack, buf, bufInfo);
                audioEx.advance();
            }
        }

        muxer.stop()
        muxer.release()
    }
}
