package com.example.soundlens.ui.main

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.soundlens.R
import com.example.soundlens.audio.WavRecorder
import com.example.soundlens.aws.AwsConfig
import com.example.soundlens.aws.LambdaInvoker
import com.example.soundlens.aws.S3Uploader
import com.example.soundlens.data.models.IdentifyResponse
import com.example.soundlens.databinding.ActivityMainBinding
import com.example.soundlens.ui.result.ResultActivity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_TIMEOUT_RETRIES = 3
    }

    private lateinit var binding: ActivityMainBinding

    // Grabación WAV
    private var wavRecorder: WavRecorder? = null
    private var recordedFile: File? = null

    // Reproducción
    private var mediaPlayer: MediaPlayer? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false

    // Demo elegido (raw)
    private var selectedResId: Int? = null

    // Estados de UI
    private enum class UiState { IDLE, RECORDING, READY }
    private var uiState: UiState = UiState.IDLE

    // Timer de grabación
    private var recordStartMs = 0L
    private val recordTimerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordStartMs).toInt()
            binding.txtRecordTimer.text = fmtMs(elapsed)
            uiHandler.postDelayed(this, 200)
        }
    }

    // Animación de pulso
    private var pulseAnimator: AnimatorSet? = null
    private fun startPulse() {
        val upCircleX = ObjectAnimator.ofFloat(binding.mainCircle, "scaleX", 1f, 1.08f)
        val upCircleY = ObjectAnimator.ofFloat(binding.mainCircle, "scaleY", 1f, 1.08f)
        val upLogoX   = ObjectAnimator.ofFloat(binding.imgMainIcon, "scaleX", 1f, 1.05f)
        val upLogoY   = ObjectAnimator.ofFloat(binding.imgMainIcon, "scaleY", 1f, 1.05f)

        val downCircleX = ObjectAnimator.ofFloat(binding.mainCircle, "scaleX", 1.08f, 1f)
        val downCircleY = ObjectAnimator.ofFloat(binding.mainCircle, "scaleY", 1.08f, 1f)
        val downLogoX   = ObjectAnimator.ofFloat(binding.imgMainIcon, "scaleX", 1.05f, 1f)
        val downLogoY   = ObjectAnimator.ofFloat(binding.imgMainIcon, "scaleY", 1.05f, 1f)

        val up = AnimatorSet().apply {
            playTogether(upCircleX, upCircleY, upLogoX, upLogoY)
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
        }
        val down = AnimatorSet().apply {
            playTogether(downCircleX, downCircleY, downLogoX, downLogoY)
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
        }

        pulseAnimator?.cancel()
        pulseAnimator = AnimatorSet().apply {
            playSequentially(up, down)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (this@apply == pulseAnimator) start()
                }
            })
            start()
        }
    }
    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.mainCircle.scaleX = 1f
        binding.mainCircle.scaleY = 1f
        binding.imgMainIcon.scaleX = 1f
        binding.imgMainIcon.scaleY = 1f
    }

    // Permiso de mic
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording()
            else Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }

    // Scope simple para tareas (similar a lifecycleScope.launchWhenStarted del mono)
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Warming up lambda")
            LambdaInvoker.warmup(this@MainActivity)
        }

        setupExamplesDropdown()
        setupMainButton()

        binding.btnTrash.setOnClickListener { clearRecording() }

        binding.btnSend.setOnClickListener {
            scope.launch {
                try {
                    binding.btnSend.isEnabled = false
                    binding.btnSend.text = "Uploading…"

                    val fileToUpload: File? = when {
                        recordedFile != null -> recordedFile
                        selectedResId != null -> {
                            val tmp = File.createTempFile("demo_", ".wav", cacheDir)
                            resources.openRawResource(selectedResId!!).use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output) }
                            }
                            tmp
                        }
                        else -> null
                    }

                    if (fileToUpload == null) {
                        Toast.makeText(this@MainActivity, "Record or select an audio first", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val baseRequestId = "run_${UUID.randomUUID()}"
                    val lambdaJson = identifyWithRetries(fileToUpload, baseRequestId)

                    val resp = Gson().fromJson(lambdaJson, IdentifyResponse::class.java)
                    if (resp.ok != true) {
                        Toast.makeText(this@MainActivity, lambdaJson, Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val i = Intent(this@MainActivity, ResultActivity::class.java).apply {
                        putExtra("payload_json", lambdaJson)
                        putExtra("title", resp.title ?: "—")
                        putExtra("artist", resp.artist ?: "—")
                        putExtra("year", resp.year ?: -1)
                        putExtra("genre", resp.genre ?: "")
                        putExtra("offset_frames", resp.offset_frames ?: -1)
                        putExtra("matches_for_song", resp.matches_for_song ?: -1)
                        putExtra("matches_at_best_offset", resp.matches_at_best_offset ?: -1)
                        putExtra("confidence", resp.confidence ?: 0.0)
                        putExtra("request_id", resp.request_id ?: baseRequestId)

                    }
                    startActivity(i)

                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e::class.java.simpleName} ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    binding.btnSend.isEnabled = true
                    binding.btnSend.text = getString(R.string.send)
                }
            }
        }

        renderUi()
    }

    /** Dropdown persistente de ejemplos */
    private fun setupExamplesDropdown() {
        val entries = resources.getStringArray(R.array.demo_audio_entries).toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)

        binding.dropExamples.setAdapter(adapter)
        binding.dropExamples.threshold = 0
        binding.dropExamples.setOnClickListener { binding.dropExamples.showDropDown() }
        binding.dropExamples.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) binding.dropExamples.showDropDown() }
        binding.dropExamples.setOnTouchListener { _, _ -> binding.dropExamples.showDropDown(); false }

        binding.dropExamples.setOnItemClickListener { _, _, pos, _ ->
            val resId = when (pos) {
                0 -> R.raw.clip_one
                1 -> R.raw.clip_two
                2 -> R.raw.clip_three
                3 -> R.raw.clip_four
                else -> null
            }
            resId?.let {
                selectedResId = it
                loadAudioFromRes(it)
                uiState = UiState.READY
                renderUi()
            }
        }
    }

    /** Botón principal con 3 estados */
    private fun setupMainButton() {
        binding.mainCircle.setOnClickListener {
            when (uiState) {
                UiState.IDLE -> checkPermissionAndRecord()
                UiState.RECORDING -> stopRecording()
                UiState.READY -> togglePlay()
            }
        }
    }

    private fun checkPermissionAndRecord() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (ok) startRecording() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Graba WAV + timer + pulso */
    private fun startRecording() {
        clearRecording()
        try {
            val f = File.createTempFile("soundlens_", ".wav", cacheDir)
            recordedFile = f
            wavRecorder = WavRecorder(f).also { it.startRecording() }
            selectedResId = null
            uiState = UiState.RECORDING

            // Timer ON
            recordStartMs = System.currentTimeMillis()
            binding.txtRecordTimer.text = "0:00"
            binding.txtRecordTimer.visibility = View.VISIBLE
            uiHandler.post(recordTimerRunnable)

            // Pulso ON
            startPulse()

            renderUi()
        } catch (e: Exception) {
            Toast.makeText(this, "Error recording: ${e.message}", Toast.LENGTH_LONG).show()
            stopRecording()
        }
    }

    private fun stopRecording() {
        // cierra header WAV correctamente (usa nuestra versión con stopAndWait si ya la pegaste)
        runCatching { wavRecorder?.stopAndWait() }.onFailure {
            // si tu WavRecorder no tiene stopAndWait, usa stopRecording() normal:
            runCatching { wavRecorder?.stopRecording() }
        }
        wavRecorder = null

        // Timer OFF
        uiHandler.removeCallbacks(recordTimerRunnable)
        binding.txtRecordTimer.visibility = View.GONE

        // Pulso OFF
        stopPulse()

        recordedFile?.let { loadAudioFromFile(it) }
        uiState = UiState.READY
        renderUi()
    }

    private fun clearRecording() {
        uiHandler.removeCallbacks(updateSeekRunnable)
        uiHandler.removeCallbacks(recordTimerRunnable)
        binding.txtRecordTimer.visibility = View.GONE
        stopPulse()

        mediaPlayer?.release()
        mediaPlayer = null
        runCatching { recordedFile?.delete() }
        recordedFile = null
        selectedResId = null
        isPlaying = false
        uiState = UiState.IDLE
        renderUi()
    }

    private fun loadAudioFromFile(file: File) {
        uiHandler.removeCallbacks(updateSeekRunnable)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener {
                this@MainActivity.isPlaying = false
                renderUi()
            }
        }
        binding.txtElapsed.text = "0:00"
        binding.txtRemaining.text = "-${fmtMs(mediaPlayer?.duration ?: 0)}"
    }

    private fun loadAudioFromRes(resId: Int) {
        uiHandler.removeCallbacks(updateSeekRunnable)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, resId).apply {
            setOnCompletionListener {
                this@MainActivity.isPlaying = false
                renderUi()
            }
        }
        binding.txtElapsed.text = "0:00"
        binding.txtRemaining.text = "-${fmtMs(mediaPlayer?.duration ?: 0)}"
    }

    private fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            isPlaying = false
            uiHandler.removeCallbacks(updateSeekRunnable)
        } else {
            mp.start()
            isPlaying = true
            uiHandler.post(updateSeekRunnable)
        }
        renderUi()
    }

    private val updateSeekRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                val dur = mp.duration.coerceAtLeast(0)
                val pos = mp.currentPosition.coerceAtLeast(0)
                binding.seek.max = dur
                binding.seek.progress = pos
                binding.txtElapsed.text = fmtMs(pos)
                binding.txtRemaining.text = "-${fmtMs((dur - pos).coerceAtLeast(0))}"
                uiHandler.postDelayed(this, 500)
            }
        }
    }

    private fun fmtMs(ms: Int): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    /** Ícono / controles visibles según estado, como en el monolito */
    private fun renderUi() {
        when (uiState) {
            UiState.IDLE -> {
                binding.imgMainIcon.setImageResource(R.drawable.soundlens_logo)
                binding.imgMainIcon.imageTintList = null
                binding.playerBar.visibility = View.GONE
                binding.btnTrash.visibility = View.GONE
            }
            UiState.RECORDING -> {
                binding.imgMainIcon.setImageResource(R.drawable.ic_stop)
                // asegúrate que se vea: tinte oscuro/verde
                binding.imgMainIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.sl_green)
                )
                binding.playerBar.visibility = View.GONE
                binding.btnTrash.visibility = View.GONE
            }
            UiState.READY -> {
                binding.imgMainIcon.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                binding.imgMainIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.sl_green)
                )
                binding.playerBar.visibility = View.VISIBLE
                binding.btnTrash.visibility = View.VISIBLE
            }
        }
    }

    private fun readAssetText(name: String): String =
        assets.open(name).use { input ->
            BufferedReader(InputStreamReader(input)).use { it.readText() }
        }

    private suspend fun identifyWithRetries(file: File, baseRequestId: String): String {
        var lastError: Exception? = null
        repeat(MAX_TIMEOUT_RETRIES) { attemptIndex ->
            val attempt = attemptIndex + 1
            try {
                Log.d(TAG, "Identify attempt $attempt/$MAX_TIMEOUT_RETRIES: uploading ${file.name}")
                val s3Key = withContext(Dispatchers.IO) {
                    S3Uploader.uploadAudio(this@MainActivity, file)
                }
                Log.d(TAG, "Identify attempt $attempt: uploaded to $s3Key")

                binding.btnSend.text = "Identifying… (try $attempt)"

                val lambdaJson = withContext(Dispatchers.IO) {
                    LambdaInvoker.identifyFromS3(
                        context = this@MainActivity,
                        functionName = "shazam-indexer",
                        bucket = AwsConfig.BUCKET,
                        key = s3Key,
                        requestId = "$baseRequestId-$attempt"
                    )
                }

                Log.d(TAG, "Identify attempt $attempt response: $lambdaJson")

                if (lambdaJson.contains("Task timed out", ignoreCase = true)) {
                    throw RuntimeException("Lambda timeout")
                }
                return lambdaJson
            } catch (e: Exception) {
                lastError = e
                val isTimeout = (e.message ?: "").contains("timeout", ignoreCase = true)
                Log.d(TAG, "Identify attempt $attempt failed: ${e.message}")
                if (!isTimeout || attempt >= MAX_TIMEOUT_RETRIES) {
                    throw e
                }
                binding.btnSend.text = "Retrying… (${attempt + 1}/${MAX_TIMEOUT_RETRIES})"
            }
        }
        throw lastError ?: RuntimeException("Unknown error")
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(updateSeekRunnable)
        uiHandler.removeCallbacks(recordTimerRunnable)
        stopPulse()
        mediaPlayer?.release()
        runCatching { wavRecorder?.stopAndWait() }.onFailure {
            runCatching { wavRecorder?.stopRecording() }
        }
    }
}
