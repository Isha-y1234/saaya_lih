package com.project.saaya.data.repository

import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class IncidentMessage(val id: String, val incidentId: String, val senderId: String,
                           val text: String, val createdAt: String)
data class ChatPage(val messages: List<IncidentMessage>, val readOnly: Boolean, val nextBefore: String?)
data class ChatFrame(val type: String, val messages: List<IncidentMessage>? = null,
                     val readOnly: Boolean = false, val detail: String? = null)
interface IncidentChatApi {
    @GET("incidents/{id}/chat/messages")
    suspend fun history(@Path("id") id: String, @Query("before") before: String? = null): ChatPage
}

/** All requests, including the WebSocket handshake, use the existing authenticated client. */
class IncidentChatRepository(private val retrofit: Retrofit) {
    private val api = retrofit.create(IncidentChatApi::class.java)
    private var socket: WebSocket? = null
    suspend fun older(id: String, before: String) = api.history(id, before)
    fun connect(id: String, after: String? = null) = callbackFlow<ChatFrame> {
        val request = Request.Builder().url(retrofit.baseUrl().newBuilder()
            .addPathSegment("incidents").addPathSegment(id).addPathSegment("chat").addPathSegment("ws")
            .apply { if (after != null) addQueryParameter("after", after) }.build()).build()
        val connection = (retrofit.callFactory() as OkHttpClient).newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { trySend(Gson().fromJson(text, ChatFrame::class.java)) }
                catch (_: Exception) { close(IllegalStateException("Invalid chat response")) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code in listOf(401, 403, 404)) trySend(ChatFrame("denied"))
                close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 4401 || code == 4404) trySend(ChatFrame("denied"))
                close()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
        socket = connection
        awaitClose { connection.cancel(); if (socket === connection) socket = null }
    }
    fun send(text: String): Boolean = socket?.send(Gson().toJson(mapOf("text" to text.trim()))) == true
}

fun mergeChatMessages(current: List<IncidentMessage>, incoming: List<IncidentMessage>, incidentId: String) =
    (current + incoming).filter { it.incidentId == incidentId }.distinctBy { it.id }.sortedBy { it.id }
