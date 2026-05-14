package com.example.soundlens.parsing

import com.example.soundlens.data.models.IdentifyResponse
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

object IdentifyParser {

    private val gson: Gson = Gson()

    fun parseOrNull(json: String?): IdentifyResponse? =
        try { gson.fromJson(json, IdentifyResponse::class.java) } catch (_: Exception) { null }

    fun pretty(raw: String?): String {
        if (raw.isNullOrBlank()) return "(json vacío)"
        return try {
            val el = JsonParser.parseString(raw)
            GsonBuilder().setPrettyPrinting().create().toJson(el)
        } catch (_: Exception) {
            raw
        }
    }
}
