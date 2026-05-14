package com.example.soundlens.uiutils

object Formatter {
    fun fmtMs(ms: Int): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }
}
