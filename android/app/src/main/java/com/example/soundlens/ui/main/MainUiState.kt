package com.example.soundlens.ui.main

data class MainUiState(
    val mode: Mode = Mode.IDLE,
    val isPlaying: Boolean = false,
    val elapsedMs: Int = 0,
    val remainingMs: Int = 0,
    val recordTimerMs: Int = 0,
    val sendEnabled: Boolean = false,
    val selectedExampleName: String? = null,
    val error: String? = null
) {
    enum class Mode { IDLE, RECORDING, READY, UPLOADING, IDENTIFYING, ERROR }
}
