package com.example.soundlens.ui.result

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import coil.load
import com.example.soundlens.R
import com.example.soundlens.databinding.ActivityResultBinding
import com.example.soundlens.data.models.Features
import com.example.soundlens.data.models.IdentifyResponse
import com.example.soundlens.uiutils.Formatter
import com.github.mikephil.charting.charts.RadarChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.RadarData
import com.github.mikephil.charting.data.RadarDataSet
import com.github.mikephil.charting.data.RadarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.chip.Chip

private data class RadarMetric(val label: String, val extractor: (Features?) -> Double?)

private val RADAR_METRICS = listOf(
    RadarMetric("Energía (RMS)") { it?.rms },
    RadarMetric("Ruido (ZCR)") { it?.zcr },
    RadarMetric("Brillo (SC)") { it?.sc_hz },
    RadarMetric("Entropía espectral") { it?.spec_entropy },
    RadarMetric("Picos (kurtosis)") { it?.spec_kurtosis },
    RadarMetric("Silencios/variación (PLEF)") { it?.plef }
)

private val RADAR_LABELS = RADAR_METRICS.map { it.label }.toTypedArray()
private const val RADAR_FILL_ALPHA_CLIP = 120
private const val RADAR_FILL_ALPHA_IDEAL = 70
private const val RADAR_LINE_WIDTH = 2f
private const val RADAR_WEB_WIDTH = 1.2f
private const val RADAR_WEB_INNER_WIDTH = 1.0f
private const val RADAR_Y_MAX = 1f
private const val RADAR_Y_MIN = 0f
private const val RADAR_Y_GRANULARITY = 0.25f
private const val RADAR_SHOW_VALUES = false

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private val vm: ResultViewModel by viewModels()
    private var lastRadarSignature: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val payloadJson = intent.getStringExtra("payload_json") ?: "{}"
        val title = intent.getStringExtra("title") ?: "—"
        val artist = intent.getStringExtra("artist") ?: "—"
        val year = intent.getIntExtra("year", -1)
        val genre = intent.getStringExtra("genre") ?: ""
        val offsetFrames = intent.getIntExtra("offset_frames", -1)

        vm.initWithPayload(payloadJson, title, artist, year, genre, offsetFrames)

        setupClicks()
        observeState()
    }

    private fun setupClicks() {
        binding.btnPlay.setOnClickListener { vm.togglePlay() }
        binding.btnForward.setOnClickListener { vm.seekBy(10_000) }
        binding.btnRewind.setOnClickListener { vm.seekBy(-10_000) }
        binding.btnJumpHighlight.setOnClickListener { vm.restartFromHighlight() }
        binding.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.txtElapsed.text = Formatter.fmtMs(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb ?: return
                vm.seekTo(sb.progress)
            }
        })
        binding.btnToggleDebug.setOnClickListener {
            val showing = binding.debugScroll.isVisible
            binding.debugScroll.visibility = if (showing) View.GONE else View.VISIBLE
            binding.btnToggleDebug.text = if (showing) "Debug log ▾" else "Debug log ▴"
        }
    }

    private fun observeState() {
        vm.state.observe(this) { s ->
            binding.txtSong.text = s.metaTitle
            binding.txtMeta.text = s.metaSubtitle
            binding.progress.visibility = if (s.loading && !s.audioReady) View.VISIBLE else View.GONE
            binding.imgCover.load(s.albumCoverUrl) {
                crossfade(true)
                placeholder(R.drawable.soundlens_logo)
                error(R.drawable.soundlens_logo)
                fallback(R.drawable.soundlens_logo)
            }

            val totalDuration = (s.durationMs.takeIf { it > 0 } ?: (s.elapsedMs + s.remainingMs)).coerceAtLeast(0)

            binding.playerControlsGroup.visibility = if (s.audioReady) View.VISIBLE else View.GONE
            binding.playerLoadingGroup.visibility = if (s.loading && !s.audioReady) View.VISIBLE else View.GONE

            binding.btnPlay.isEnabled = s.audioReady
            binding.btnForward.isEnabled = s.audioReady
            binding.btnRewind.isEnabled = s.audioReady
            binding.btnJumpHighlight.isEnabled = s.audioReady && vm.hasHighlight()
            binding.seek.isEnabled = s.audioReady

            binding.btnPlay.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            binding.seek.max = totalDuration.coerceAtLeast(1)
            binding.seek.progress = s.elapsedMs.coerceIn(0, binding.seek.max)
            binding.txtElapsed.text = Formatter.fmtMs(s.elapsedMs)
            binding.txtRemaining.text = "-${Formatter.fmtMs(s.remainingMs)}"
            binding.txtDuration.text = Formatter.fmtMs(totalDuration)

            binding.txtPlayerTitle.text = s.metaTitle
            binding.txtPlayerStatus.text = when {
                s.loading && !s.audioReady -> "Preparando preview..."
                s.isPlaying -> "Reproduciendo preview"
                s.audioReady -> "En pausa"
                else -> "Esperando audio..."
            }
            binding.txtPlayerHighlight.text = vm.formatOffset()
            binding.btnJumpHighlight.visibility = if (vm.hasHighlight()) View.VISIBLE else View.GONE

            binding.txtDebug.text = s.prettyJson

            updateStatsCard(s.response)
            updateGenreChips()

            if (s.showRadar) {
                renderRadarChart()
            } else {
                binding.radarChart.visibility = View.GONE
                lastRadarSignature = null
            }

            s.error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun updateStatsCard(response: IdentifyResponse?) {
        binding.txtStatBestOffset.text = response?.bestMatches?.toString() ?: "—"
        binding.txtStatTotalMatches.text = response?.totalMatches?.toString() ?: "—"
        binding.txtStatTotalHashes.text = response?.clip_hashes?.toString() ?: "—"
    }

    private fun updateGenreChips() {
        val genres = vm.topGenres()
        val visible = genres.isNotEmpty()
        binding.txtGenresTitle.visibility = if (visible) View.VISIBLE else View.GONE
        binding.cardGenres.visibility = if (visible) View.VISIBLE else View.GONE
        binding.chipGroupGenres.removeAllViews()
        if (!visible) return

        val icon = ContextCompat.getDrawable(this, R.drawable.ic_logo_notes)
        val textColor = ContextCompat.getColor(this, R.color.sl_text_primary)
        genres.forEach { genre ->
            val chip = Chip(this).apply {
                text = genre
                chipIcon = icon
                chipIconTint = ColorStateList.valueOf(textColor)
                chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(this@ResultActivity, genreColorFor(genre)))
                isClickable = false
                isCheckable = false
                isFocusable = false
                setTextColor(textColor)
                textSize = 14f
            }
            binding.chipGroupGenres.addView(chip)
        }
    }

    private fun genreColorFor(label: String): Int {
        val lower = label.lowercase()
        return when {
            lower.contains("pop") -> R.color.genre_chip_pink
            lower.contains("rock") || lower.contains("metal") -> R.color.genre_chip_purple
            lower.contains("hip") || lower.contains("rap") -> R.color.genre_chip_orange
            lower.contains("edm") || lower.contains("dance") || lower.contains("elect") -> R.color.genre_chip_blue
            lower.contains("latin") || lower.contains("reggaeton") -> R.color.genre_chip_teal
            else -> R.color.genre_chip_default
        }
    }

    private fun renderRadarChart() {
        val r = vm.state.value?.response ?: return
        val clip = r.clipFeatures
        val ideal = r.idealFeatures
        if (clip == null) {
            binding.radarChart.visibility = View.GONE
            lastRadarSignature = null
            return
        }

        val signature = RADAR_METRICS.flatMap { metric ->
            listOf(metric.extractor(clip), metric.extractor(ideal))
        }.joinToString("|")
        if (signature == lastRadarSignature && binding.radarChart.data != null) {
            binding.radarChart.visibility = View.VISIBLE
            return
        }
        lastRadarSignature = signature

        fun norm(value: Double?): Float {
            return (value ?: 0.0).coerceIn(0.0, 1.0).toFloat()
        }

        val clipEntries = RADAR_METRICS.map { metric ->
            RadarEntry(norm(metric.extractor(clip)))
        }
        val idealEntries = ideal?.let {
            RADAR_METRICS.map { metric ->
                RadarEntry(norm(metric.extractor(it)))
            }
        }

        val chart: RadarChart = binding.radarChart
        chart.visibility = View.VISIBLE

        val clipColor = ContextCompat.getColor(this, R.color.sl_green)
        val idealColor = ContextCompat.getColor(this, R.color.sl_text_secondary)
        val labelColor = ContextCompat.getColor(this, R.color.sl_text_secondary)

        val setClip = RadarDataSet(clipEntries, "Clip").apply {
            color = clipColor
            fillColor = clipColor
            setDrawFilled(true)
            fillAlpha = RADAR_FILL_ALPHA_CLIP
            lineWidth = RADAR_LINE_WIDTH
            setDrawValues(RADAR_SHOW_VALUES)
        }
        val setIdeal = idealEntries?.let { entries ->
            RadarDataSet(entries, "Ideal").apply {
                color = idealColor
                fillColor = idealColor
                setDrawFilled(true)
                fillAlpha = RADAR_FILL_ALPHA_IDEAL
                lineWidth = RADAR_LINE_WIDTH
                setDrawValues(RADAR_SHOW_VALUES)
            }
        }

        chart.data = RadarData(listOfNotNull(setClip, setIdeal))
        chart.description.isEnabled = false
        chart.isRotationEnabled = false
        chart.webLineWidth = RADAR_WEB_WIDTH
        chart.webLineWidthInner = RADAR_WEB_INNER_WIDTH
        chart.webColor = labelColor
        chart.webColorInner = labelColor

        chart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(RADAR_LABELS)
            textColor = labelColor
            textSize = 11f
            position = XAxis.XAxisPosition.TOP
        }
        chart.yAxis.apply {
            axisMinimum = RADAR_Y_MIN
            axisMaximum = RADAR_Y_MAX
            granularity = RADAR_Y_GRANULARITY
            setDrawLabels(false)
            textColor = labelColor
        }
        chart.legend.apply {
            isEnabled = true
            textColor = labelColor
            textSize = 11f
            verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
            horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            orientation = Legend.LegendOrientation.HORIZONTAL
            setDrawInside(true)
            formSize = 8f
            xEntrySpace = 6f
            yEntrySpace = 2f
            formToTextSpace = 3f
            yOffset = 4f
        }

        chart.setExtraOffsets(8f, 12f, 8f, 12f)
        chart.setMinOffset(4f)

        chart.animateXY(500, 500)
        chart.invalidate()
    }
}
