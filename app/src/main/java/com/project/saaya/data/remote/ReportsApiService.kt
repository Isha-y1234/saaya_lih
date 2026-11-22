package com.project.saaya.data.remote

import retrofit2.http.*

data class ReportOptions(val includeChat: Boolean, val includeAudio: Boolean, val notes: String)
data class ReportIncident(val id: String, val status: String, val startedAt: String?, val endedAt: String?, val timezone: String)
data class ReportLine(val timestamp: String?, val category: String, val text: String, val reference: String?)
data class ReportEvidence(val id: String, val mediaType: String?, val fileSize: Long?, val capturedAt: String?, val uploadedAt: String?)
data class IncidentReport(val id: String, val generatedAt: String, val incident: ReportIncident,
    val timeline: List<ReportLine>, val evidence: List<ReportEvidence>, val notes: String,
    val disclosures: List<String>, val limitations: List<String>) {
    override fun toString() = "IncidentReport(id=$id)"
}
data class ReportSummary(val id: String, val generatedAt: String)
data class ReportList(val reports: List<ReportSummary>)
interface ReportsApiService {
    @POST("incidents/{id}/reports") suspend fun create(@Path("id") id: String, @Body options: ReportOptions): IncidentReport
    @GET("incidents/{id}/reports") suspend fun list(@Path("id") id: String): ReportList
    @GET("incidents/{id}/reports/{report}") suspend fun read(@Path("id") id: String, @Path("report") report: String): IncidentReport
}
