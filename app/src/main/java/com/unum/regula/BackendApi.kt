package com.unum.regula

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class BackendApi(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    fun createSession(config: AppConfig): String {
        val payload = JSONObject()
            .put("tag", config.sessionTag)
            .put("platform", "android")
            .put("source", "regula-mobile-sdk")

        val request = Request.Builder()
            .url(config.normalizedBaseUrl("api/v1/sessions"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .applyAuth(config.apiToken)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Error creating session: HTTP ${response.code} - $body")
            }
            val json = JSONObject(body)
            return json.optString("sessionId").ifBlank {
                error("Session response did not include sessionId")
            }
        }
    }

    fun uploadImage(config: AppConfig, sessionId: String, file: File, type: String): JSONObject {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("type", type)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(config.normalizedBaseUrl("api/v1/sessions/$sessionId/images"))
            .post(multipart)
            .applyAuth(config.apiToken)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Error uploading image $type: HTTP ${response.code} - $body")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    fun submitResult(config: AppConfig, sessionId: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(config.normalizedBaseUrl("api/v1/sessions/$sessionId/results"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .applyAuth(config.apiToken)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Error submitting result: HTTP ${response.code} - $body")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    private fun Request.Builder.applyAuth(token: String): Request.Builder {
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        }
        header("Accept", "application/json")
        return this
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun AppConfig.normalizedBaseUrl(path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    require(base.startsWith("http://") || base.startsWith("https://")) {
        "La Base URL debe comenzar con http:// o https://"
    }
    return "$base/$path"
}
