package com.soulseekplus.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Mini oynatıcının görünür durumu. [uri] null ise oynatıcı boştadır. */
data class PlayerState(
    val uri: String? = null,
    val title: String = "",
    val preparing: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val failed: Boolean = false,
) {
    val hasMedia: Boolean get() = uri != null
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * İndirilen bir ses dosyasını önizleyen küçük oynatıcı (`android.media.MediaPlayer`). Tüm genel metotlar ana iş
 * parçacığından çağrılır. Ses odağı alınır; odak kaybında duraklatır. Uygulama ömrü boyunca yaşar.
 */
class MiniPlayerController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** [uri] (content:// ya da file://) adresini çalmaya başlar; öncekini durdurur. */
    fun play(uri: String, title: String) {
        releasePlayer()
        _state.value = PlayerState(uri = uri, title = title, preparing = true)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(appContext, Uri.parse(uri))
            mp.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                _state.update { it.copy(preparing = false, durationMs = prepared.duration.coerceAtLeast(0)) }
                if (requestFocus()) {
                    prepared.start()
                    _state.update { it.copy(playing = true) }
                    startTicker()
                }
            }
            mp.setOnCompletionListener { finished ->
                if (player !== finished) return@setOnCompletionListener
                stopTicker()
                abandonFocus()
                _state.update { it.copy(playing = false, positionMs = 0) }
            }
            mp.setOnErrorListener { failedPlayer, _, _ ->
                if (player === failedPlayer) {
                    stopTicker()
                    abandonFocus()
                    releasePlayer()
                    _state.update { it.copy(preparing = false, playing = false, failed = true) }
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            _state.update { it.copy(preparing = false, playing = false, failed = true) }
        }
    }

    fun pause() {
        val mp = player ?: return
        if (_state.value.preparing) return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (e: IllegalStateException) {
            return
        }
        stopTicker()
        _state.update { it.copy(playing = false, positionMs = safePosition(mp)) }
    }

    fun resume() {
        val mp = player ?: return
        if (_state.value.preparing || !requestFocus()) return
        try {
            mp.start()
        } catch (e: IllegalStateException) {
            return
        }
        _state.update { it.copy(playing = true) }
        startTicker()
    }

    fun toggle() {
        if (_state.value.playing) pause() else resume()
    }

    fun seekTo(positionMs: Int) {
        val mp = player ?: return
        if (_state.value.preparing) return
        try {
            mp.seekTo(positionMs.coerceIn(0, _state.value.durationMs))
        } catch (e: IllegalStateException) {
            return
        }
        _state.update { it.copy(positionMs = positionMs.coerceIn(0, it.durationMs)) }
    }

    /** Oynatmayı durdurur ve mini oynatıcıyı kapatır. */
    fun stop() {
        stopTicker()
        abandonFocus()
        releasePlayer()
        _state.value = PlayerState()
    }

    // ------------------------------------------------------------------ iç

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        mp.setOnPreparedListener(null)
        mp.setOnCompletionListener(null)
        mp.setOnErrorListener(null)
        try {
            mp.release()
        } catch (e: Exception) {
            // zaten bırakılmış
        }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                _state.update { it.copy(positionMs = safePosition(mp)) }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun safePosition(mp: MediaPlayer): Int = try {
        mp.currentPosition.coerceAtLeast(0)
    } catch (e: IllegalStateException) {
        0
    }

    private fun requestFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch { pause() }
                }
            }
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private companion object {
        const val TICK_MS = 400L
    }
}
