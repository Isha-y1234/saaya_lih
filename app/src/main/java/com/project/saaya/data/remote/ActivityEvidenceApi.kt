package com.project.saaya.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

data class ActivityCapture(
    val requestId: String,
    val kind: String,
    val capturedAt: String,
    val details: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val fileSize: Long = 0
)

data class ActivityItem(
    val id: String,
    val requestId: String,
    val kind: String,
    val capturedAt: String,
    val details: String,
    val latitude: Double?,
    val longitude: Double?,
    val fileSize: Long,
    val mediaType: String?,
    val analysis: ActivityAnalysis?,
    val state: String? = null,
    val uploadedAt: String? = null
)

data class ActivityAnalysis(
    val state: String,
    val assetIds: List<String>,
    val stages: List<ActivityStage>,
    val reason: String?,
    val report: ActivityReport?,
    val attempt: Int = 0
)

data class ActivityStage(
    val kind: String,
    val status: String,
    val reason: String?,
    val offsetMs: Long?,
    val sourceTimestamp: String?,
    val result: ActivityStageResult?
)
data class ActivityStageResult(
    val observations: List<String>?,
    val uncertainties: List<String>?,
    val text: String?,
    val words: List<ActivityWord>?,
    val timestampDisclosure: String?
)
data class ActivityWord(val word: String, val start: Double, val end: Double)
data class ActivityGroundedClaim(val category: String, val text: String, val sourceId: String,
                                val quote: String, val offsetMs: Long?)
data class ActivityGroundedReport(val claims: List<ActivityGroundedClaim>, val abstain: Boolean)
data class ActivityReport(
    val observations: List<String>,
    val interpretations: List<String>,
    val uncertainties: List<String>,
    val provider: String? = null,
    val evidenceReferences: List<String>? = null,
    val groundedReport: ActivityGroundedReport? = null
)

data class ActivityPage(val items: List<ActivityItem>, val nextBefore: String?)
data class ActivityConfig(val audioEnabled: Boolean, val synthesisProvider: String? = null)
data class LocalActivityTask(val kind: String, val consent: Boolean, val media: String? = null, val context: String? = null,
    val synthesisProvider: String = "cloudflare", val synthesisConsent: String? = null)
data class LocalActivityResult(val status: String, val reason: String?, val result: com.google.gson.JsonObject?)

interface ActivityEvidenceApi {
    @POST("activity/evidence/local-analysis")
    suspend fun analyzeLocal(@Body task: LocalActivityTask): LocalActivityResult

    @GET("activity/evidence")
    suspend fun list(@Query("before") before: String? = null): ActivityPage

    @POST("activity/evidence/{id}/download")
    suspend fun download(@Path("id") id: String): EvidenceDownload

    @POST("activity/evidence/{id}/archive")
    suspend fun archive(@Path("id") id: String): Map<String, Boolean>

    @GET("activity/evidence/analysis-config")
    suspend fun config(): ActivityConfig

}
