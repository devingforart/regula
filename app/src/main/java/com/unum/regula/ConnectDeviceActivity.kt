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
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.regula.common.ble.BLEWrapper
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
    private var loadingDialog: AlertDialog? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var selectedDevice: BluetoothDevice? = null
    private var selectedCandidate: BleCandidate? = null
    private var pendingCandidate: BleCandidate? = null
    private var connectionAttempts: List<ConnectionMethod> = emptyList()
    private var connectionAttemptIndex = 0
    private var isBleServiceConnected = false
    private val discoveredDevices = linkedMapOf<String, BleCandidate>()
    private lateinit var devicesAdapter: ArrayAdapter<String>

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectTimeout = Runnable {
        tryNextConnectionMethod("Tiempo agotado")
    }

    private val serviceTimeout = Runnable {
        stopRegulaBleService()
        showStatus("El servicio Regula no conectó. Probando conexión manual...")
        connectionAttemptIndex = 0
        tryConnectionAttempt("Servicio Regula sin respuesta")
    }

    private val scanTimeout = Runnable {
        stopBleScan()
        if (discoveredDevices.isEmpty()) {
            showStatus("No se detectaron dispositivos BLE. Asegúrate de que el equipo Regula esté encendido, con Bluetooth activo y cerca.")
        } else {
            showStatus("Selecciona un emparejado si aparece. Si todos salen sin nombre, usa la MAC con mejor señal.")
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
        binding.deviceNameInput.setText(savedDeviceLabel(config))

        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.devicesList.adapter = devicesAdapter
        binding.devicesList.setOnItemClickListener { _, _, position, _ ->
            val candidate = sortedCandidates().elementAtOrNull(position) ?: return@setOnItemClickListener
            val device = candidate.device
            selectedDevice = device
            selectedCandidate = candidate
            binding.deviceNameInput.setText(candidate.savedLabel)
            configStore.save(
                configStore.load().copy(
                    deviceName = candidate.displayName,
                    deviceAddress = device.address,
                )
            )
            showStatus("Seleccionado: ${candidate.displayLabel}. Ahora toca conectar.")
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
                showStatus("Primero escanea BLE y selecciona una MAC de la lista.")
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
        mainHandler.removeCallbacks(serviceTimeout)
        stopBleScan()
        stopRegulaBleService()
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
        selectedCandidate = null
        discoveredDevices.clear()
        devicesAdapter.clear()
        devicesAdapter.notifyDataSetChanged()
        addBondedDevices(adapter)
        showStatus("Buscando emparejados y BLE cercanos. Si no hay nombre, usa la MAC con mejor señal.")
        isScanning = true
        ensureBleManager()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(null, settings, nativeScanCallback)
        bleManager?.startDeviceScan(regulaScanCallback)
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, 8_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        runCatching { bleScanner?.stopScan(nativeScanCallback) }
        runCatching { bleManager?.stopDeviceScan() }
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedDevice() {
        val candidate = selectedCandidate ?: selectedDevice?.let { BleCandidate(it, Int.MIN_VALUE) } ?: return
        stopBleScan()

        ensureBleManager()

        pendingCandidate = candidate
        connectionAttempts = buildConnectionAttempts(candidate)
        connectionAttemptIndex = 0
        if (candidate.hasRealName) {
            connectWithRegulaService(candidate)
        } else {
            tryConnectionAttempt("Inicio")
        }
    }

    private fun ensureBleManager() {
        if (bleManager != null) return
        bleManager = BLEWrapper(this, bleCallbacks)
        bleManager?.initializeBleManager()
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectionAttempt(reason: String) {
        mainHandler.post {
            val candidate = pendingCandidate ?: return@post
            val method = connectionAttempts.getOrNull(connectionAttemptIndex)
            if (method == null) {
                dismissDialog()
                bleManager?.disconnect()
                showStatus("No se pudo conectar al Regula 7310. Ultimo paso: $reason. Prueba el otro HF o revisa que la app oficial no tenga tomado el Bluetooth.")
                return@post
            }

            runCatching { bleManager?.disconnect() }
            showDialog("Conectando ${candidate.displayName}")
            mainHandler.removeCallbacks(connectTimeout)
            mainHandler.postDelayed(connectTimeout, 15_000)
            showStatus("Intento ${connectionAttemptIndex + 1}/${connectionAttempts.size}: ${method.label} con ${candidate.displayLabel}")

            when (method) {
                ConnectionMethod.NAME -> bleManager?.connect(candidate.displayName)
                ConnectionMethod.DEVICE -> bleManager?.connect(candidate.device)
                ConnectionMethod.UUID -> bleManager?.connectUUID(candidate.device.address)
                ConnectionMethod.AUTO -> bleManager?.connect()
            }
        }
    }

    private fun tryNextConnectionMethod(reason: String) {
        mainHandler.post {
            mainHandler.removeCallbacks(connectTimeout)
            connectionAttemptIndex += 1
            tryConnectionAttempt(reason)
        }
    }

    private fun buildConnectionAttempts(candidate: BleCandidate): List<ConnectionMethod> {
        val attempts = mutableListOf<ConnectionMethod>()
        if (candidate.hasRealName) attempts += ConnectionMethod.NAME
        attempts += ConnectionMethod.DEVICE
        attempts += ConnectionMethod.UUID
        attempts += ConnectionMethod.AUTO
        return attempts.distinct()
    }

    private fun connectWithRegulaService(candidate: BleCandidate) {
        stopRegulaBleService()
        DocumentReader.Instance().functionality().edit().setBtDeviceName(candidate.displayName).apply()
        showDialog("Conectando ${candidate.displayName}")
        showStatus("Conectando con servicio oficial Regula: ${candidate.displayName}")
        val bleIntent = Intent(this, RegulaBleService::class.java).apply {
            putExtra(RegulaBleService.DEVICE_NAME, candidate.displayName)
        }
        startService(bleIntent)
        bindService(bleIntent, regulaBleConnection, BIND_AUTO_CREATE)
        mainHandler.removeCallbacks(serviceTimeout)
        mainHandler.postDelayed(serviceTimeout, 12_000)
    }

    private fun stopRegulaBleService() {
        mainHandler.removeCallbacks(serviceTimeout)
        if (isBleServiceConnected) {
            runCatching { unbindService(regulaBleConnection) }
            isBleServiceConnected = false
        }
        runCatching { stopService(Intent(this, RegulaBleService::class.java)) }
    }

    private val regulaBleConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            isBleServiceConnected = true
            val bleService = (service as RegulaBleService.LocalBinder).service
            bleManager = bleService.bleManager
            bleManager?.addCallback(bleCallbacks)
            if (bleManager?.isConnected == true || bleManager?.isDeviceReady == true) {
                mainHandler.removeCallbacks(serviceTimeout)
                prepareDatabaseAndInitialize()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBleServiceConnected = false
        }
    }

    private val bleCallbacks = object : BleManagerCallback {
        override fun onDeviceSearching() = Unit

        override fun onDeviceStopSearching() = Unit

        override fun onDeviceConnecting(device: BluetoothDevice) {
            showStatus("Conectando con ${deviceLabel(device)}...")
        }

        override fun onDeviceConnected(device: BluetoothDevice) {
            showStatus("Conectado a ${deviceLabel(device)}. Esperando autenticador...")
        }

        override fun onDeviceReady() {
            mainHandler.removeCallbacks(connectTimeout)
            showStatus("Autenticador listo. Inicializando DocumentReader...")
            prepareDatabaseAndInitialize()
        }

        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit

        override fun onDeviceDisconnected(device: BluetoothDevice) {
            showStatus("Desconectado: ${deviceLabel(device)}")
        }

        override fun onLinkLossOccurred(device: BluetoothDevice) {
            showStatus("Se perdió la conexión con ${deviceLabel(device)}")
        }

        override fun onServicesDiscovered(device: BluetoothDevice) {
            showStatus("Servicios BLE detectados en ${deviceLabel(device)}. Preparando autenticador...")
        }

        override fun onError(device: BluetoothDevice?, message: String?, code: Int) {
            mainHandler.removeCallbacks(connectTimeout)
            tryNextConnectionMethod("Error Bluetooth ($code): ${message ?: "sin detalle"}")
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
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { addScanResult(it) }
        }
    }

    @SuppressLint("MissingPermission")
    private val regulaScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, _ ->
        val current = discoveredDevices[device.address]
        val knownName = current?.knownName ?: device.name?.trim().orEmpty().ifBlank { null }
        discoveredDevices[device.address] = BleCandidate(
            device = device,
            rssi = rssi,
            knownName = knownName,
            source = mergeSource(current?.source, "Regula SDK"),
        )
        runOnUiThread {
            renderDevicesList()
        }
    }


    @SuppressLint("MissingPermission")
    private fun addBondedDevices(adapter: BluetoothAdapter) {
        adapter.bondedDevices.orEmpty().forEach { device ->
            val name = device.name?.trim().orEmpty()
            val candidate = BleCandidate(
                device = device,
                rssi = Int.MAX_VALUE,
                knownName = name.ifBlank { null },
                source = "emparejado",
            )
            discoveredDevices[device.address] = candidate
        }
        renderDevicesList()
    }

    @SuppressLint("MissingPermission")
    private fun addScanResult(result: ScanResult) {
        val device = result.device
        val current = discoveredDevices[device.address]
        val scanName = result.scanRecord?.deviceName?.trim().orEmpty()
        val deviceName = device.name?.trim().orEmpty()
        val knownName = current?.knownName
            ?: scanName.ifBlank { null }
            ?: deviceName.ifBlank { null }
        val source = if (current?.source == "emparejado") "emparejado + BLE" else "BLE"
        discoveredDevices[device.address] = BleCandidate(device, result.rssi, knownName, mergeSource(current?.source, source))
        runOnUiThread {
            renderDevicesList()
        }
    }

    private fun renderDevicesList() {
        devicesAdapter.clear()
        sortedCandidates().forEach { devicesAdapter.add(it.displayLabel) }
        devicesAdapter.notifyDataSetChanged()
    }

    private fun sortedCandidates(): List<BleCandidate> =
        discoveredDevices.values.sortedWith(
            compareByDescending<BleCandidate> { it.isBonded }.thenByDescending { it.rssi }
        )

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice): String {
        val name = device.name?.trim().orEmpty()
        return if (name.isBlank()) device.address else "$name (${device.address})"
    }

    private fun savedDeviceLabel(config: AppConfig): String {
        if (config.deviceAddress.isBlank()) return config.deviceName
        return "${config.deviceName} (${config.deviceAddress})"
    }

    private fun mergeSource(current: String?, next: String): String {
        if (current.isNullOrBlank()) return next
        return (current.split(" + ") + next.split(" + "))
            .distinct()
            .joinToString(" + ")
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

    private data class BleCandidate(
        val device: BluetoothDevice,
        val rssi: Int,
        val knownName: String? = null,
        val source: String = "BLE",
    ) {
        val isBonded: Boolean
            get() = source.startsWith("emparejado")

        val hasRealName: Boolean
            get() = displayName != "(sin nombre)"

        val displayName: String
            @SuppressLint("MissingPermission")
            get() = knownName ?: device.name?.trim().orEmpty().ifBlank { "(sin nombre)" }

        val savedLabel: String
            get() = "$displayName (${device.address})"

        val displayLabel: String
            get() {
                val signal = if (rssi == Int.MAX_VALUE) "sin RSSI" else "señal $rssi dBm"
                return "$displayName | ${device.address} | $source | $signal"
            }
    }

    private enum class ConnectionMethod(val label: String) {
        NAME("por nombre"),
        DEVICE("por dispositivo BLE"),
        UUID("por MAC/UUID"),
        AUTO("auto SDK Regula"),
    }
}
