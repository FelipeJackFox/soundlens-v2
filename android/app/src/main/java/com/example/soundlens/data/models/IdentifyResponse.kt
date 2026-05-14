package com.example.soundlens.data.models

/**
 * Modelo unificado para parsear tanto el JSON “viejo” como el nuevo de la Lambda.
 * Compat:
 *  - Si matches_* no vienen en la raíz, se toman de top_matches[0].
 *  - Se mantienen campos de YouTube por compatibilidad aunque ya no se usen en UI.
 */
data class IdentifyResponse(
    val ok: Boolean? = null,
    val request_id: String? = null,

    // Info básica del match
    val song_id: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val path: String? = null,

    // Coincidencia
    val offset_frames: Int? = null,
    val confidence: Double? = null,
    val clip_hashes: Int? = null,

    // Audio / S3
    val s3_url: String? = null,
    val s3_key: String? = null,

    // YouTube (compat)
    val youtube_id: String? = null,
    val youtube_url: String? = null,
    val youtube_url_highlight: String? = null,

    // Highlight
    val highlight_sec: Int? = null,

    // Compat JSON viejo (podían venir en raíz)
    val matches_at_best_offset: Int? = null,
    val matches_for_song: Int? = null,

    // ---- NUEVO JSON ----
    val audio_analysis: AudioAnalysis? = null,
    val input: IdentifyInput? = null,

    // Compatibilidad hacia atrás
    val calculated_features: Features? = null,
    val ideal_features: Features? = null,
    val genre_distances: Map<String, Double>? = null,
    val top_matches: List<TopMatch>? = null,

    // Datos del query que a veces devuelve el back
    val s3_bucket_query: String? = null,
    val s3_key_query: String? = null,
    val s3_key_target: String? = null,
) {
    /** Si no vienen en raíz, usa top_matches[0] como fallback. */
    val bestMatches: Int?
        get() = matches_at_best_offset ?: top_matches?.firstOrNull()?.matches_at_best_offset

    val totalMatches: Int?
        get() = matches_for_song ?: top_matches?.firstOrNull()?.matches_for_song

    val clipFeatures: Features?
        get() = calculated_features
            ?: audio_analysis?.calculated_features
            ?: audio_analysis?.features_norm
            ?: audio_analysis?.features_raw

    val idealFeatures: Features?
        get() = ideal_features ?: audio_analysis?.ideal_features

    val genreDistances: Map<String, Double>?
        get() = genre_distances
            ?: audio_analysis?.distances
            ?: audio_analysis?.classification?.scores

    val predictedGenre: String?
        get() = audio_analysis?.predicted_genre
            ?: audio_analysis?.classification?.best_genre_label
            ?: audio_analysis?.classification?.best_genre_key
            ?: genre
}

data class Features(
    val rms: Double? = null,
    val zcr: Double? = null,
    val sc_hz: Double? = null,
    val spec_entropy: Double? = null,
    val spec_kurtosis: Double? = null,
    val plef: Double? = null
)

data class AudioAnalysis(
    val predicted_genre: String? = null,
    val calculated_features: Features? = null,
    val ideal_features: Features? = null,
    val distances: Map<String, Double>? = null,
    val tempo: Tempo? = null,
    val features_raw: Features? = null,
    val features_norm: Features? = null,
    val classification: Classification? = null
)

data class Tempo(
    val bpm: Double? = null,
    val bpm_raw: Double? = null,
    val confidence: Double? = null,
    val frame_rate_hz: Double? = null
)

data class Classification(
    val best_genre_key: String? = null,
    val best_genre_label: String? = null,
    val scores: Map<String, Double>? = null,
    val candidates_after_tempo: List<String>? = null
)

data class IdentifyInput(
    val bucket: String? = null,
    val key: String? = null
)

data class TopMatch(
    val song_id: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val path: String? = null,
    val s3_key: String? = null,
    val s3_url: String? = null,
    val youtube_url: String? = null,
    val youtube_id: String? = null,
    val youtube_url_highlight: String? = null,
    val highlight_sec: Int? = null,
    val offset_frames: Int? = null,
    val matches_at_best_offset: Int? = null,
    val matches_for_song: Int? = null,
    val confidence: Double? = null
)
