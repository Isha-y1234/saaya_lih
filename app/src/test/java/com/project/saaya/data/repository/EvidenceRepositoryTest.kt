package com.project.saaya.data.repository

import com.project.saaya.data.remote.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.UUID

class EvidenceRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val timestamp = "2026-11-04T00:00:00.000Z"
    private fun file() = temp.newFile("${UUID.randomUUID()}.jpg").apply { writeText("test-photo") }
    private fun failure(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())))
    @Test fun captureStartMarkerSurvivesFailedUploadAndIsRemovedOnlyAfterConfirmation() = runBlocking {
        val api = Api(); val file = file()
        val timestampFile = File(file.parentFile, "${file.nameWithoutExtension}.capture-time").apply { writeText("1234") }
        val repository = EvidenceRepository(api) { _, _, _, _, _ -> throw IOException("fixture upload failed") }
        try { repository.upload("incident",file,timestamp) {};fail() } catch (_: IOException) {}
        assertTrue(file.exists());assertEquals("1234",timestampFile.readText())
        EvidenceRepository(api) { _, _, _, _, _ -> }.upload("incident",file,timestamp) {}
        assertFalse(timestampFile.exists())
    }
    private inner class Api : EvidenceApiService {
        override suspend fun analysisConfig(id: String): AnalysisConfig = error("unused")
        override suspend fun analysisUpload(id: String, body: AnalysisAssetUpload): AnalysisGrant = error("unused")
        override suspend fun analysisComplete(id: String, asset: String): AnalysisGrant = error("unused")
        override suspend fun analysisCreate(id: String, body: AnalysisCreate): AnalysisJob = error("unused")
        override suspend fun analysisJobs(id: String): AnalysisJobs = error("unused")
        override suspend fun analysisAdvance(id: String, job: String): AnalysisJob = error("unused")
        var body: EvidenceUpload? = null
        var authorizations = 0
        var confirmations = 0
        var completeError: Exception? = null
        var authorizeError: Exception? = null
        var serverComplete = false
        var wrongIncident = false
        override suspend fun authorize(id: String, body: EvidenceUpload): EvidenceAuthorization {
            authorizations++; this.body = body
            authorizeError?.let { throw it }
            return EvidenceAuthorization("evidence", serverComplete, "https://private.example/upload",
                mapOf("Content-Type" to body.mediaType, "x-amz-meta-evidence-id" to "evidence"), null)
        }
        override suspend fun complete(id: String, evidence: String): EvidenceItem {
            confirmations++
            completeError?.let { throw it }
            return EvidenceItem(evidence, if (wrongIncident) "other" else id, "owner", "image/jpeg", 10, timestamp, timestamp)
        }
        override suspend fun list(id: String, before: String?) = EvidencePage(emptyList(), false, null)
        override suspend fun download(id: String, evidence: String) = EvidenceDownload("https://private.example/download")
    }
    @Test fun successfulPutRequiresBackendConfirmationBeforeLocalDeletion() = runBlocking {
        val api = Api(); val file = file()
        val repository = EvidenceRepository(api) { _, headers, local, mime, progress ->
            assertTrue(local.exists()); assertEquals(0, api.confirmations)
            assertEquals("image/jpeg", mime); assertEquals("evidence", headers["x-amz-meta-evidence-id"])
            progress(1f)
        }
        val saved = repository.upload("incident", file, timestamp) {}
        assertEquals("incident", saved.incidentId)
        assertEquals(file.nameWithoutExtension, api.body!!.requestId)
        assertEquals(10L, api.body!!.fileSize)
        assertEquals(timestamp, api.body!!.capturedAt)
        assertFalse(file.exists()); assertFalse(File(file.path + ".server").exists())
    }
    @Test fun failedOrExpiredPutPreservesFileAndRetryUsesSameRequest() = runBlocking {
        val api = Api(); val file = file(); var puts = 0
        val repository = EvidenceRepository(api) { _, _, _, _, _ ->
            puts++; if (puts == 1) throw IOException("expired authorization")
        }
        try { repository.upload("incident", file, timestamp) {}; fail() } catch (_: IOException) {}
        assertTrue(file.exists()); assertEquals(0, api.confirmations)
        val first = api.body
        api.completeError = failure(409)
        try { repository.upload("incident", file, timestamp) {}; fail() } catch (_: HttpException) {}
        assertEquals(first, api.body); assertTrue(file.exists())
        api.completeError = null
        repository.upload("incident", file, timestamp) {}
        assertEquals(2, puts); assertFalse(file.exists())
    }
    @Test fun lostCompletionResponseRetriesConfirmationWithoutRepeatedPut() = runBlocking {
        val api = Api(); val file = file(); var puts = 0
        api.completeError = IOException("response lost")
        val repository = EvidenceRepository(api) { _, _, _, _, _ -> puts++ }
        try { repository.upload("incident", file, timestamp) {}; fail() } catch (_: IOException) {}
        assertTrue(file.exists())
        api.completeError = null
        repository.upload("incident", file, timestamp) {}
        assertEquals(1, api.authorizations); assertEquals(1, puts); assertEquals(2, api.confirmations)
        assertFalse(file.exists())
    }
    @Test fun unauthorizedCrossAccountInvalidMimeOversizeAndEndedNeverDeleteFiles() = runBlocking {
        for (code in listOf(401, 403, 404, 409, 410, 413, 422, 503)) {
            val api = Api(); val file = file(); api.authorizeError = failure(code)
            val repository = EvidenceRepository(api) { _, _, _, _, _ -> fail("No PUT allowed") }
            try { repository.upload("incident", file, timestamp) {}; fail() }
            catch (error: HttpException) { assertEquals(code, error.code()) }
            assertTrue(file.exists()); assertEquals(0, api.confirmations)
        }
    }
    @Test fun completionFailureCannotBecomeUploadSuccess() = runBlocking {
        for (code in listOf(401, 403, 404, 409, 410, 503)) {
            val api = Api(); val file = file(); api.completeError = failure(code)
            val repository = EvidenceRepository(api) { _, _, _, _, _ -> }
            try { repository.upload("incident", file, timestamp) {}; fail() } catch (_: HttpException) {}
            assertTrue(file.exists()); assertTrue(File(file.path + ".server").exists())
        }
    }
    @Test fun completedAuthorizationDoesNotRepeatPut() = runBlocking {
        val api = Api(); val file = file(); api.serverComplete = true
        EvidenceRepository(api) { _, _, _, _, _ -> fail("Completed evidence must not be overwritten") }
            .upload("incident", file, timestamp) {}
        assertFalse(file.exists()); assertEquals(1, api.confirmations)
    }
    @Test fun mismatchedCompletionDoesNotDeleteLocalFile() = runBlocking {
        val api = Api(); val file = file(); api.wrongIncident = true
        try { EvidenceRepository(api) { _, _, _, _, _ -> }.upload("incident", file, timestamp) {}; fail() }
        catch (_: IllegalStateException) {}
        assertTrue(file.exists())
    }
    @Test fun videoUsesMp4Authorization() = runBlocking {
        val api = Api(); val file = temp.newFile("${UUID.randomUUID()}.mp4").apply { writeText("video") }
        EvidenceRepository(api) { _, _, _, mime, _ -> assertEquals("video/mp4", mime) }.upload("incident", file, timestamp) {}
        assertEquals("video/mp4", api.body!!.mediaType)
    }
}
