package com.unum.regula

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.regula.documentreader.api.DocumentReader
import com.regula.documentreader.api.completions.IDocumentReaderCompletion
import com.regula.documentreader.api.completions.IDocumentReaderInitCompletion
import com.regula.documentreader.api.completions.IDocumentReaderPrepareCompletion
import com.regula.documentreader.api.enums.DocReaderAction
import com.regula.documentreader.api.enums.Scenario
import com.regula.documentreader.api.errors.DocumentReaderException
import com.regula.documentreader.api.params.AuthenticityParams
import com.regula.documentreader.api.params.DocReaderConfig
import com.regula.documentreader.api.params.LivenessParams
import com.regula.documentreader.api.results.DocumentReaderResults
import com.regula.documentreader.api.config.ScannerConfig
import com.unum.regula.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
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
            initializeReader(config)
        }

        binding.captureButton.setOnClickListener {
            val config = collectConfig()
            configStore.save(config)
            if (!readerInitialized) {
                initializeReader(config) {
                    ensureCameraAndCapture()
                }
            } else {
                ensureCameraAndCapture()
            }
        }

        renderStatus(
            "Coloca `regula.license` en `app/src/main/assets/`.\n" +
                "Opcional: coloca `db.dat` en `app/src/main/assets/Regula/` si no quieres descargar la base.\n" +
                "Luego configura tu API e inicializa Regula."
        )
    }

    private fun bindConfig(config: AppConfig) = with(binding) {
        baseUrlInput.setText(config.baseUrl)
        apiTokenInput.setText(config.apiToken)
        sessionTagInput.setText(config.sessionTag)
        strictSecurityCheckbox.isChecked = config.strictSecurityChecks
        uploadImagesCheckbox.isChecked = config.uploadImages
        prepareDatabaseCheckbox.isChecked = config.prepareDatabase
    }

    private fun collectConfig(): AppConfig = AppConfig(
        baseUrl = binding.baseUrlInput.text?.toString().orEmpty().trim(),
        apiToken = binding.apiTokenInput.text?.toString().orEmpty().trim(),
        sessionTag = binding.sessionTagInput.text?.toString().orEmpty().trim().ifBlank { UUID.randomUUID().toString() },
        strictSecurityChecks = binding.strictSecurityCheckbox.isChecked,
        uploadImages = binding.uploadImagesCheckbox.isChecked,
        prepareDatabase = binding.prepareDatabaseCheckbox.isChecked,
    )

    private fun initializeReader(config: AppConfig, onReady: (() -> Unit)? = null) {
        prepareDatabaseBeforeInit(config) {
            doInitializeReader(config, onReady)
        }
    }

    private fun doInitializeReader(config: AppConfig, onReady: (() -> Unit)? = null) {
        val license = try {
            assets.open("regula.license").use { it.readBytes() }
        } catch (_: IOException) {
            renderStatus("Falta `app/src/main/assets/regula.license`.")
            return
        }

        val initConfig = DocReaderConfig(license)
        renderStatus("Inicializando Regula...")

        DocumentReader.Instance().initializeReader(this, initConfig, object : IDocumentReaderInitCompletion {
            override fun onInitCompleted(success: Boolean, error: DocumentReaderException?) {
                if (!success) {
                    readerInitialized = false
                    renderStatus("Falló la inicialización de Regula: ${error?.message ?: "sin detalle"}")
                    return
                }

                configureProcessParams(config)
                renderStatus("Regula listo.")
                onReady?.invoke()
            }
        })
    }

    private fun configureProcessParams(config: AppConfig) {
        val authenticityParams = AuthenticityParams.defaultParams()
        authenticityParams.livenessParams = LivenessParams.defaultParams()
        DocumentReader.Instance().processParams().authenticityParams = authenticityParams
        DocumentReader.Instance().processParams().strictSecurityChecks = config.strictSecurityChecks
        readerInitialized = true
    }

    private fun prepareDatabaseBeforeInit(config: AppConfig, onReady: () -> Unit) {
        val hasEmbeddedDb = runCatching { assets.open("Regula/db.dat").close(); true }.getOrDefault(false)
        if (hasEmbeddedDb || !config.prepareDatabase) {
            onReady()
            return
        }

        renderStatus("Descargando base `db.dat` compatible...")
        DocumentReader.Instance().prepareDatabase(this, "Full", object : IDocumentReaderPrepareCompletion {
            override fun onPrepareProgressChanged(progress: Int) {
                renderStatus("Descargando base `db.dat`: $progress%")
            }

            override fun onPrepareCompleted(status: Boolean, error: DocumentReaderException?) {
                if (!status) {
                    renderStatus("No se pudo preparar la base de documentos: ${error?.message ?: "sin detalle"}")
                    return
                }
                onReady()
            }
        })
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
        val scannerConfig = ScannerConfig.Builder(Scenario.SCENARIO_FULL_PROCESS).build()
        renderStatus("Abriendo cámara de Regula...")
        DocumentReader.Instance().startScanner(this, scannerConfig, object : IDocumentReaderCompletion {
            override fun onCompleted(action: Int, results: DocumentReaderResults?, error: DocumentReaderException?) {
                when (action) {
                    DocReaderAction.COMPLETE, DocReaderAction.TIMEOUT -> {
                        if (results == null) {
                            renderStatus("La captura terminó sin resultados. ${error?.message ?: ""}".trim())
                        } else {
                            handleCaptureResult(results)
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
        return buildString {
            appendLine("Captura enviada.")
            appendLine("sessionId: $sessionId")
            appendLine("documento: $docName")
            appendLine("overallStatus: $overall")
            appendLine("securityStatus: $security")
            appendLine("imageQAStatus: $imageQa")
            appendLine("imagenes procesadas: $imageCount")
            appendLine("respuesta API: $apiResponse")
        }
    }

    private fun renderStatus(message: String) {
        runOnUiThread {
            binding.statusText.text = message
            Toast.makeText(this, message.lineSequence().firstOrNull().orEmpty(), Toast.LENGTH_SHORT).show()
        }
    }
}
