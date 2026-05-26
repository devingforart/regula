package com.unum.regula

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.regula.common.ble.BLEWrapper
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
    private var loadingDialog: AlertDialog? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var selectedDevice: BluetoothDevice? = null
    private val discoveredDevices = linkedMapOf<String, BluetoothDevice>()
    private lateinit var devicesAdapter: ArrayAdapter<String>

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectTimeout = Runnable {
        dismissDialog()
        bleManager?.disconnect()
        showStatus("No se pudo conectar al Regula 7310. Selecciona otro dispositivo o verifica que el equipo esté activo.")
    }

    private val scanTimeout = Runnable {
        stopBleScan()
        if (discoveredDevices.isEmpty()) {
            showStatus("No se detectaron dispositivos BLE. Asegúrate de que la workstation esté activa.")
        } else {
            showStatus("Selecciona un dispositivo de la lista y luego toca conectar.")
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            ensureBluetoothAndScan()
        } else {
            showStatus("Se necesitan permisos Bluetooth para detectar el Regula 7310.")
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter?.isEnabled == true) {
            startBleScan()
        } else {
            showStatus("Activa Bluetooth para detectar el Regula 7310.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = ConfigStore(this)
        val config = configStore.load()
        binding.deviceNameInput.setText(config.deviceName)

        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.devicesList.adapter = devicesAdapter
        binding.devicesList.setOnItemClickListener { _, _, position, _ ->
            val selected = devicesAdapter.getItem(position).orEmpty()
            val address = selected.substringAfterLast(" | ").trim()
            val device = discoveredDevices.values.firstOrNull { it.address == address } ?: return@setOnItemClickListener
            selectedDevice = device
            val name = device.name?.trim().orEmpty().ifBlank { address }
            binding.deviceNameInput.setText(name)
            configStore.save(configStore.load().copy(deviceName = name))
            showStatus("Seleccionado: $name (${device.address})")
        }

        if (DocumentReader.Instance().isReady) {
            launchMain()
            return
        }

        binding.scanButton.setOnClickListener {
            requestPermissionsAndScan()
        }

        binding.connectButton.setOnClickListener {
            if (selectedDevice == null) {
                showStatus("Primero escanea BLE y selecciona un dispositivo de la lista.")
                return@setOnClickListener
            }
            connectSelectedDevice()
        }

        binding.skipButton.setOnClickListener {
            launchMain()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(connectTimeout)
        mainHandler.removeCallbacks(scanTimeout)
        stopBleScan()
        bleManager?.disconnect()
        super.onDestroy()
    }

    private fun requestPermissionsAndScan() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ensureBluetoothAndScan()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureBluetoothAndScan() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            showStatus("Este equipo no tiene Bluetooth disponible.")
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startBleScan()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val adapter = bluetoothAdapter ?: return
        bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            showStatus("No se pudo iniciar el escáner BLE.")
            return
        }

        selectedDevice = null
        discoveredDevices.clear()
        devicesAdapter.clear()
        devicesAdapter.notifyDataSetChanged()
        showStatus("Escaneando dispositivos BLE cercanos...")
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(null, settings, nativeScanCallback)
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, 8_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        runCatching { bleScanner?.stopScan(nativeScanCallback) }
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedDevice() {
        val device = selectedDevice ?: return
        stopBleScan()

        if (bleManager == null) {
            bleManager = BLEWrapper(this, bleCallbacks)
        }

        showDialog("Conectando ${device.name ?: device.address}")
        mainHandler.removeCallbacks(connectTimeout)
        mainHandler.postDelayed(connectTimeout, 10_000)
        bleManager?.connect(device)
    }

    private val bleCallbacks = object : BleManagerCallback {
        override fun onDeviceSearching() = Unit

        override fun onDeviceStopSearching() = Unit

        override fun onDeviceConnecting(device: BluetoothDevice) {
            showStatus("Conectando con ${device.name ?: device.address}...")
        }

        override fun onDeviceConnected(device: BluetoothDevice) {
            showStatus("Conectado a ${device.name ?: device.address}. Esperando autenticador...")
        }

        override fun onDeviceReady() {
            mainHandler.removeCallbacks(connectTimeout)
            prepareDatabaseAndInitialize()
        }

        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit

        override fun onDeviceDisconnected(device: BluetoothDevice) {
            showStatus("Desconectado: ${device.name ?: device.address}")
        }

        override fun onLinkLossOccurred(device: BluetoothDevice) {
            showStatus("Se perdió la conexión con ${device.name ?: device.address}")
        }

        override fun onServicesDiscovered(device: BluetoothDevice) = Unit

        override fun onError(device: BluetoothDevice?, message: String?, code: Int) {
            mainHandler.removeCallbacks(connectTimeout)
            dismissDialog()
            showStatus("Error Bluetooth ($code): ${message ?: "sin detalle"}")
        }

        override fun onBatteryValueReceived(value: Int) = Unit

        override fun onCharacteristicWrite(
            gatt: android.bluetooth.BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
        ) = Unit

        override fun onCharacteristicNotified(
            gatt: android.bluetooth.BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
        ) = Unit

        override fun onCardStatusChanged(data: ByteArray, present: Boolean) = Unit

        override fun onReceivedAPDUResponse(data: ByteArray) = Unit

        override fun onMtuChanged(mtu: Int) = Unit

        override fun onReceivedATRResponse(data: ByteArray) = Unit

        override fun onParametersResponse(data: ByteArray) = Unit

        override fun onStartFlashing() = Unit

        override fun onStartFlashingWithDelay() = Unit

        override fun onStopBeforeFinishFlashing() = Unit

        override fun onStopFlashing() = Unit

        override fun onStopFlashingWithDelay() = Unit

        override fun onGotLicense(success: Boolean, message: String, data: ByteArray) = Unit
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
            showStatus("No se pudo preparar el Regula 7310 para inicialización.")
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

    private val nativeScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result.device)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { addScanResult(it.device) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addScanResult(device: BluetoothDevice) {
        val name = device.name?.trim().orEmpty().ifBlank { "(sin nombre)" }
        val key = "$name|${device.address}"
        if (discoveredDevices.containsKey(key)) return
        discoveredDevices[key] = device
        runOnUiThread {
            devicesAdapter.add("$name | ${device.address}")
            devicesAdapter.notifyDataSetChanged()
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
            .setMessage("Mantén el Regula 7310 activo y cerca del equipo.")
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
