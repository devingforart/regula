package com.unum.regula

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.regula.common.ble.BLEWrapper
import com.regula.common.ble.BleWrapperCallback
import com.regula.common.ble.RegulaBleService
import com.regula.common.ble.callback.BleManagerCallback
import com.regula.documentreader.api.DocumentReader
import com.regula.documentreader.api.completions.IDocumentReaderInitCompletion
import com.regula.documentreader.api.completions.IDocumentReaderPrepareCompletion
import com.regula.documentreader.api.errors.DocumentReaderException
import com.regula.documentreader.api.params.BleDeviceConfig
import com.unum.regula.databinding.ActivityConnectDeviceBinding

class ConnectDeviceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConnectDeviceBinding
    private lateinit var configStore: ConfigStore
    private var bleManager: BLEWrapper? = null
    private var isBleServiceConnected = false
    private var loadingDialog: AlertDialog? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        dismissDialog()
        stopBleService()
        showStatus("No se pudo conectar al Regula 7310. Verifica que esté encendido y con Bluetooth activo.")
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            ensureBluetoothAndConnect()
        } else {
            showStatus("Se necesitan permisos Bluetooth/ubicación para emparejar el Regula 7310.")
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter?.isEnabled == true) {
            startBluetoothService()
        } else {
            showStatus("Activa Bluetooth para conectar el Regula 7310.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = ConfigStore(this)
        val config = configStore.load()
        binding.deviceNameInput.setText(config.deviceName)

        if (DocumentReader.Instance().isReady) {
            launchMain()
            return
        }

        binding.connectButton.setOnClickListener {
            val updated = config.copy(deviceName = binding.deviceNameInput.text?.toString().orEmpty().trim().ifBlank { "Regula 7310" })
            configStore.save(updated)
            requestPermissionsAndConnect()
        }

        binding.skipButton.setOnClickListener {
            launchMain()
        }
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        stopBleService()
        super.onDestroy()
    }

    private fun requestPermissionsAndConnect() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            ensureBluetoothAndConnect()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureBluetoothAndConnect() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            showStatus("Este teléfono no tiene Bluetooth disponible para el Regula 7310.")
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startBluetoothService()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothService() {
        if (isBleServiceConnected && bleManager?.isConnected == true && !DocumentReader.Instance().isReady) {
            prepareDatabaseAndInitialize()
            return
        }

        if (isBleServiceConnected) return

        val deviceName = configStore.load().deviceName
        showDialog("Buscando $deviceName")

        val bleIntent = Intent(this, RegulaBleService::class.java).apply {
            putExtra(RegulaBleService.DEVICE_NAME, deviceName)
        }
        startService(bleIntent)
        bindService(bleIntent, bleConnection, BIND_AUTO_CREATE)
    }

    private val bleConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            isBleServiceConnected = true
            val bleService = (service as RegulaBleService.LocalBinder).service
            bleManager = bleService.bleManager

            if (DocumentReader.Instance().isReady) {
                launchMain()
                return
            }

            if (bleManager?.isConnected == true) {
                prepareDatabaseAndInitialize()
                return
            }

            timeoutHandler.postDelayed(timeoutRunnable, 10_000)
            bleManager?.addCallback(bleCallback)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBleServiceConnected = false
        }
    }

    private val bleCallback: BleManagerCallback = object : BleWrapperCallback() {
        override fun onDeviceReady() {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            bleManager?.removeCallback(this)
            prepareDatabaseAndInitialize()
        }
    }

    private fun prepareDatabaseAndInitialize() {
        val config = configStore.load()
        val hasEmbeddedDb = runCatching { assets.open("Regula/db.dat").close(); true }.getOrDefault(false)
        if (hasEmbeddedDb || !config.prepareDatabase) {
            initializeReader()
            return
        }

        showDialog("Descargando base FullAuth")
        DocumentReader.Instance().prepareDatabase(this, "FullAuth", object : IDocumentReaderPrepareCompletion {
            override fun onPrepareProgressChanged(progress: Int) {
                showStatus("Descargando base FullAuth: $progress%")
            }

            override fun onPrepareCompleted(status: Boolean, error: DocumentReaderException?) {
                if (!status) {
                    dismissDialog()
                    showStatus("No se pudo descargar FullAuth: ${error?.message ?: "sin detalle"}")
                    return
                }
                initializeReader()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun initializeReader() {
        val manager = bleManager
        if (manager == null) {
            dismissDialog()
            showStatus("No se pudo obtener la licencia del Regula 7310.")
            return
        }

        showDialog("Inicializando Regula 7310")
        DocumentReader.Instance().initializeReader(this, BleDeviceConfig(manager), object : IDocumentReaderInitCompletion {
            override fun onInitCompleted(success: Boolean, error: DocumentReaderException?) {
                dismissDialog()
                if (!success) {
                    showStatus("Falló la inicialización del 7310: ${error?.message ?: "sin detalle"}")
                    return
                }
                DocumentReader.Instance().functionality().edit().setUseAuthenticator(true).apply()
                showStatus("Regula 7310 conectado.")
                launchMain()
            }
        })
    }

    private fun launchMain() {
        dismissDialog()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun stopBleService() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        if (isBleServiceConnected) {
            runCatching { unbindService(bleConnection) }
            isBleServiceConnected = false
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions
    }

    private fun showDialog(title: String) {
        dismissDialog()
        loadingDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Mantén el Regula 7310 encendido y cerca del teléfono.")
            .setCancelable(false)
            .show()
    }

    private fun dismissDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            binding.statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
