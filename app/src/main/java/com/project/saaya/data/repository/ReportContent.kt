package com.project.saaya.data.repository

import com.project.saaya.data.remote.IncidentReport

data class ReportParagraph(val title: Boolean, val text: String)
/** Same reviewed content feeds preview and Android PDF; no storage URLs or credentials. */
object ReportContent {
    fun paragraphs(report: IncidentReport): List<ReportParagraph> = buildList {
        fun heading(s: String) { add(ReportParagraph(true,s)) }
        fun line(s: String) { add(ReportParagraph(false,s)) }
        heading("Saaya · Incident summary")
        line("Incident ID: ${report.incident.id}\nStatus: ${report.incident.status}\nReport ID: ${report.id}")
        line("Created: ${report.generatedAt}\nStarted: ${report.incident.startedAt ?: "Unavailable"}\nEnded: ${report.incident.endedAt ?: "Not ended / unavailable"}\nTimezone: ${report.incident.timezone}")
        report.limitations.forEach(::line)
        heading("User-provided notes · unverified")
        line(report.notes.ifBlank { "No additional report notes supplied." })
        heading("Chronological timeline")
        report.timeline.sortedWith(compareBy({it.timestamp == null},{it.timestamp ?: ""})).forEach {
            line("${it.timestamp ?: "Time unavailable / recording-relative offset"} | ${it.category}\n${it.text}\nReference: ${it.reference ?: "Unavailable"}")
        }
        if(report.timeline.isEmpty()) line("No timeline data available.")
        heading("Original evidence references")
        report.evidence.forEach {
            line("ID: ${it.id}\nType: ${it.mediaType ?: "Unavailable"} | Bytes: ${it.fileSize ?: "Unavailable"}\nCaptured: ${it.capturedAt ?: "Unavailable"}\nUploaded: ${it.uploadedAt ?: "Unavailable"}")
        }
        if(report.evidence.isEmpty()) line("No confirmed original evidence available.")
        heading("Missing data and processing disclosures")
        report.disclosures.forEach(::line)
    }
}
