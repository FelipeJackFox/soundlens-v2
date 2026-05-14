package com.example.soundlens.ui.result

import com.example.soundlens.data.models.IdentifyResponse
import java.io.File

data class ResultUiState(
    val loading: Boolean = false,
    val audioReady: Boolean = false,
    val isPlaying: Boolean = false,
    val elapsedMs: Int = 0,
    val remainingMs: Int = 0,
    val durationMs: Int = 0,
    val highlightMs: Int = 0,
    val prettyJson: String = "",
    val metaTitle: String = "—",
    val metaSubtitle: String = "—",
    val albumCoverUrl: String? = null,
    val response: IdentifyResponse? = null,
    val localFile: File? = null,
    val showRadar: Boolean = false,
    val error: String? = null
)
