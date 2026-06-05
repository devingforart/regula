package com.unum.regula

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.regula.documentreader.api.DocumentReader
import com.regula.documentreader.api.enums.DocReaderAction
import com.regula.documentreader.api.enums.Scenario
import com.regula.documentreader.api.errors.DocumentReaderException
import com.regula.documentreader.api.params.AuthenticityParams
import com.regula.documentreader.api.params.LivenessParams
import com.regula.documentreader.api.results.DocumentReaderResults
import com.regula.documentreader.api.config.ScannerConfig
import com.regula.documentreader.api.completions.IDocumentReaderCompletion
import com.regula.documentreader.api.completions.rfid.IRfidReaderCompletion
import com.unum.regula.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: ConfigStore
    private val backendApi = BackendApi()
    private var readerInitialized = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCapture()
        } else {
            renderStatus("Se necesita permiso de cámara para capturar el documento.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = ConfigStore(this)
        bindConfig(configStore.load())

        binding.saveConfigButton.setOnClickListener {
            val config = collectConfig()
            configStore.save(config)
            renderStatus("Configuración guardada.")
        }

        binding.initButton.setOnClickListener {
            val config = collectConfig()
            configStore.save(config)
            startActivity(Intent(this, ConnectDeviceActivity::class.java))
        }

        binding.captureButton.setOnClickListener {
            val config = collectConfig()
            configStore.save(config)
            if (!DocumentReader.Instance().isReady) {
                renderStatus("Conecta primero el Regula 7310.")
                startActivity(Intent(this, ConnectDeviceActivity::class.java))
            } else if (config.readerMode != READER_MODE_BLE_AUTHENTICATOR) {
                renderStatus("Captura bloqueada: el SDK está listo, pero no hay evidencia de autenticador 7310 conectado. Vuelve a conectar Regula 7310.")
                startActivity(Intent(this, ConnectDeviceActivity::class.java))
            } else {
                configureProcessParams(config)
                ensureCameraAndCapture()
            }
        }

        renderStatus(
            "Configura tu API, conecta el Regula 7310 y luego captura con autenticidad."
        )
    }

    override fun onResume() {
        super.onResume()
        readerInitialized = DocumentReader.Instance().isReady
        if (readerInitialized && configStore.load().readerMode == READER_MODE_BLE_AUTHENTICATOR) {
            renderStatus("Regula 7310 conectado. Puedes capturar el documento.")
        } else if (readerInitialized) {
            renderStatus("SDK inicializado sin evidencia de autenticador 7310. Conecta el 7310 antes de capturar.")
        }
    }

    private fun bindConfig(config: AppConfig) = with(binding) {
        baseUrlInput.setText(config.baseUrl)
        apiTokenInput.setText(config.apiToken)
        sessionTagInput.setText(config.sessionTag)
        strictSecurityCheckbox.isChecked = config.strictSecurityChecks
        readRfidCheckbox.isChecked = config.readRfidChip
        uploadImagesCheckbox.isChecked = config.uploadImages
        prepareDatabaseCheckbox.isChecked = config.prepareDatabase
    }

    private fun collectConfig(): AppConfig = AppConfig(
        baseUrl = binding.baseUrlInput.text?.toString().orEmpty().trim(),
        apiToken = binding.apiTokenInput.text?.toString().orEmpty().trim(),
        sessionTag = binding.sessionTagInput.text?.toString().orEmpty().trim().ifBlank { UUID.randomUUID().toString() },
        deviceName = configStore.load().deviceName,
        deviceAddress = configStore.load().deviceAddress,
        readerMode = configStore.load().readerMode,
        strictSecurityChecks = binding.strictSecurityCheckbox.isChecked,
        readRfidChip = binding.readRfidCheckbox.isChecked,
        uploadImages = binding.uploadImagesCheckbox.isChecked,
        prepareDatabase = binding.prepareDatabaseCheckbox.isChecked,
    )

    private fun configureProcessParams(config: AppConfig) {
        val authenticityParams = AuthenticityParams.defaultParams()
        authenticityParams.livenessParams = LivenessParams.defaultParams()
        DocumentReader.Instance().processParams().authenticityParams = authenticityParams
        DocumentReader.Instance().processParams().strictSecurityChecks = config.strictSecurityChecks
        readerInitialized = true
    }

    private fun ensureCameraAndCapture() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCapture() {
        val scannerConfig = ScannerConfig.Builder(Scenario.SCENARIO_FULL_AUTH).build()
        renderStatus("Abriendo autenticación con Regula 7310...")
        DocumentReader.Instance().startScanner(this, scannerConfig, object : IDocumentReaderCompletion {
            override fun onCompleted(action: Int, results: DocumentReaderResults?, error: DocumentReaderException?) {
                when (action) {
                    DocReaderAction.COMPLETE, DocReaderAction.TIMEOUT -> {
                        if (results == null) {
                            renderStatus("La captura terminó sin resultados. ${error?.message ?: ""}".trim())
                        } else {
                            val config = collectConfig()
                            if (config.readRfidChip && results.chipPage != 0) {
                                renderStatus("Leyendo chip RFID del documento...")
                                DocumentReader.Instance().startRFIDReader(
                                    this@MainActivity,
                                    object : IRfidReaderCompletion() {
                                        override fun onCompleted(
                                            rfidAction: Int,
                                            rfidResults: DocumentReaderResults?,
                                            rfidError: DocumentReaderException?,
                                        ) {
                                            if ((rfidAction == DocReaderAction.COMPLETE || rfidAction == DocReaderAction.CANCEL) && rfidResults != null) {
                                                handleCaptureResult(rfidResults)
                                            } else if (rfidError != null) {
                                                renderStatus("RFID falló: ${rfidError.message}")
                                                handleCaptureResult(results)
                                            }
                                        }
                                    }
                                )
                            } else {
                                handleCaptureResult(results)
                            }
                        }
                    }

                    else -> {
                        if (error != null) {
                            renderStatus("Error durante captura: ${error.message}")
                        }
                    }
                }
            }
        })
    }

    private fun handleCaptureResult(results: DocumentReaderResults) {
        val config = collectConfig()
        lifecycleScope.launch {
            try {
                renderStatus("Procesando resultados y enviando a tu API...")
                val summary = withContext(Dispatchers.IO) {
                    val sessionId = backendApi.createSession(config)
                    val payload = ResultMapper.map(this@MainActivity, results, sessionId)
                    val uploadedImages = JSONArray()
                    if (config.uploadImages) {
                        payload.imageFiles.forEach { image ->
                            val response = backendApi.uploadImage(config, sessionId, image.file, image.type)
                            uploadedImages.put(response.put("type", image.type).put("sha256", image.sha256))
                        }
                    }

                    payload.resultJson.put("uploadedImages", uploadedImages)
                    val apiResponse = backendApi.submitResult(config, sessionId, payload.resultJson)
                    buildSummary(results, sessionId, payload.imageFiles.size, apiResponse.toString())
                }
                renderStatus(summary)
            } catch (t: Throwable) {
                renderStatus("Falló el envío a la API: ${t.message}")
            }
        }
    }

    private fun buildSummary(
        results: DocumentReaderResults,
        sessionId: String,
        imageCount: Int,
        apiResponse: String,
    ): String {
        val docName = results.documentType.firstOrNull()?.name ?: "documento desconocido"
        val overall = results.status?.getOverallStatus()
        val security = results.status?.getDetailsOptical()?.getSecurity()
        val imageQa = results.status?.getDetailsOptical()?.getImageQA()
        val verdict = pocVerdict(results)
        return buildString {
            appendLine("Captura enviada.")
            appendLine("veredicto POC: ${verdict.label}")
            appendLine("criterio: ${verdict.explanation}")
            appendLine("sessionId: $sessionId")
            appendLine("documento: $docName")
            appendLine("overallStatus: ${checkResultName(overall)}")
            appendLine("securityStatus: ${checkResultName(security)}")
            appendLine("imageQAStatus: ${checkResultName(imageQa)}")
            appendLine("imagenes procesadas: $imageCount")
            appendLine("respuesta API: $apiResponse")
        }
    }

    private fun pocVerdict(results: DocumentReaderResults): PocVerdict {
        val overall = results.status?.getOverallStatus()
        val optical = results.status?.getOptical()
        val security = results.status?.getDetailsOptical()?.getSecurity()
        val imageQa = results.status?.getDetailsOptical()?.getImageQA()
        val expiry = results.status?.getDetailsOptical()?.getExpiry()
        var hasInvalidInputOrTimeout = false

        results.authenticityResult?.checks?.forEach { check ->
            check.elements?.forEach { element ->
                val diagnose = element.getElementDiagnoseName(this).lowercase()
                if (
                    diagnose.contains("datos de entrada") ||
                    diagnose.contains("invalid input") ||
                    diagnose.contains("tiempo") ||
                    diagnose.contains("timeout") ||
                    diagnose.contains("exceeded")
                ) {
                    hasInvalidInputOrTimeout = true
                }
            }
        }

        return when {
            security == 1 && overall == 1 && optical == 1 ->
                PocVerdict("Autenticidad aprobada", "El SDK reportó seguridad y óptica OK.")
            hasInvalidInputOrTimeout || imageQa == 0 ->
                PocVerdict("Requiere recaptura", "Hubo timeout, datos inválidos o calidad insuficiente.")
            security == 0 || overall == 0 || optical == 0 || expiry == 0 ->
                PocVerdict("No aprobado", "El SDK reportó fallo de seguridad, óptica, expiración o resultado general.")
            else ->
                PocVerdict("No concluyente", "No hay suficientes controles para decisión automática.")
        }
    }

    private fun checkResultName(value: Int?): String = when (value) {
        1 -> "OK"
        0 -> "ERROR"
        2 -> "NO_EJECUTADO"
        else -> "DESCONOCIDO($value)"
    }

    private fun renderStatus(message: String) {
        runOnUiThread {
            binding.statusText.text = message
            Toast.makeText(this, message.lineSequence().firstOrNull().orEmpty(), Toast.LENGTH_SHORT).show()
        }
    }

    private data class PocVerdict(
        val label: String,
        val explanation: String,
    )
}
