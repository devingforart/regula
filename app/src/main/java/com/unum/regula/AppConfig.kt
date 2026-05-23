package com.unum.regula

import android.content.Context
import java.util.UUID

data class AppConfig(
    val baseUrl: String = "",
    val apiToken: String = "",
    val sessionTag: String = UUID.randomUUID().toString(),
    val strictSecurityChecks: Boolean = true,
    val uploadImages: Boolean = true,
    val prepareDatabase: Boolean = true,
)

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("regula_capture_config", Context.MODE_PRIVATE)

    fun load(): AppConfig = AppConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
        apiToken = prefs.getString(KEY_API_TOKEN, "") ?: "",
        sessionTag = prefs.getString(KEY_SESSION_TAG, UUID.randomUUID().toString()) ?: UUID.randomUUID().toString(),
        strictSecurityChecks = prefs.getBoolean(KEY_STRICT_SECURITY, true),
        uploadImages = prefs.getBoolean(KEY_UPLOAD_IMAGES, true),
        prepareDatabase = prefs.getBoolean(KEY_PREPARE_DB, true),
    )

    fun save(config: AppConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_TOKEN, config.apiToken)
            .putString(KEY_SESSION_TAG, config.sessionTag)
            .putBoolean(KEY_STRICT_SECURITY, config.strictSecurityChecks)
            .putBoolean(KEY_UPLOAD_IMAGES, config.uploadImages)
            .putBoolean(KEY_PREPARE_DB, config.prepareDatabase)
            .apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_TOKEN = "api_token"
        const val KEY_SESSION_TAG = "session_tag"
        const val KEY_STRICT_SECURITY = "strict_security"
        const val KEY_UPLOAD_IMAGES = "upload_images"
        const val KEY_PREPARE_DB = "prepare_db"
    }
}
