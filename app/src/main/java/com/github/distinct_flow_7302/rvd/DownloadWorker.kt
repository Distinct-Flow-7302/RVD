package com.github.distinct_flow_7302.rvd

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.random.Random
import android.webkit.MimeTypeMap

class DownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        const val TIMESTAMP = "timestamp"
        const val TITLE = "title"
        const val JSON_URL = "json-url"
    }

    private val timestamp = params.inputData.getInt(TIMESTAMP, Random.Default.nextInt())
    private val title = params.inputData.getString(TITLE)!!
    private val jsonUrl = params.inputData.getString(JSON_URL)!!

    private val client = OkHttpClient()
    private val notificationHandler = NotificationHandler(context, timestamp, title)

    override fun doWork(): Result {
        try {
            val response = Request.Builder().url(jsonUrl).build().let { req ->
                client.newCall(req).execute()
            }

            val json = JSONArray(response.body!!.string())

            var sourceUrl = videoUrlFromJson(json)
            if (sourceUrl != null) {
                return doWorkVideo(sourceUrl)
            }

            sourceUrl = imageUrlFromJson(json)
            if (sourceUrl != null) {
                return doWorkImage(sourceUrl)
            }

            return Result.failure()
        } catch (e: Throwable) {
            return Result.failure()
        }
    }

    private fun videoUrlFromJson(json: JSONArray): String? {
        return try {
            json
                .getJSONObject(0)
                .getJSONObject("data")
                .getJSONArray("children")
                .getJSONObject(0)
                .getJSONObject("data")
                .getJSONObject("secure_media")
                .getJSONObject("reddit_video")
                .getString("fallback_url")
        } catch (e: JSONException) {
            null
        }
    }

    private fun imageUrlFromJson(json: JSONArray): String? {
        return try {
            json
                .getJSONObject(0)
                .getJSONObject("data")
                .getJSONArray("children")
                .getJSONObject(0)
                .getJSONObject("data")
                .getString("url_overridden_by_dest")
        } catch (e: JSONException) {
            null
        }
    }

    private fun doWorkImage(imageUrl: String): Result {
        val imageFile = File(context.cacheDir, "$timestamp")
        try {
            notificationHandler.newSegment(0, 80)
            Request.Builder().url(imageUrl).build().let { req ->
                val response = client.newCall(req).execute()
                response.body!!.byteStream().use { input->
                    imageFile.outputStream().use { output ->
                        input.copyTo(output, response.body!!.contentLength())
                    }
                }
            }

            notificationHandler.newSegment(80, 20)
            return saveToDownloads(imageFile, MimeTypeMap.getFileExtensionFromUrl(imageUrl))
        } catch (e: Throwable) {
            return downloadFailed()
        } finally {
            imageFile.delete()
        }
    }

    private fun doWorkVideo(videoUrl: String): Result {
        val videoFile = File(context.cacheDir, "${timestamp}_video")
        val audioFile = File(context.cacheDir, "${timestamp}_audio")
        val mergeFile = File(context.cacheDir, "${timestamp}_merge")

        try {
            val audioUrl = Uri.parse(videoUrl).let { uri ->
                val path = uri.path?.replaceAfterLast('/', "DASH_audio.mp4")
                uri.buildUpon().path(path).build().toString()
            }

            notificationHandler.newSegment(5, 20)
            val audioLen = downloadAudio(audioUrl, audioFile)

            notificationHandler.newSegment(25, 50)
            val videoLen = downloadVideo(videoUrl, videoFile)

            val input = if (audioLen != 0L) {
                notificationHandler.newSegment(75, 15)
                mux(videoFile, audioFile, mergeFile, videoLen + audioLen)

                mergeFile
            } else {
                videoFile
            }

            notificationHandler.newSegment(90, 10)
            return saveToDownloads(input, MimeTypeMap.getFileExtensionFromUrl(videoUrl))
        } catch (e: Throwable) {
            return downloadFailed()
        } finally {
            videoFile.delete()
            audioFile.delete()
            mergeFile.delete()
        }
    }

    private fun downloadAudio(audioUrl: String, audioFile: File): Long {
        return Request.Builder().url(audioUrl).build().let { req ->
            val response = client.newCall(req).execute()
            if (response.code == 200) {
                response.body!!.byteStream().use { input ->
                    audioFile.outputStream().use { output ->
                        input.copyTo(output, response.body!!.contentLength())
                    }
                }
            } else {
                0
            }
        }
    }

    private fun InputStream.copyTo(out: OutputStream, estimatedLen: Long): Long {
        var bytesCopied: Long = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = read(buffer)
        while (bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            notificationHandler.setProgressPercentage(bytesCopied, estimatedLen)
            bytes = read(buffer)
        }
        return bytesCopied
    }

    private fun downloadVideo(videoUrl: String, videoFile: File): Long {
        return Request.Builder().url(videoUrl).build().let { req ->
            val response = client.newCall(req).execute()
            response.body!!.byteStream().use { input ->
                videoFile.outputStream().use { output ->
                    input.copyTo(output, response.body!!.contentLength())
                }
            }
        }
    }

    private fun saveToDownloads(file: File, ext: String): Result {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS
            )
        }

        val uri = context.contentResolver.insert(
            MediaStore.Downloads.getContentUri("external"),
            values
        )!!

        context.contentResolver.openOutputStream(uri).use { output ->
            file.inputStream().use { input ->
                input.copyTo(output!!, file.length())
            }
        }

        notificationHandler.showFinished(uri, mime)
        return Result.success()
    }

    private fun mux(video: File, audio: File, output: File, estimatedLen: Long) {
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
            bufInfo.offset = offset
            bufInfo.size = videoEx.readSampleData(buf, offset)
            notificationHandler.setProgressPercentage(bufInfo.size.toLong(), estimatedLen)

            if (bufInfo.size < 0) {
                sawEOS = true
                bufInfo.size = 0
            } else {
                bufInfo.presentationTimeUs = videoEx.sampleTime
                bufInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                muxer.writeSampleData(videoTrack, buf, bufInfo)
                videoEx.advance()
            }
        }

        sawEOS = false
        while (!sawEOS) {
            bufInfo.offset = offset
            bufInfo.size = audioEx.readSampleData(buf, offset)
            notificationHandler.setProgressPercentage(bufInfo.size.toLong(), estimatedLen)

            if (bufInfo.size < 0) {
                sawEOS = true
                bufInfo.size = 0
            } else {
                bufInfo.presentationTimeUs = audioEx.sampleTime
                bufInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                muxer.writeSampleData(audioTrack, buf, bufInfo)
                audioEx.advance()
            }
        }

        muxer.stop()
        muxer.release()
    }

    private fun downloadFailed(): Result {
        notificationHandler.showFailed()
        return Result.failure()
    }
}