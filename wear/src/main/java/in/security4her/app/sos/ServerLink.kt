package `in`.security4her.app.sos

import `in`.security4her.app.BuildConfig
import `in`.security4her.app.data.DeviceLink
import `in`.security4her.app.data.S4hJson
import `in`.security4her.app.data.WatchSosRequest
import `in`.security4her.app.data.WatchSosResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Direct HTTPS to the watch-sos Edge Function (path B: works over Wi-Fi without the phone). */
object ServerLink {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val isConfigured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    suspend fun post(link: DeviceLink, request: WatchSosRequest): Result<WatchSosResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isConfigured) throw IOException("server not configured")
                val body = S4hJson.encodeToString(WatchSosRequest.serializer(), request).toRequestBody(JSON)
                val http = Request.Builder()
                    .url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/watch-sos")
                    .post(body)
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("x-device-token", link.deviceToken)
                    .build()
                client.newCall(http).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val parsed = runCatching { S4hJson.decodeFromString(WatchSosResponse.serializer(), text) }.getOrNull()
                    if (!response.isSuccessful || parsed?.ok != true) {
                        throw IOException(parsed?.error ?: "HTTP ${response.code}")
                    }
                    parsed
                }
            }
        }
}
