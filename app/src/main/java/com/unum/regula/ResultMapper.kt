package com.unum.regula

import android.content.Context
import android.graphics.Bitmap
import com.regula.documentreader.api.results.DocumentReaderResults
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

data class CapturePayload(
    val resultJson: JSONObject,
    val imageFiles: List<CapturedImage>,
)

data class CapturedImage(
    val type: String,
    val file: File,
    val sha256: String,
)

object ResultMapper {
    fun map(context: Context, results: DocumentReaderResults, sessionId: String): CapturePayload {
        val appConfig = ConfigStore(context).load()
        val textFields = JSONArray()
        results.textResult?.fields?.forEach { field ->
            val values = JSONArray()
            field.values?.forEach { value ->
                values.put(
                    JSONObject()
                        .put("sourceType", value.sourceType)
                        .put("value", value.value)
                        .put("originalValue", value.originalValue)
                        .put("pageIndex", value.pageIndex)
                        .put("probability", value.probability)
                )
            }

            textFields.put(
                JSONObject()
                    .put("fieldType", field.fieldType)
                    .put("fieldName", field.getFieldName(context))
                    .put("value", field.value)
                    .put("status", field.status)
                    .put("comparisonStatus", field.comparisonStatus)
                    .put("validityStatus", field.validityStatus)
                    .put("values", values)
            )
        }

        val authenticityChecks = JSONArray()
        results.authenticityResult?.checks?.forEach { check ->
            val elements = JSONArray()
            check.elements?.forEach { element ->
                elements.put(
                    JSONObject()
                        .put("status", element.status)
                        .put("elementType", element.elementType)
                        .put("elementTypeName", element.getElementTypeName(context))
                        .put("elementDiagnose", element.elementDiagnose)
                        .put("elementDiagnoseName", element.getElementDiagnoseName(context))
                )
            }

            authenticityChecks.put(
                JSONObject()
                    .put("type", check.type)
                    .put("typeName", check.getTypeName(context))
                    .put("status", check.getStatus())
                    .put("pageIndex", check.pageIndex)
                    .put("elements", elements)
            )
        }

        val imageQuality = JSONArray()
        results.imageQuality?.forEach { group ->
            group.imageQualityList?.forEach { quality ->
                imageQuality.put(
                    JSONObject()
                        .put("pageIndex", group.pageIndex)
                        .put("groupResult", group.result)
                        .put("type", quality.type)
                        .put("result", quality.result)
                        .put("featureType", quality.featureType)
                )
            }
        }

        val barcodeFields = JSONArray()
        results.barcodeResult?.fields?.forEach { barcode ->
            barcodeFields.put(
                JSONObject()
                    .put("barcodeType", barcode.barcodeType)
                    .put("status", barcode.status)
                    .put("data", barcode.data)
                    .put("pageIndex", barcode.pageIndex)
            )
        }

        val images = mutableListOf<CapturedImage>()
        val graphicFields = JSONArray()
        val graphicLights = linkedSetOf<Int>()
        val graphicSourceTypes = linkedSetOf<Int>()
        results.graphicResult?.fields?.forEachIndexed { index, field ->
            val bitmap = field.getBitmap() ?: return@forEachIndexed
            val fieldName = field.getFieldName(context)
            graphicLights += field.light
            graphicSourceTypes += field.sourceType
            val fileType = buildGraphicType(fieldName, index, field.pageIndex)
            val file = persistBitmap(context, sessionId, fileType, bitmap)
            val hash = sha256(file)
            images += CapturedImage(fileType, file, hash)
            graphicFields.put(
                JSONObject()
                    .put("fieldType", field.fieldType)
                    .put("fieldName", fieldName)
                    .put("sourceType", field.sourceType)
                    .put("light", field.light)
                    .put("pageIndex", field.pageIndex)
                    .put("sha256", hash)
                    .put("fileName", file.name)
            )
        }

        val documentTypes = JSONArray()
        results.documentType?.forEach { docType ->
            documentTypes.put(
                JSONObject()
                    .put("name", docType.name)
                    .put("documentId", docType.documentID)
                    .put("icaoCode", docType.ICAOCode)
                    .put("countryName", docType.dCountryName)
                    .put("pageIndex", docType.pageIndex)
            )
        }

        val payload = JSONObject()
            .put("sessionId", sessionId)
            .put("documentTypes", documentTypes)
            .put("captureDevice", JSONObject()
                .put("expectedDevice", "Regula 7310")
                .put("readerMode", appConfig.readerMode)
                .put("deviceName", appConfig.deviceName)
                .put("deviceAddress", appConfig.deviceAddress)
                .put("authenticatorRequired", true)
                .put("authenticatorConfirmed", appConfig.readerMode == READER_MODE_BLE_AUTHENTICATOR)
            )
            .put("imageEvidence", JSONObject()
                .put("graphicFieldCount", graphicFields.length())
                .put("lights", JSONArray(graphicLights.toList()))
                .put("sourceTypes", JSONArray(graphicSourceTypes.toList()))
            )
            .put("status", JSONObject()
                .put("overall", results.status?.getOverallStatus())
                .put("optical", results.status?.getOptical())
                .put("rfid", results.status?.getRfid())
                .put("portrait", results.status?.getPortrait())
                .put("stopList", results.status?.getStopList())
                .put("security", results.status?.getDetailsOptical()?.getSecurity())
                .put("imageQa", results.status?.getDetailsOptical()?.getImageQA())
                .put("expiry", results.status?.getDetailsOptical()?.getExpiry())
                .put("captureIntegrity", results.status?.getCaptureProcessIntegrity())
            )
            .put("textFields", textFields)
            .put("graphicFields", graphicFields)
            .put("authenticityChecks", authenticityChecks)
            .put("imageQualityChecks", imageQuality)
            .put("barcodes", barcodeFields)
            .put("transactionInfo", JSONObject()
                .put("tag", results.transactionInfo?.tag)
                .put("transactionId", results.transactionInfo?.transactionId)
            )
            .put("rawResult", results.rawResult)

        return CapturePayload(
            resultJson = payload,
            imageFiles = images,
        )
    }

    private fun buildGraphicType(fieldName: String?, index: Int, pageIndex: Int): String {
        val base = fieldName
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            .orEmpty()
            .ifBlank { "graphic" }
        return "page-$pageIndex-$base-$index"
    }

    private fun persistBitmap(context: Context, sessionId: String, type: String, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "captures/$sessionId").apply { mkdirs() }
        val file = File(dir, "$type.jpg")
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
