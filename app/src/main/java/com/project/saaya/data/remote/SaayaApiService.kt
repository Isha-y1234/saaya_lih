package com.project.saaya.data.remote

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Trusted Circle contact CRUD and account invitation endpoints. */
interface SaayaApiService {
    @GET("contacts")
    suspend fun getContacts(@Query("ownerId") ownerId: String): Response<JsonElement>

    // Ignore the mutation response body; reload contacts after a successful write.
    @POST("contacts")
    suspend fun addContact(@Body payload: JsonElement): Response<Unit>

    // Invitation creation needs the saved contact ID; legacy addContact keeps its empty-body contract.
    @POST("contacts")
    suspend fun createInvitationContact(@Body payload: JsonElement): Response<JsonElement>

    @DELETE("contacts/{id}")
    suspend fun deleteContact(@Path("id") id: String): Response<Unit>

    @POST("contacts/{id}/invitation")
    suspend fun sendInvitation(@Path("id") contactId: String,
                               @Body body: ContactInvitationRequest): Response<ContactInvitationResponse>

    @GET("contacts/invitations/incoming")
    suspend fun getIncomingInvitations(): Response<IncomingInvitationsResponse>

    @POST("contacts/{id}/invitation/accept")
    suspend fun acceptInvitation(@Path("id") contactId: String,
                                 @Body body: ContactInvitationDecisionRequest): Response<ContactInvitationResponse>

    @POST("contacts/{id}/invitation/reject")
    suspend fun rejectInvitation(@Path("id") contactId: String,
                                 @Body body: ContactInvitationDecisionRequest): Response<ContactInvitationResponse>
}
