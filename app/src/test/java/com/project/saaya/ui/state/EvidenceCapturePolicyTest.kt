package com.project.saaya.ui.state

import org.junit.Assert.*
import org.junit.Test

class EvidenceCapturePolicyTest {
    @Test fun photosRequestCameraWithoutMicrophone() {
        assertArrayEquals(arrayOf("android.permission.CAMERA"), evidencePermissions(false))
    }
    @Test fun videoWithAudioRequestsBothPermissions() {
        assertEquals(setOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), evidencePermissions(true).toSet())
    }
    @Test fun boundedLocalCapturePreservesExistingEvidenceAtLimit() {
        assertTrue(evidenceCaptureHasSpace(0))
        assertTrue(evidenceCaptureHasSpace(200L * 1024 * 1024 - 1))
        assertFalse(evidenceCaptureHasSpace(200L * 1024 * 1024))
    }
}
