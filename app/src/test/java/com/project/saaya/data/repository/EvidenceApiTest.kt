package com.project.saaya.data.repository

import com.project.saaya.data.remote.*
import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

class EvidenceApiTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun endedEvidenceListAndExplicitDownloadUseCurrentSession() = runBlocking {
        val sessions = SessionManager(object : AuthSessionStore {
            override fun read(): AuthSession? = null
            override fun write(session: AuthSession) {}
            override fun clear() {}
        })
        fun login(id: String) = sessions.activate(AuthSession("token-$id", AuthenticatedUser(id, id, "$id@example.com"), 2000000000))
        login("owner")
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessions)).addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            val json = if (request.url.encodedPath.endsWith("download")) """{"downloadUrl":"https://b2.example/private"}"""
                       else """{"evidence":[],"canUpload":false,"nextBefore":null}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://narikavach-backend.onrender.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(EvidenceApiService::class.java)
        assertFalse(api.list("ended", "cursor").canUpload)
        assertEquals("Bearer token-owner", requests.single().header("Authorization"))
        assertEquals("cursor", requests.single().url.queryParameter("before"))
        assertEquals(1, requests.size) // Listing never downloads media.
        login("viewer")
        api.download("ended", "saved")
        assertEquals("Bearer token-viewer", requests.last().header("Authorization"))
        assertEquals("POST", requests.last().method)
        assertEquals("/incidents/ended/evidence/saved/download", requests.last().url.encodedPath)
        assertNull(requests.last().url.queryParameter("ownerId"))
    }
    @Test fun b2PutUsesExactLengthMimeMetadataAndProgressWithoutSessionCredentials() {
        val file = temp.newFile("evidence.jpg").apply { writeText("photo") }
        var progress = 0f
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Authorization"))
            assertEquals(5L, request.body!!.contentLength())
            assertEquals("image/jpeg", request.body!!.contentType().toString())
            assertEquals("id", request.header("x-amz-meta-evidence-id"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            assertEquals("photo", buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("".toResponseBody()).build()
        }.build()
        EvidenceTransfer(client).put("https://b2.example/private", mapOf("x-amz-meta-evidence-id" to "id"), file, "image/jpeg") { progress = it }
        assertEquals(1f, progress); assertTrue(file.exists()) // PUT success alone cannot delete evidence.
    }
    @Test fun expiredPutDoesNotDeleteFileOrBecomeSuccess() {
        val file = temp.newFile("evidence.mp4").apply { writeText("video") }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("expired")
                .body("".toResponseBody()).build()
        }.build()
        try { EvidenceTransfer(client).put("https://b2.example/expired", emptyMap(), file, "video/mp4") {}; fail() }
        catch (_: IOException) {}
        assertTrue(file.exists())
    }
    @Test fun signedUrlsAreRedactedFromDtoStrings() {
        val secret = "signed-url-test-fixture"
        assertFalse(EvidenceDownload(secret).toString().contains(secret))
        assertFalse(EvidenceAuthorization("id", false, secret, emptyMap(), null).toString().contains(secret))
        assertFalse(AnalysisGrant("id", false, secret, mapOf("secret" to secret)).toString().contains(secret))
    }
    @Test fun analysisRequiresExplicitConsentBodyAndUsesAuthenticatedIncidentEndpoint() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
            val body = com.google.gson.JsonParser.parseString(buffer.readUtf8()).asJsonObject
            assertTrue(body["consent"].asBoolean)
            assertFalse(body.has("ownerId")); assertFalse(body.has("token"))
            assertEquals("incident-notes", body["notes"].asString)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"id":"job","state":"QUEUED","report":null}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://narikavach-backend.onrender.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(EvidenceApiService::class.java)
        assertEquals("job", api.analysisCreate("incident", AnalysisCreate("request", listOf("asset"), "incident-notes")).id)
        assertEquals("/incidents/incident/analysis/jobs", requests.single().url.encodedPath)
    }
    @Test fun oversizedAnalysisDownloadIsRejectedAndPartialFileRemoved() {
        val target = temp.newFile("bounded-original.mp4")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("oversized".toResponseBody()).build()
        }.build()
        try { EvidenceTransfer(client).download("https://b2.example/private", target, 3); fail() }
        catch (_: IllegalStateException) { }
        assertFalse(target.exists())
    }
}
