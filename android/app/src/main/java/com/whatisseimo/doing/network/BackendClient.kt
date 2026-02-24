package com.whatisseimo.doing.network

import com.whatisseimo.doing.BuildConfig
import com.whatisseimo.doing.model.AppCatalogSyncRequest
import com.whatisseimo.doing.model.DailySnapshotRequest
import com.whatisseimo.doing.model.ForegroundSwitchRequest
import com.whatisseimo.doing.model.GenericOkResponse
import com.whatisseimo.doing.model.HeartbeatRequest
import com.whatisseimo.doing.model.RegisterDeviceRequest
import com.whatisseimo.doing.model.RegisterDeviceResponse
import com.whatisseimo.doing.model.ScreenStateRequest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

class BackendClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    fun registerDevice(body: RegisterDeviceRequest): RegisterDeviceResponse {
        val request = Request.Builder()
            .url("${BuildConfig.SERVER_BASE_URL}/devices/register")
            .post(json.encodeToString(RegisterDeviceRequest.serializer(), body).toRequestBody(jsonType))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            ensureSuccess(response, "devices/register")
            val raw = response.body.string()
            if (raw.isBlank()) {
                throw BackendHttpException(
                    statusCode = response.code,
                    requestPath = "devices/register",
                    responseBody = null,
                    detailMessage = "Empty register response",
                )
            }
            json.decodeFromString(RegisterDeviceResponse.serializer(), raw)
        }
    }

    fun postHeartbeat(accessToken: String, body: HeartbeatRequest): GenericOkResponse {
        return postJson(
            path = "devices/heartbeat",
            accessToken = accessToken,
            payload = json.encodeToString(HeartbeatRequest.serializer(), body),
        )
    }

    fun postForegroundSwitch(accessToken: String, body: ForegroundSwitchRequest): GenericOkResponse {
        return postJson(
            path = "events/foreground-switch",
            accessToken = accessToken,
            payload = json.encodeToString(ForegroundSwitchRequest.serializer(), body),
        )
    }

    fun postDailySnapshot(accessToken: String, body: DailySnapshotRequest): GenericOkResponse {
        return postJson(
            path = "stats/daily-snapshot",
            accessToken = accessToken,
            payload = json.encodeToString(DailySnapshotRequest.serializer(), body),
        )
    }

    fun postScreenState(accessToken: String, body: ScreenStateRequest): GenericOkResponse {
        return postJson(
            path = "events/screen-state",
            accessToken = accessToken,
            payload = json.encodeToString(ScreenStateRequest.serializer(), body),
        )
    }

    fun postAppCatalogSync(accessToken: String, body: AppCatalogSyncRequest): GenericOkResponse {
        return postJson(
            path = "events/app-catalog-sync",
            accessToken = accessToken,
            payload = json.encodeToString(AppCatalogSyncRequest.serializer(), body),
        )
    }

    fun uploadScreenshotResult(accessToken: String, requestId: String, imageFile: File): GenericOkResponse {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("requestId", requestId)
            .addFormDataPart(
                "image",
                imageFile.name,
                imageFile.asRequestBody("image/png".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("${BuildConfig.SERVER_BASE_URL}/screenshots/result")
            .header("Authorization", "Bearer $accessToken")
            .post(requestBody)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            ensureSuccess(response, "screenshots/result")
            GenericOkResponse(true)
        }
    }

    private fun postJson(path: String, accessToken: String, payload: String): GenericOkResponse {
        val requestBody: RequestBody = payload.toRequestBody(jsonType)
        val request = Request.Builder()
            .url("${BuildConfig.SERVER_BASE_URL}/$path")
            .header("Authorization", "Bearer $accessToken")
            .post(requestBody)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            ensureSuccess(response, path)
            GenericOkResponse(true)
        }
    }

    private fun ensureSuccess(response: Response, path: String) {
        if (response.isSuccessful) {
            return
        }
        val body = response.body.string()
        throw BackendHttpException(
            statusCode = response.code,
            requestPath = path,
            responseBody = body.takeIf { it.isNotBlank() },
        )
    }
}

class BackendHttpException(
    val statusCode: Int,
    val requestPath: String,
    val responseBody: String?,
    detailMessage: String? = null,
) : RuntimeException(
    detailMessage
        ?: "$requestPath failed: HTTP $statusCode" + if (responseBody.isNullOrBlank()) "" else " body=$responseBody",
)
