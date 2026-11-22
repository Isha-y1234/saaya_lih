package com.project.saaya.data.remote

import retrofit2.http.*

data class EvidenceUpload(val requestId: String, val mediaType: String, val fileSize: Long, val capturedAt: String)
data class EvidenceItem(val id: String, val incidentId: String, val ownerId: String, val mediaType: String,
                        val fileSize: Long, val uploadedAt: String?, val capturedAt: String?)
data class EvidenceAuthorization(val evidenceId: String, val complete: Boolean, val uploadUrl: String?,
                                 val headers: Map<String, String>?, val evidence: EvidenceItem?) {
    override fun toString() = "EvidenceAuthorization(evidenceId=$evidenceId, complete=$complete)"
}
data class EvidencePage(val evidence: List<EvidenceItem>, val canUpload: Boolean, val nextBefore: String?)
data class EvidenceDownload(val downloadUrl: String) {
    override fun toString() = "EvidenceDownload(redacted)"
}

interface EvidenceApiService {
    @GET("incidents/{id}/analysis/config")
    suspend fun analysisConfig(@Path("id") id: String): AnalysisConfig
    @POST("incidents/{id}/analysis/assets")
    suspend fun analysisUpload(@Path("id") id: String, @Body body: AnalysisAssetUpload): AnalysisGrant
    @POST("incidents/{id}/analysis/assets/{asset}/complete")
    suspend fun analysisComplete(@Path("id") id: String, @Path("asset") asset: String): AnalysisGrant
    @POST("incidents/{id}/analysis/jobs")
    suspend fun analysisCreate(@Path("id") id: String, @Body body: AnalysisCreate): AnalysisJob
    @GET("incidents/{id}/analysis/jobs")
    suspend fun analysisJobs(@Path("id") id: String): AnalysisJobs
    @POST("incidents/{id}/analysis/jobs/{job}/advance")
    suspend fun analysisAdvance(@Path("id") id: String, @Path("job") job: String): AnalysisJob
    @POST("incidents/{id}/evidence/uploads")
    suspend fun authorize(@Path("id") id: String, @Body body: EvidenceUpload): EvidenceAuthorization
    @POST("incidents/{id}/evidence/{evidence}/complete")
    suspend fun complete(@Path("id") id: String, @Path("evidence") evidence: String): EvidenceItem
    @GET("incidents/{id}/evidence")
    suspend fun list(@Path("id") id: String, @Query("before") before: String? = null): EvidencePage
    @POST("incidents/{id}/evidence/{evidence}/download")
    suspend fun download(@Path("id") id: String, @Path("evidence") evidence: String): EvidenceDownload
}

data class AnalysisConfig(val maxFrames: Int, val maxEdge: Int, val maxDurationMs: Long, val audioEnabled: Boolean, val verificationAllowed: Boolean = false)
data class AnalysisAssetUpload(val requestId: String, val evidenceId: String?, val kind: String,
    val fileSize: Long, val offsetMs: Long, val sha256: String, val consent: Boolean = true,
    val verificationOnly: Boolean = false, val capturedAt: String? = null)
data class AnalysisGrant(val assetId: String, val complete: Boolean, val uploadUrl: String?, val headers: Map<String, String>?) {
    override fun toString() = "AnalysisGrant(redacted)"
}
data class AnalysisCreate(val requestId: String, val assetIds: List<String>, val notes: String, val consent: Boolean = true, val verificationOnly: Boolean = false)
data class AnalysisJob(val id: String, val state: String, val reason: String?, val report: com.google.gson.JsonElement?) {
    override fun toString() = "AnalysisJob(id=$id, state=$state)"
}
data class AnalysisJobs(val jobs: List<AnalysisJob>)
