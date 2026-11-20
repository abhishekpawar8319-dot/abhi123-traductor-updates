package com.abhi.vozconversa.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Turns saved translations into a readable text file or a spreadsheet-friendly CSV. */
object TranscriptExporter {
    fun toText(records: List<TranslationRecord>, zone: TimeZone = TimeZone.getDefault()): String {
        val day = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).apply { timeZone = zone }
        val time = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).apply { timeZone = zone }
        val sb = StringBuilder("Abhishek – Español ⇄ English\n")
        var lastDay = ""
        for (r in records.sortedBy { it.timeMillis }) {
            val d = day.format(Date(r.timeMillis))
            if (d != lastDay) {
                sb.append("\n=== ").append(d).append(" ===\n")
                lastDay = d
            }
            sb.append('[').append(time.format(Date(r.timeMillis))).append("] ")
                .append(r.source.short).append(" → ").append(r.target.short).append('\n')
                .append("  ").append(r.original).append('\n')
                .append("  ").append(r.translated.ifBlank { "(not translated)" }).append('\n')
        }
        return sb.toString()
    }

    fun toCsv(records: List<TranslationRecord>, zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).apply { timeZone = zone }
        val sb = StringBuilder("﻿time,from,to,original,translation\n")
        for (r in records.sortedBy { it.timeMillis }) {
            sb.append(fmt.format(Date(r.timeMillis))).append(',')
                .append(r.source.short).append(',').append(r.target.short).append(',')
                .append(csv(r.original)).append(',').append(csv(r.translated)).append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
