package com.example.soundlens.audio

import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Wrapper sencillo de MediaPlayer para aislar la UI.
 */
class AudioPlayer {

    private var mp: MediaPlayer? = null
    private var onPreparedCb: ((MediaPlayer) -> Unit)? = null
    private var onCompletionCb: ((MediaPlayer) -> Unit)? = null
    private var onErrorCb: ((what: Int, extra: Int) -> Unit)? = null
    private var logger: ((String) -> Unit)? = null
    private var prepared: Boolean = false

    fun setOnPrepared(block: (MediaPlayer) -> Unit) {
        onPreparedCb = block
    }

    fun setOnCompletion(block: (MediaPlayer) -> Unit) {
        onCompletionCb = block
    }

    fun setOnError(block: (what: Int, extra: Int) -> Unit) {
        onErrorCb = block
    }

    fun setLogger(block: (String) -> Unit) {
        logger = block
    }
    fun prepare(path: String, startMs: Int = 0, playWhenReady: Boolean = false) {
        log("prepare(path=$path, startMs=$startMs, playWhenReady=$playWhenReady)")
        release(clearCallbacks = false)
        prepared = false
        mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener {
                prepared = true
                log("onPrepared() duration=${it.duration}")
                if (startMs > 0) runCatching {
                    log("seeking to $startMs ms")
                    it.seekTo(startMs)
                }.onFailure { e -> log("seek failed: ${e.message}") }

                if (playWhenReady) {
                    runCatching {
                        log("auto-starting playback")
                        it.start()
                    }.onFailure { e -> log("start failed: ${e.message}") }
                }

                onPreparedCb?.invoke(it)
            }
            setOnCompletionListener {
                log("onCompletion()")
                onCompletionCb?.invoke(it)
            }
            setOnErrorListener { _, what, extra ->
                log("onError(what=$what, extra=$extra)")
                onErrorCb?.invoke(what, extra)
                true
            }
            runCatching { setDataSource(path) }
                .onFailure { e -> log("setDataSource failed: ${e.message}") }
            prepareAsync()
            log("prepareAsync() issued")
        }
    }

    fun start() {
        if (!prepared) {
            log("start() ignored: not prepared")
            return
        }
        log("start()")
        runCatching { mp?.start() }.onFailure { e -> log("start failed: ${e.message}") }
    }
    fun pause() {
        log("pause()")
        runCatching { mp?.pause() }.onFailure { e -> log("pause failed: ${e.message}") }
    }
    fun isPlaying(): Boolean = mp?.isPlaying == true
    fun seekTo(ms: Int) {
        if (!prepared) {
            log("seekTo($ms) ignored: not prepared")
            return
        }
        runCatching { mp?.seekTo(ms) }
            .onSuccess { log("seekTo($ms) ok") }
            .onFailure { e -> log("seekTo($ms) failed: ${e.message}") }
    }

    fun duration(): Int = if (prepared) mp?.duration ?: 0 else 0
    fun currentPosition(): Int = if (prepared) mp?.currentPosition ?: 0 else 0

    fun release(clearCallbacks: Boolean = true) {
        log("release(clearCallbacks=$clearCallbacks)")
        prepared = false
        runCatching { mp?.release() } // ignore failures
        mp = null
        if (clearCallbacks) {
            onPreparedCb = null
            onCompletionCb = null
            onErrorCb = null
        }
    }
    private fun log(msg: String) { logger?.invoke("AudioPlayer: $msg") }
}
