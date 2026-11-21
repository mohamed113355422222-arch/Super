package com.supershade.domain.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.supershade.service.NotificationCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaRepository(private val context: Context) {

    private val _media = MutableStateFlow<MediaState?>(null)
    val media: StateFlow<MediaState?> = _media.asStateFlow()

    private val sessionManager: MediaSessionManager =
        context.getSystemService(MediaSessionManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeController: MediaController? = null
    private var artDecodeJob: Job? = null
    @Volatile private var cachedArtUri: String? = null
    @Volatile private var cachedArtBitmap: Bitmap? = null

    // Track whether the listener was successfully registered
    private var listenerRegistered = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateFromActive()
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateFromActive()
        override fun onSessionDestroyed() {
            activeController?.unregisterCallback(this)
            activeController = null
            _media.value = null
            refresh()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        trackBestController(controllers)
    }

    init {
        try {
            val component = ComponentName(context, NotificationCollector::class.java)
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, mainHandler)
            listenerRegistered = true
            refresh()
        } catch (_: SecurityException) {}
    }

    fun refresh() {
        try {
            val component = ComponentName(context, NotificationCollector::class.java)
            trackBestController(sessionManager.getActiveSessions(component))
        } catch (_: SecurityException) {
            _media.value = null
        }
    }

    fun play() { activeController?.transportControls?.play() }
    fun pause() { activeController?.transportControls?.pause() }
    fun skipNext() { activeController?.transportControls?.skipToNext() }
    fun skipPrevious() { activeController?.transportControls?.skipToPrevious() }
    fun seekTo(positionMs: Long) { activeController?.transportControls?.seekTo(positionMs) }

    fun dispose() {
        artDecodeJob?.cancel()
        ioScope.cancel()
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        if (listenerRegistered) {
            try { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
            listenerRegistered = false
        }
    }

    // ---------------------------------------------------------------------------

    private fun trackBestController(controllers: List<MediaController>?) {
        val best = controllers?.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers?.firstOrNull()

        if (best == null && _media.value == null) return

        if (best?.sessionToken != activeController?.sessionToken) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = best
            activeController?.registerCallback(controllerCallback, mainHandler)
        }
        updateFromController(best)
    }

    private fun updateFromActive() = updateFromController(activeController)

    private fun updateFromController(controller: MediaController?) {
        val meta = controller?.metadata
        if (meta == null) {
            artDecodeJob?.cancel()
            _media.value = null
            return
        }

        val embeddedArt = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val artUriStr = meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)

        val immediateArt = when {
            embeddedArt != null -> embeddedArt
            !artUriStr.isNullOrBlank() && artUriStr == cachedArtUri -> cachedArtBitmap
            else -> null
        }

        val state = MediaState(
            title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            albumArt = immediateArt,
            isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
            packageName = controller.packageName ?: "",
            duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            position = controller.playbackState?.position ?: 0L,
        )
        _media.value = state

        if (embeddedArt == null && !artUriStr.isNullOrBlank() && artUriStr != cachedArtUri) {
            artDecodeJob?.cancel()
            val token = controller.sessionToken
            artDecodeJob = ioScope.launch {
                val decoded = try {
                    val uri = android.net.Uri.parse(artUriStr)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                } catch (_: Exception) { null }
                cachedArtUri = artUriStr
                cachedArtBitmap = decoded
                if (decoded != null && activeController?.sessionToken == token) {
                    val current = _media.value
                    if (current != null && current.title == state.title) {
                        _media.value = current.copy(albumArt = decoded)
                    }
                }
            }
        }
    }
}
