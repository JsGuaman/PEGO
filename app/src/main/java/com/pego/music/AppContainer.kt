package com.pego.music

import android.content.Context
import com.pego.music.core.network.OkHttpDownloader
import com.pego.music.data.audio.AudioTrimmer
import com.pego.music.data.download.DownloadOrchestrator
import com.pego.music.data.extractor.YouTubeExtractorRepository
import com.pego.music.data.local.LocalLibraryScanner
import com.pego.music.data.local.PegoDatabase
import com.pego.music.data.metadata.LyricsRepository
import com.pego.music.data.metadata.MetadataEnricher
import com.pego.music.data.recommend.RecommendationEngine
import com.pego.music.data.tagger.AudioTagger
import com.pego.music.player.PlayerController
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val httpClient: OkHttpClient = OkHttpDownloader.getInstance().client
    val database: PegoDatabase = PegoDatabase.getInstance(appContext)
    val localLibraryScanner = LocalLibraryScanner(appContext, database.pegoDao())
    val extractorRepo = YouTubeExtractorRepository()
    val metadataEnricher = MetadataEnricher(httpClient)
    val lyricsRepository = LyricsRepository(httpClient)
    val recommendationEngine = RecommendationEngine(httpClient, extractorRepo)
    val audioTagger = AudioTagger(appContext)
    val audioTrimmer = AudioTrimmer(appContext)
    val downloadOrchestrator = DownloadOrchestrator(appContext, database.pegoDao())
    val playerController = PlayerController(appContext, extractorRepo)
}
