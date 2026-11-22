package com.project.saaya.data.remote

import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

class GoogleSignInRequest(val idToken: String)

interface UserApiService {
    @POST("users/google")
    suspend fun signIn(@Body request: GoogleSignInRequest): Response<AuthSession>

    @GET("users/me")
    suspend fun getMe(@Header("Authorization") authorization: String): Response<AuthenticatedUser>

    @DELETE("users/session")
    suspend fun signOut(@Header("Authorization") authorization: String): Response<Unit>
}
