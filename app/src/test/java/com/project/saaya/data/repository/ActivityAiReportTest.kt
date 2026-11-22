package com.project.saaya.data.repository

import com.project.saaya.data.remote.ActivityAnalysis
import com.project.saaya.data.remote.ActivityItem
import com.project.saaya.data.remote.ActivityStage
import com.project.saaya.data.remote.ActivityStageResult
import com.project.saaya.data.remote.ActivityWord
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAiReportTest {
    @Test fun reportSeparatesDescriptionFromTimestampedCloudflareTranscript() {
        val source = "2026-11-22T12:00:00+00:00"
        val stage = ActivityStage("audio", "ok", null, 0, source,
            ActivityStageResult(null, null, "Test transcript", listOf(ActivityWord("Test", 0.1, 0.3)),
                "Provider timestamps"))
        val item = ActivityItem("record-id", "request-id", "audio", source, "User statement",
            25.0, 76.0, 100, "audio/wav", ActivityAnalysis("COMPLETE", listOf("asset"), listOf(stage), null, null))
        val output = ActivityAiReport.content(item).joinToString("\n") { it.text }
        assertTrue(output.contains("Latitude 25.000000 · Longitude 76.000000"))
        assertTrue(output.contains("User description\nUser statement"))
        assertTrue(output.contains("Whisper transcription\n$source · Source offset: 0 ms"))
        assertTrue(output.contains("Test transcript"))
        assertTrue(output.contains("Test [0.10–0.30 s]"))
    }
}
