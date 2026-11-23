package com.petpulse.app.cloud

import android.content.Context
import com.petpulse.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supabase settings, baked in at build time from gradle properties or environment variables
 * (CFP_SUPABASE_URL, CFP_SUPABASE_ANON_KEY). Empty means the app runs fully offline.
 */
object CloudConfig {
    val supabaseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val enabled get() = supabaseUrl.isNotEmpty() && anonKey.isNotEmpty()
}

class CloudException(message: String, val code: String? = null, val status: Int = 0) : Exception(message)

/**
 * A Supabase session for this device. Starts as an anonymous account (no email needed), which is
 * enough for AI checks and purchases; the user id is also the RevenueCat app user id, so the
 * billing webhook can attach subscriptions to it. Requires "Anonymous sign-ins" to be enabled in
 * Supabase → Authentication → Sign In / Providers.
 */
class CloudSession(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)
    private val lock = Mutex()

    val userId: String? get() = prefs.getString("user_id", null)

    suspend fun accessToken(): String = lock.withLock {
        val token = prefs.getString("access_token", null)
        val expires = prefs.getLong("expires_at", 0)
        if (token != null && System.currentTimeMillis() < expires - 60_000) return token
        val refresh = prefs.getString("refresh_token", null)
        val json = if (refresh != null) {
            runCatching { post("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh), null) }
                .getOrElse { post("/auth/v1/signup", JSONObject(), null) }
        } else post("/auth/v1/signup", JSONObject(), null)
        prefs.edit()
            .putString("access_token", json.getString("access_token"))
            .putString("refresh_token", json.getString("refresh_token"))
            .putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
            .putString("user_id", json.getJSONObject("user").getString("id"))
            .apply()
        json.getString("access_token")
    }

    /** POST JSON to Supabase, returning the decoded body or throwing [CloudException] with the server's message. */
    suspend fun post(path: String, body: JSONObject, bearer: String?): JSONObject = withContext(Dispatchers.IO) {
        if (!CloudConfig.enabled) throw CloudException("Cloud features aren't set up in this build.", "NO_CLOUD")
        val conn = URL(CloudConfig.supabaseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", CloudConfig.anonKey)
            conn.setRequestProperty("Authorization", "Bearer ${bearer ?: CloudConfig.anonKey}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                val msg = json.optString("error").ifEmpty { json.optString("msg").ifEmpty { json.optString("message", "Request failed ($status)") } }
                throw CloudException(msg, json.optString("code").ifEmpty { null }, status)
            }
            json
        } finally {
            conn.disconnect()
        }
    }
}
