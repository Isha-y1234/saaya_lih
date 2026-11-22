package com.project.saaya.data.remote

import com.project.saaya.data.auth.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val sessions: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = sessions.current
        // Never attach a session token to a foreign host or the Google token exchange.
        val authenticatedRequest = if (session != null &&
            request.url.scheme == "https" &&
            request.url.host == "narikavach-backend.onrender.com" &&
            request.url.encodedPath != "/users/google" &&
            request.header("Authorization") == null) {
            request.newBuilder().header("Authorization", "Bearer ${session.token}").build()
        } else request
        val response = chain.proceed(authenticatedRequest)
        if (response.code == 401 && session != null &&
            authenticatedRequest.header("Authorization") == "Bearer ${session.token}") {
            sessions.invalidate(session.token)
        }
        return response
    }
}
