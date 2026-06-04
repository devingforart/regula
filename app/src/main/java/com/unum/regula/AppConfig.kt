package com.unum.regula

import android.content.Context
import java.util.UUID

data class AppConfig(
    val baseUrl: String = "",
    val apiToken: String = "",
    val sessionTag: String = UUID.randomUUID().toString(),
    val deviceName: String = "Regula 7310",
    val deviceAddress: String = "",
    val strictSecurityChecks: Boolean = true,
    val readRfidChip: Boolean = false,
    val uploadImages: Boolean = true,
    val prepareDatabase: Boolean = true,
)

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("regula_capture_config", Context.MODE_PRIVATE)

    fun load(): AppConfig = AppConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
        apiToken = prefs.getString(KEY_API_TOKEN, "") ?: "",
        sessionTag = prefs.getString(KEY_SESSION_TAG, UUID.randomUUID().toString()) ?: UUID.randomUUID().toString(),
        deviceName = prefs.getString(KEY_DEVICE_NAME, "Regula 7310") ?: "Regula 7310",
        deviceAddress = prefs.getString(KEY_DEVICE_ADDRESS, "") ?: "",
        strictSecurityChecks = prefs.getBoolean(KEY_STRICT_SECURITY, true),
        readRfidChip = prefs.getBoolean(KEY_READ_RFID, false),
        uploadImages = prefs.getBoolean(KEY_UPLOAD_IMAGES, true),
        prepareDatabase = prefs.getBoolean(KEY_PREPARE_DB, true),
    )

    fun save(config: AppConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_TOKEN, config.apiToken)
            .putString(KEY_SESSION_TAG, config.sessionTag)
            .putString(KEY_DEVICE_NAME, config.deviceName)
            .putString(KEY_DEVICE_ADDRESS, config.deviceAddress)
            .putBoolean(KEY_STRICT_SECURITY, config.strictSecurityChecks)
            .putBoolean(KEY_READ_RFID, config.readRfidChip)
            .putBoolean(KEY_UPLOAD_IMAGES, config.uploadImages)
            .putBoolean(KEY_PREPARE_DB, config.prepareDatabase)
            .apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_TOKEN = "api_token"
        const val KEY_SESSION_TAG = "session_tag"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_STRICT_SECURITY = "strict_security"
        const val KEY_READ_RFID = "read_rfid"
        const val KEY_UPLOAD_IMAGES = "upload_images"
        const val KEY_PREPARE_DB = "prepare_db"
    }
}
