package com.project.saaya.data.repository

import com.project.saaya.data.remote.*
import org.junit.Assert.*
import org.junit.Test

class ReportContentTest {
    private fun report()=IncidentReport("report","2026-11-06T10:00:00Z",ReportIncident("incident","ENDED","2026-11-06T08:00:00Z","2026-11-06T09:00:00Z","UTC"),
        listOf(ReportLine("2026-11-06T09:00:00Z","Participant statement","Later","m2"),
            ReportLine(null,"AI uncertainty","Time unavailable","a"),ReportLine("2026-11-06T08:00:00Z","AI observation (unverified)","Earlier","m1")),
        emptyList(),"User note",listOf("Audio unavailable","Video unavailable"),listOf("Not an official police report","Saaya cannot revoke exported copies"))
    @Test fun previewAndPdfSourceChronologyKeepUntimedEntriesSeparate() {
        val paragraphs=ReportContent.paragraphs(report()).map{it.text}
        assertTrue(paragraphs.indexOfFirst{it.contains("Earlier")}<paragraphs.indexOfFirst{it.contains("Later")})
        assertTrue(paragraphs.indexOfFirst{it.contains("Later")}<paragraphs.indexOfFirst{it.contains("Time unavailable\n")})
    }
    @Test fun reportContainsMetadataSourceLabelsAndUnavailableDisclosures() {
        val body=ReportContent.paragraphs(report()).joinToString("\n"){it.text}
        for(required in listOf("incident","ENDED","UTC","2026-11-06T10:00:00Z","User-provided","AI observation (unverified)","Audio unavailable","Video unavailable","cannot revoke","No confirmed original evidence"))
            assertTrue("Missing $required",body.contains(required))
    }
    @Test fun reportDtoToStringNeverIncludesSensitiveContents() {
        assertFalse(report().toString().contains("User note"))
    }
}
