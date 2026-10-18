package com.univibe.app.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** WebSockets over OkHttp. The ping keeps the connection honest: a dead network surfaces as a failure within about a minute. */
class OkHttpSocketFactory(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // the socket is long-lived; the ping detects a dead one
        .build(),
) : SocketFactory {
    override fun open(url: String, listener: SocketListener): SocketConnection {
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null) // answer the server's close (echoing odd codes such as 1005 would throw)
                listener.onClosed(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
        })
        return object : SocketConnection {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String) { socket.close(code, reason) }
        }
    }
}
