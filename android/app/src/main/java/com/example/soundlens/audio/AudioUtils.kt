package com.example.soundlens.audio

object AudioUtils {
    /** 43.07 fps ≈ tu índice previo de frames → milisegundos */
    fun framesToMs(frames: Int): Int = (frames * 1000.0 / 43.07).toInt()

    fun guessExt(urlOrPath: String): String {
        val u = urlOrPath.lowercase()
        return when {
            u.contains(".wav") -> ".wav"
            u.contains(".m4a") -> ".m4a"
            u.contains(".aac") -> ".aac"
            else -> ".mp3"
        }
    }
}
