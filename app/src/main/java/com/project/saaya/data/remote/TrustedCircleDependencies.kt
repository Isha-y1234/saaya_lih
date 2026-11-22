package com.project.saaya.data.remote

import com.project.saaya.data.repository.FastApiTrustedContactJsonMapper
import com.project.saaya.data.repository.TrustedCircleRepository
import com.project.saaya.ui.state.TrustedCircleViewModel
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.project.saaya.data.auth.SessionManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Existing Render Retrofit configuration with an authenticated, user-scoped repository. */
object TrustedCircleDependencies {
    private const val BASE_URL = "https://narikavach-backend.onrender.com/"

    fun createRetrofit(sessions: SessionManager): Retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(sessions))
                .callTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    fun viewModelFactory(userId: String, retrofit: Retrofit): TrustedCircleViewModel.Factory {
        val api = retrofit.create(SaayaApiService::class.java)
        return TrustedCircleViewModel.Factory(
            TrustedCircleRepository(api, FastApiTrustedContactJsonMapper, userId)
        )
    }
}
