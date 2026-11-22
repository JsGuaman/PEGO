package com.pego.music.data.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pego.music.PegoApp
import com.pego.music.core.image.ImageQualityEvaluator
import com.pego.music.core.network.OkHttpDownloader
import com.pego.music.domain.model.DownloadStatus
import com.pego.music.domain.model.EnrichedMetadata
import com.pego.music.domain.model.LyricsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private data class ByteRange(val start: Long, val end: Long)

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val rawTitle = inputData.getString(KEY_TITLE) ?: return Result.failure()
        val rawArtist = inputData.getString(KEY_ARTIST).orEmpty()
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: return Result.failure()
        val fallbackThumb = inputData.getString(KEY_THUMB_URL).orEmpty()

        val app = applicationContext as PegoApp
        val container = app.container
        val dao = container.database.pegoDao()

        val existingTrack = dao.getTrackById(trackId)
        if (existingTrack?.downloadStatus == DownloadStatus.COMPLETED.name) {
            return Result.success()
        }
        val durationSeconds = existingTrack?.durationSeconds ?: 0L

        val notificationId = abs(trackId.hashCode()).coerceAtLeast(1000)

        setForeground(createForegroundInfo(notificationId, rawTitle, 0))

        var tempAudioFile: File? = null
        var tempCoverFile: File? = null

        return try {
            dao.updateDownloadState(trackId, DownloadStatus.DOWNLOADING.name, 5)

            lateinit var enriched: EnrichedMetadata
            var coverUrl = fallbackThumb
            var lyricsResult: LyricsResult? = null

            coroutineScope {
                val metadataDeferred = async(Dispatchers.IO) {
                    val meta = container.metadataEnricher.enrich(rawTitle, rawArtist)
                    val lyricsDeferred = async {
                        container.lyricsRepository.fetchLyrics(
                            artist = meta.artist.ifBlank { rawArtist },
                            title = meta.title.ifBlank { rawTitle },
                            durationSeconds = durationSeconds
                        )
                    }
                    val candidates = listOf(meta.coverUrl, fallbackThumb)
                        .filter { it.isNotBlank() }
                        .distinct()

                    var acceptedCoverFile: File? = null
                    var acceptedCoverUrl = ""

                    for (candidateUrl in candidates) {
                        val coverTarget = File(applicationContext.cacheDir, "cover_${trackId}_${candidateUrl.hashCode()}.jpg")
                        val downloadedOk = runCatching {
                            downloadBinary(container.httpClient, candidateUrl, coverTarget)
                            coverTarget.exists() && coverTarget.length() > 0
                        }.getOrDefault(false)

                        if (downloadedOk) {
                            val quality = ImageQualityEvaluator.evaluateFile(coverTarget)
                            if (quality != null && quality.isAcceptable) {
                                acceptedCoverFile = coverTarget
                                acceptedCoverUrl = candidateUrl
                                break
                            }
                        }
                        coverTarget.delete()
                    }

                    Triple(meta, acceptedCoverUrl, acceptedCoverFile) to lyricsDeferred.await()
                }

                val streamUrl = container.extractorRepo.resolveBestM4aAudioUrl(sourceUrl)

                val audioTarget = File(applicationContext.cacheDir, "dl_$trackId.m4a")
                tempAudioFile = audioTarget
                downloadAudioFast(
                    client = container.httpClient,
                    url = streamUrl,
                    destFile = audioTarget
                ) { progress ->
                    val scaled = 5 + (progress * 75 / 100)
                    dao.updateDownloadState(trackId, DownloadStatus.DOWNLOADING.name, scaled)
                    setForeground(createForegroundInfo(notificationId, rawTitle, scaled))
                }

                val (metaTriple, resolvedLyrics) = metadataDeferred.await()
                enriched = metaTriple.first
                coverUrl = metaTriple.second
                tempCoverFile = metaTriple.third
                lyricsResult = resolvedLyrics
            }

            dao.updateDownloadState(trackId, DownloadStatus.TAGGING.name, 85)
            setForeground(createForegroundInfo(notificationId, rawTitle, 85))

            val saved = container.audioTagger.tagAndSaveM4a(
                tempAudioFile = tempAudioFile!!,
                coverFile = tempCoverFile,
                metadata = enriched,
                lyricsText = lyricsResult?.plainLyrics ?: lyricsResult?.syncedLyrics
            )

            dao.markDownloadCompleted(
                trackId = trackId,
                cleanTitle = enriched.title,
                artist = enriched.artist,
                album = enriched.album,
                year = enriched.year,
                hdCoverUrl = coverUrl,
                localFilePath = saved.localFilePath,
                localUri = saved.localUri,
                plainLyrics = lyricsResult?.plainLyrics,
                syncedLyrics = lyricsResult?.syncedLyrics
            )

            Result.success()
        } catch (e: Exception) {
            dao.updateDownloadState(trackId, DownloadStatus.FAILED.name, 0)
            Result.failure()
        } finally {
            tempAudioFile?.delete()
            tempCoverFile?.delete()
        }
    }

    private suspend fun downloadAudioFast(
        client: OkHttpClient,
        url: String,
        destFile: File,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val probeRequest = Request.Builder()
            .url(url)
            .header("User-Agent", OkHttpDownloader.USER_AGENT)
            .header("Range", "bytes=0-0")
            .get()
            .build()

        client.newCall(probeRequest).execute().use { probeResponse ->
            if (!probeResponse.isSuccessful) {
                throw IllegalStateException("HTTP ${probeResponse.code} descargando audio")
            }

            val totalBytes = parseTotalBytesFromContentRange(
                probeResponse.header("Content-Range")
            ) ?: probeResponse.body?.contentLength() ?: -1L

            if (probeResponse.code == 206 && totalBytes >= CHUNK_SIZE_BYTES) {
                probeResponse.close()
                downloadInParallelChunks(
                    client = client,
                    url = url,
                    destFile = destFile,
                    totalBytes = totalBytes,
                    onProgress = onProgress
                )
            } else {
                val body = probeResponse.body ?: throw IllegalStateException("Body vacío")
                streamSingleResponse(
                    body = body,
                    totalBytes = totalBytes,
                    destFile = destFile,
                    onProgress = onProgress
                )
            }
        }
    }

    private suspend fun downloadInParallelChunks(
        client: OkHttpClient,
        url: String,
        destFile: File,
        totalBytes: Long,
        onProgress: suspend (Int) -> Unit
    ) = coroutineScope {
        val chunkChannel = Channel<ByteRange>(Channel.UNLIMITED)
        var start = 0L
        while (start < totalBytes) {
            val end = (start + CHUNK_SIZE_BYTES - 1).coerceAtMost(totalBytes - 1)
            chunkChannel.send(ByteRange(start, end))
            start = end + 1
        }
        chunkChannel.close()

        val downloadedCounter = AtomicLong(0L)
        val progressTicker = launch(Dispatchers.IO) {
            var lastPct = -1
            while (isActive) {
                val pct = ((downloadedCounter.get() * 100) / totalBytes).toInt().coerceIn(0, 100)
                if (pct != lastPct && (pct - lastPct >= 3 || pct == 100)) {
                    lastPct = pct
                    onProgress(pct)
                }
                if (pct >= 100) break
                delay(350L)
            }
        }

        RandomAccessFile(destFile, "rw").use { raf ->
            raf.setLength(totalBytes)
            val fileChannel = raf.channel
            val workers = (1..PARALLEL_WORKERS).map {
                launch(Dispatchers.IO) {
                    for (range in chunkChannel) {
                        downloadChunkWithRetry(
                            client = client,
                            url = url,
                            range = range,
                            fileChannel = fileChannel,
                            downloadedCounter = downloadedCounter
                        )
                    }
                }
            }
            workers.forEach { it.join() }
        }

        progressTicker.cancel()
        onProgress(100)
    }

    private fun downloadChunkWithRetry(
        client: OkHttpClient,
        url: String,
        range: ByteRange,
        fileChannel: FileChannel,
        downloadedCounter: AtomicLong
    ) {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < 3) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", OkHttpDownloader.USER_AGENT)
                    .header("Range", "bytes=${range.start}-${range.end}")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} en rango ${range.start}-${range.end}")
                    }
                    val stream = response.body?.byteStream()
                        ?: throw IllegalStateException("Chunk vacío")

                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var writeOffset = range.start
                    while (true) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        val byteBuffer = ByteBuffer.wrap(buffer, 0, read)
                        while (byteBuffer.hasRemaining()) {
                            writeOffset += fileChannel.write(byteBuffer, writeOffset)
                        }
                        downloadedCounter.addAndGet(read.toLong())
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt < 3) {
                    Thread.sleep(150L * attempt)
                }
            }
        }
        throw lastError ?: IllegalStateException("Error descargando chunk")
    }

    private suspend fun streamSingleResponse(
        body: okhttp3.ResponseBody,
        totalBytes: Long,
        destFile: File,
        onProgress: suspend (Int) -> Unit
    ) {
        var downloadedBytes = 0L
        var lastReportedTime = 0L
        var lastReportedPct = -1

        body.byteStream().use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalBytes > 0) {
                        val now = SystemClock.elapsedRealtime()
                        val pct = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if ((now - lastReportedTime >= 350L && pct != lastReportedPct) || pct == 100) {
                            lastReportedTime = now
                            lastReportedPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
    }

    private fun parseTotalBytesFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex < 0 || slashIndex == contentRange.lastIndex) return null
        return contentRange.substring(slashIndex + 1).trim().toLongOrNull()
    }

    private fun downloadBinary(
        client: OkHttpClient,
        url: String,
        destFile: File
    ) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val body = response.body ?: return
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun createForegroundInfo(notificationId: Int, title: String, progress: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            PegoApp.DOWNLOAD_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Descargando: $title")
            .setContentText(if (progress >= 85) "Verificando PSNR e incrustando tags..." else "$progress%")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_THUMB_URL = "thumb_url"

        private const val CHUNK_SIZE_BYTES = 256L * 1024L
        private const val PARALLEL_WORKERS = 6
        private const val IO_BUFFER_SIZE = 64 * 1024
    }
}
