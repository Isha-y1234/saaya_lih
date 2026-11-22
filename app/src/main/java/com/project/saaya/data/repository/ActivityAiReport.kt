package com.project.saaya.data.repository

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.project.saaya.data.remote.ActivityItem
import java.io.OutputStream
import java.util.Locale

internal fun activityCoordinates(latitude: Double?, longitude: Double?): String? =
    if (latitude != null && longitude != null)
        String.format(Locale.US, "Latitude %.6f · Longitude %.6f", latitude, longitude)
    else null

internal object ActivityAiReport {
    fun content(item: ActivityItem): List<ReportParagraph> = buildList {
        val job = requireNotNull(item.analysis)
        fun heading(text: String) { add(ReportParagraph(true, text)) }
        fun line(text: String) { add(ReportParagraph(false, text)) }
        heading("SAAYA Activity AI report")
        line("Evidence: ${item.kind.replaceFirstChar { it.titlecase() }}\nCaptured: ${item.capturedAt}\nReference: ${item.id}")
        activityCoordinates(item.latitude, item.longitude)?.let(::line)
        heading("User description")
        line(item.details.ifBlank { "No description provided." })
        if (item.details.isNotBlank()) line("Text synthesis source: note")
        heading("AI findings · unverified")
        line("Capture time is device-reported. Sampled video frames can miss events. Frame offsets are requested sampling positions.")
        job.reason?.let(::line)
        job.report?.let { report ->
            line("Interpretation provider: ${report.provider ?: "Cloudflare"}")
            report.evidenceReferences.orEmpty().takeIf { it.isNotEmpty() }?.let {
                line("Report references: ${it.joinToString()}")
            }
            listOf("Observations" to report.observations, "Interpretations" to report.interpretations,
                "Uncertainties" to report.uncertainties).forEach { (label, values) ->
                if (values.isNotEmpty()) {
                    heading(label)
                    values.forEach(::line)
                }
            }
            report.groundedReport?.let { grounded ->
                if (grounded.abstain) line("The report did not establish a safety event.")
                if (grounded.claims.isNotEmpty()) heading("Claim sources")
                grounded.claims.forEach { claim ->
                    val offset = claim.offsetMs?.let { "$it ms" } ?: "offset unavailable"
                    line("${claim.category} · ${claim.sourceId} · $offset\nSource quote: ${claim.quote}")
                }
            }
        }
        job.stages.withIndex().sortedBy { it.value.offsetMs }.forEach { (index, stage) ->
            heading(if (stage.kind == "audio") "Whisper transcription" else "Vision frame")
            line(listOfNotNull(stage.sourceTimestamp, stage.offsetMs?.let { "Source offset: $it ms" })
                .ifEmpty { listOf("Source time unavailable") }.joinToString(" · "))
            if (stage.status == "ok") line(String.format(Locale.US, "Text synthesis source: source-%02d", index + 1))
            stage.reason?.let(::line)
            stage.result?.let { result ->
                result.observations.orEmpty().forEach(::line)
                result.uncertainties.orEmpty().forEach { line("Uncertainty: $it") }
                result.text?.let(::line)
                result.timestampDisclosure?.let(::line)
                result.words.orEmpty().chunked(12).forEach { words ->
                    line(words.joinToString("  ") {
                        String.format(Locale.US, "%s [%.2f–%.2f s]", it.word, it.start, it.end)
                    })
                }
            }
        }
        if (item.kind == "video" && job.stages.none { it.kind == "audio" && it.status == "ok" }) {
            line("Video speech transcription was unavailable or omitted.")
        }
    }

    fun write(item: ActivityItem, output: OutputStream) {
        val document = PdfDocument()
        var number = 0
        fun page() = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
        var current = page()
        var y = 44f
        val paint = TextPaint().apply { color = Color.rgb(28, 35, 45) }
        try {
            for (paragraph in content(item)) {
                paint.typeface = if (paragraph.title) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.textSize = if (paragraph.title) 14f else 11f
                val layout = StaticLayout.Builder.obtain(paragraph.text, 0, paragraph.text.length, paint, 515)
                    .setLineSpacing(3f, 1f).build()
                for (line in 0 until layout.lineCount) {
                    val top = layout.getLineTop(line)
                    val height = layout.getLineBottom(line) - top
                    if (y + height > 795) {
                        document.finishPage(current)
                        current = page()
                        y = 44f
                    }
                    val canvas = current.canvas
                    canvas.save()
                    canvas.clipRect(40f, y, 555f, y + height)
                    canvas.translate(40f, y - top)
                    layout.draw(canvas)
                    canvas.restore()
                    y += height
                }
                y += 9
            }
        } catch (error: Exception) {
            document.finishPage(current)
            document.close()
            throw error
        }
        document.finishPage(current)
        try { document.writeTo(output) } finally { document.close() }
    }
}
