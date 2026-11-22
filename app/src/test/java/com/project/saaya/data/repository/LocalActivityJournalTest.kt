package com.project.saaya.data.repository

import com.google.gson.Gson
import com.project.saaya.data.remote.ActivityAnalysis
import com.project.saaya.data.remote.ActivityCapture
import com.project.saaya.data.remote.ActivityReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalActivityJournalTest {
    private val gson = Gson()
    private val note = ActivityCapture("test-id", "note", "2026-11-22T12:00:00Z", "A local note", 12.0, 34.0)

    @Test fun earlierJournalRemainsVisibleWithoutSync() {
        val restored = decodeActivityJournal(gson.toJson(note), gson)
        assertTrue(restored.queued)
        assertEquals(note.details, restored.item().details)
        assertEquals(note.requestId, restored.item().id)
    }

    @Test fun savedMetadataAndAnalysisRetryStateReopenTogether() {
        val entry = ActivityEvidenceRepository.Entry(note, true,
            ActivityAnalysis("PAUSED", emptyList(), emptyList(), "Offline", null))
        val restored = decodeActivityJournal(gson.toJson(entry), gson)
        assertEquals(entry, restored)
        assertEquals("Offline", restored.item().analysis?.reason)
    }

    @Test fun savedCheckpointReportRetainsItsSourcesWhenAnotherAttemptIsPaused() {
        val report = gson.fromJson(
            """{"observations":[],"interpretations":[],"uncertainties":["Time is approximate."],
                "provider":"Adaption-trained SAAYA","evidenceReferences":["source-01"],
                "groundedReport":{"abstain":true,"claims":[{"category":"reported","text":"Synthetic statement",
                 "sourceId":"source-01","quote":"Synthetic statement","offsetMs":2400}]}}""",
            ActivityReport::class.java
        )
        val entry = ActivityEvidenceRepository.Entry(note, true,
            ActivityAnalysis("PAUSED", emptyList(), emptyList(), "Synthesis is busy.", report))
        val restored = decodeActivityJournal(gson.toJson(entry), gson)
        val text = ActivityAiReport.content(restored.item()).joinToString("\n") { it.text }
        assertTrue(text.contains("Adaption-trained SAAYA"))
        assertTrue(text.contains("source-01 · 2400 ms"))
        assertTrue(text.contains("Source quote: Synthetic statement"))
        assertTrue(text.contains("Time is approximate."))
    }
}
