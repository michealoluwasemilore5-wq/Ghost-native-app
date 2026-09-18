package com.mgghost.assistant

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

object GhostApi {
    private val executor = Executors.newCachedThreadPool()
    private const val PREFS = "ghost_api"
    private const val TOKEN = "session_token"

    fun ask(context: Context, prompt: String, callback: (String?, String?) -> Unit) {
        executor.execute {
            try {
                val token = getOrCreateSession(context)
                val response = postJson(
                    BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/chat",
                    token,
                    JSONObject().put("message", prompt).toString()
                )
                if (response.code !in 200..299) {
                    callback(null, parseError(response.body) ?: "AI request failed (${response.code}).")
                    return@execute
                }
                val reply = JSONObject(response.body).optString("reply", "").trim()
                callback(if (reply.isBlank()) null else reply, if (reply.isBlank()) "AI returned no reply." else null)
            } catch (e: Exception) {
                callback(null, "I couldn't reach MG GHOST right now. Please check your internet connection.")
            }
        }
    }

    private fun getOrCreateSession(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(TOKEN, null)
        if (!existing.isNullOrBlank()) return existing

        val response = postJson(
            BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/session",
            null,
            "{}"
        )
        if (response.code !in 200..299) throw IllegalStateException("Could not create session")
        val token = JSONObject(response.body).optString("token", "").trim()
        if (token.isBlank()) throw IllegalStateException("Server returned no session token")
        prefs.edit().putString(TOKEN, token).apply()
        return token
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun postJson(urlString: String, token: String?, body: String): HttpResult {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MG-GHOST-Android")
            if (!token.isNullOrBlank()) setRequestProperty("X-Ghost-Token", token)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            return HttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(body: String): String? = try {
        JSONObject(body).optString("error", null)
    } catch (_: Exception) { null }
}
