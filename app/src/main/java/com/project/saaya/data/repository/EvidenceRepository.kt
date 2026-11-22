package com.project.saaya.data.repository

import com.project.saaya.data.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Separate client deliberately has no session interceptor, cookies, logging or redirects. */
class EvidenceTransfer(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.MINUTES).build()) {

    fun put(url: String, headers: Map<String, String>, file: File, mime: String, progress: (Float) -> Unit) {
        require(url.startsWith("https://"))
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count); total += count
                        progress(total.toFloat() / file.length().coerceAtLeast(1))
                    }
                }
            }
        }
        val builder = Request.Builder().url(url).put(body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use {
            if (!it.isSuccessful) throw IOException("Upload failed or authorization expired. Retry.")
        }
    }

    fun download(url: String, target: File, maxBytes: Long = Long.MAX_VALUE) {
        require(url.startsWith("https://"))
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download unavailable. Retry.")
                val body = response.body ?: throw IOException("Empty download")
                check(body.contentLength() <= maxBytes) { "Evidence size limit exceeded" }
                body.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= maxBytes) { "Evidence size limit exceeded" }
                        output.write(buffer, 0, count)
                    }
                } }
            }
        } catch (error: Exception) { target.delete(); throw error }
    }
}

/** Local deletion is strictly after a validated successful metadata response. */
class EvidenceRepository(private val api: EvidenceApiService,
                         private val put: (String, Map<String, String>, File, String, (Float) -> Unit) -> Unit = EvidenceTransfer()::put) {
    suspend fun upload(incidentId: String, file: File, capturedAt: String, progress: (Float) -> Unit): EvidenceItem =
        withContext(Dispatchers.IO) {
            require(file.extension in listOf("jpg", "mp4") && file.length() > 0) { "Invalid local evidence" }
            val mime = if (file.extension == "jpg") "image/jpeg" else "video/mp4"
            val marker = File(file.path + ".server")
            suspend fun confirm(evidenceId: String): EvidenceItem {
                val saved = api.complete(incidentId, evidenceId)
                check(saved.id == evidenceId && saved.incidentId == incidentId && saved.uploadedAt?.isNotBlank() == true)
                file.delete(); marker.delete()
                if (!file.exists()) File(file.parentFile, "${file.nameWithoutExtension}.capture-time").delete()
                return saved
            }
            // Resolve lost completion responses before attempting another PUT.
            if (marker.exists()) {
                try { return@withContext confirm(marker.readText()) }
                catch (error: HttpException) {
                    if (error.code() != 409) throw error
                }
            }
            val grant = api.authorize(incidentId, EvidenceUpload(file.nameWithoutExtension, mime, file.length(), capturedAt))
            if (!marker.exists()) {
                val temporary = File(marker.path + ".tmp")
                temporary.writeText(grant.evidenceId)
                check(temporary.renameTo(marker)) { "Could not persist upload identity" }
            }
            if (!grant.complete) {
                put(requireNotNull(grant.uploadUrl), requireNotNull(grant.headers), file, mime, progress)
            }
            confirm(grant.evidenceId)
        }
}
