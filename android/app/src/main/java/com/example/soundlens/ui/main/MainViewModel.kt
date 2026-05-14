package com.example.soundlens.ui.main

import android.app.Application
import android.content.res.Resources
import androidx.annotation.RawRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.soundlens.R
import com.example.soundlens.audio.AudioPlayer
import com.example.soundlens.audio.WavRecorder
import com.example.soundlens.aws.AwsConfig
import com.example.soundlens.aws.S3Uploader
import com.example.soundlens.data.IdentifyRepository
import com.example.soundlens.parsing.IdentifyParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableLiveData(MainUiState())
    val state: LiveData<MainUiState> = _state

    private val _goToResult = MutableLiveData<ResultNav?>()
    val goToResult: LiveData<ResultNav?> = _goToResult

    // --- Internals ---
    private var recorder: WavRecorder? = null
    private var recordedFile: File? = null
    private val player = AudioPlayer()

    private var tickerJob: Job? = null
    private var recordStartMs: Long = 0L

    private var selectedResId: Int? = null
    private var mediaPrepared = false

    fun loadExamples(resources: Resources): List<String> =
        resources.getStringArray(R.array.demo_audio_entries).toList()

    fun onExamplePicked(label: String, @RawRes resId: Int) {
        selectedResId = resId
        viewModelScope.launch(Dispatchers.IO) {
            prepareFromRes(resId)
            _state.postValue(
                _state.value!!.copy(
                    mode = MainUiState.Mode.READY,
                    sendEnabled = true,
                    selectedExampleName = label,
                    error = null
                )
            )
        }
    }

    // ---------- Recording ----------
    fun startRecording() {
        stopPlayback()            // asegúrate de que no quede player vivo
        clearAll()                // limpia estado anterior

        val ctx = getApplication<Application>()
        val f = File.createTempFile("soundlens_", ".wav", ctx.cacheDir)
        recordedFile = f

        recorder = WavRecorder(f).also { it.startRecording() }
        recordStartMs = System.currentTimeMillis()
        startRecordTicker()

        _state.value = _state.value!!.copy(
            mode = MainUiState.Mode.RECORDING,
            sendEnabled = false,
            error = null
        )
    }

    fun stopRecording() {
        // Detén y ESPERA a que cierre header WAV
        runCatching { recorder?.stopAndWait() }
        recorder = null
        stopRecordTicker()

        viewModelScope.launch(Dispatchers.IO) {
            val file = recordedFile
            if (file == null || !file.exists() || file.length() <= 44) {
                _state.postValue(
                    _state.value!!.copy(
                        mode = MainUiState.Mode.ERROR,
                        sendEnabled = true,
                        error = "Archivo de audio inválido"
                    )
                )
                return@launch
            }
            prepareFromFile(file)
            _state.postValue(
                _state.value!!.copy(
                    mode = MainUiState.Mode.READY,
                    sendEnabled = true,
                    selectedExampleName = null,
                    error = null
                )
            )
        }
    }

    fun clearAll() {
        stopPlayback()
        stopRecordTicker()
        runCatching { recorder?.stopAndWait() }
        recorder = null
        runCatching { recordedFile?.delete() }
        recordedFile = null
        selectedResId = null
        mediaPrepared = false
        _state.value = MainUiState()
    }

    // ---------- Playback ----------
    fun togglePlay() {
        if (!mediaPrepared) return
        if (player.isPlaying()) {
            player.pause()
            stopSeekTicker()
            _state.value = _state.value!!.copy(isPlaying = false)
        } else {
            player.start()
            startSeekTicker()
            _state.value = _state.value!!.copy(isPlaying = true)
        }
    }

    /** ÚNICA versión de stopPlayback (thread-safe) */
    private fun stopPlayback() {
        player.release()
        stopSeekTicker()
        // puede llamarse desde IO → usa postValue
        _state.postValue(
            _state.value!!.copy(
                isPlaying = false,
                elapsedMs = 0,
                remainingMs = 0
            )
        )
        mediaPrepared = false
    }

    // ---------- Send (upload + identify) ----------
    fun sendToIdentify() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                _state.value = _state.value!!.copy(
                    mode = MainUiState.Mode.UPLOADING,
                    sendEnabled = false,
                    error = null
                )

                val fileToUpload: File = when {
                    recordedFile != null -> recordedFile!!
                    selectedResId != null -> withContext(Dispatchers.IO) {
                        val tmp = File.createTempFile("demo_", ".wav", ctx.cacheDir)
                        ctx.resources.openRawResource(selectedResId!!).use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        tmp
                    }
                    else -> {
                        _state.value = _state.value!!.copy(
                            mode = MainUiState.Mode.READY,
                            sendEnabled = true,
                            error = "Selecciona o graba un audio primero"
                        )
                        return@launch
                    }
                }

                val s3Key = withContext(Dispatchers.IO) {
                    S3Uploader.uploadAudio(ctx, fileToUpload)
                }

                _state.value = _state.value!!.copy(mode = MainUiState.Mode.IDENTIFYING)

                val requestId = "run_${UUID.randomUUID()}"
                val payloadJson = IdentifyRepository.identifyFromS3(
                    context = ctx,
                    functionName = "shazam-indexer",
                    bucket = AwsConfig.BUCKET,
                    key = s3Key,
                    requestId = requestId
                )

                val resp = IdentifyParser.parseOrNull(payloadJson)
                val top0 = resp?.top_matches?.firstOrNull()
                val resolvedGenre = (resp?.genre?.takeIf { it.isNotBlank() })
                    ?: resp?.predictedGenre
                    ?: top0?.genre
                    ?: ""

                _goToResult.value = ResultNav(
                    payloadJson,
                    resp?.title ?: top0?.title ?: "—",
                    resp?.artist ?: top0?.artist ?: "—",
                    (resp?.year ?: top0?.year) ?: -1,
                    resolvedGenre,
                    resp?.offset_frames ?: (top0?.offset_frames ?: -1),
                    resp?.totalMatches ?: -1,
                    resp?.bestMatches ?: -1,
                    resp?.confidence ?: (top0?.confidence ?: 0.0),
                    resp?.request_id ?: requestId
                )

                _state.value = _state.value!!.copy(
                    mode = MainUiState.Mode.READY,
                    sendEnabled = true,
                    error = null
                )
            } catch (e: Exception) {
                _state.value = _state.value!!.copy(
                    mode = MainUiState.Mode.ERROR,
                    sendEnabled = true,
                    error = "${e::class.java.simpleName}: ${e.message}"
                )
            }
        }
    }

    // ---------- Internals ----------
    private fun prepareFromFile(file: File) {
        stopPlayback()
        player.setOnPrepared {
            mediaPrepared = true
            _state.postValue(
                _state.value!!.copy(
                    isPlaying = true,
                    elapsedMs = 0,
                    remainingMs = it.duration.coerceAtLeast(0)
                )
            )
            player.start()
            startSeekTicker()
        }
        runCatching { player.prepare(file.absolutePath) }
            .onFailure { e ->
                _state.postValue(
                    _state.value!!.copy(
                        mode = MainUiState.Mode.ERROR,
                        sendEnabled = true,
                        error = "Audio inválido: ${e.message}"
                    )
                )
            }
    }

    private fun prepareFromRes(@RawRes resId: Int) {
        val ctx = getApplication<Application>()
        stopPlayback()
        val tmp = File.createTempFile("demo_", ".wav", ctx.cacheDir)
        runCatching {
            ctx.resources.openRawResource(resId).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { e ->
            _state.postValue(
                _state.value!!.copy(
                    mode = MainUiState.Mode.ERROR,
                    sendEnabled = true,
                    error = "No se pudo cargar demo: ${e.message}"
                )
            )
            return
        }
        prepareFromFile(tmp)
    }

    private fun startSeekTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                val dur = player.duration().coerceAtLeast(0)
                val pos = player.currentPosition().coerceAtLeast(0)
                _state.value = _state.value!!.copy(
                    elapsedMs = pos,
                    remainingMs = (dur - pos).coerceAtLeast(0),
                    isPlaying = player.isPlaying()
                )
                delay(500)
            }
        }
    }

    private fun stopSeekTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun startRecordTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                val elapsed = (System.currentTimeMillis() - recordStartMs).toInt()
                _state.value = _state.value!!.copy(recordTimerMs = elapsed)
                delay(200)
            }
        }
    }

    private fun stopRecordTicker() = stopSeekTicker()

    fun consumeGoToResult() { _goToResult.value = null }
}

data class ResultNav(
    val payloadJson: String,
    val title: String,
    val artist: String,
    val year: Int,
    val genre: String,
    val offsetFrames: Int,
    val matchesForSong: Int,
    val matchesAtBestOffset: Int,
    val confidence: Double,
    val requestId: String
)
