package com.example.hc06

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.content.*
import android.os.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var lvDevices: ListView
    private lateinit var etMessage: EditText
    private lateinit var btnEnableBt: Button
    private lateinit var btnPaired: Button
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var btnSend: Button

    private val deviceList = mutableListOf<BluetoothDevice>()
    private lateinit var adapterList: ArrayAdapter<String>

    private val sppUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var readThread: Thread? = null

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* no-op */ }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!deviceList.any { d -> d.address == it.address }) {
                            deviceList.add(it)
                            adapterList.add("${it.name ?: "(sin nombre)"} - ${it.address}")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    log("Escaneo finalizado.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        lvDevices = findViewById(R.id.lvDevices)
        etMessage = findViewById(R.id.etMessage)
        btnEnableBt = findViewById(R.id.btnEnableBt)
        btnPaired = findViewById(R.id.btnPaired)
        btnScan = findViewById(R.id.btnScan)
        btnConnect = findViewById(R.id.btnConnect)
        btnSend = findViewById(R.id.btnSend)

        adapterList = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvDevices.adapter = adapterList

        // Obtener el adaptador
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        // Registrar receiver para descubrimiento
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)

        btnEnableBt.setOnClickListener { ensureBluetoothEnabled() }
        btnPaired.setOnClickListener { listBonded() }
        btnScan.setOnClickListener { scan() }
        btnConnect.setOnClickListener { connectToHc06() }
        btnSend.setOnClickListener { send(etMessage.text.toString()) }

        lvDevices.setOnItemClickListener { _, _, position, _ ->
            val dev = deviceList[position]
            connect(dev)
        }

        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // Para descubrir dispositivos en Android 6–11 se requiere ubicación
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
        }
    }

    private fun ensureBluetoothEnabled() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(enableBtIntent)
        } else {
            toast("Bluetooth ya está activado")
        }
    }

    private fun listBonded() {
        adapterList.clear()
        deviceList.clear()
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        if (bonded.isEmpty()) {
            log("No hay dispositivos vinculados. Vaya a Ajustes > Bluetooth y empareje con el HC-06 (PIN 1234/0000)")
            return
        }
        bonded.forEach {
            deviceList.add(it)
            adapterList.add("${it.name ?: "(sin nombre)"} - ${it.address}")
        }
        log("Mostrando ${bonded.size} vinculados.")
    }

    private fun scan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Falta permiso BLUETOOTH_SCAN")
            return
        }
        val adapter = bluetoothAdapter ?: return
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapterList.clear(); deviceList.clear()
        adapter.startDiscovery()
        log("Escaneando...")
    }

    private fun connectToHc06() {
        // Intentamos por nombre
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        val dev = bonded.firstOrNull { (it.name ?: "").contains("HC-06", ignoreCase = true) }
        if (dev == null) {
            log("HC-06 no vinculado. Use 'Vinculados' o pulse un dispositivo de la lista tras 'Escanear'.")
        } else connect(dev)
    }

    private fun connect(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Falta permiso BLUETOOTH_CONNECT")
            return
        }
        // Cancelar discovery para acelerar la conexión
        bluetoothAdapter?.cancelDiscovery()

        tvStatus.text = "Estado: Conectando a ${device.name ?: device.address}"
        log("Conectando a ${device.name} (${device.address})...")

        thread(start = true) {
            try {
                val tmp = device.createRfcommSocketToServiceRecord(sppUUID)
                tmp.connect()
                socket = tmp
                runOnUiThread {
                    tvStatus.text = "Estado: Conectado a ${device.name ?: device.address}"
                    toast("Conectado")
                }
                startReading()
            } catch (e: IOException) {
                runOnUiThread {
                    tvStatus.text = "Estado: Error de conexión"
                    log("Error conectando: ${e.message}")
                }
                closeSocket()
            }
        }
    }

    private fun startReading() {
        val s = socket ?: return
        readThread?.interrupt()
        readThread = thread(start = true) {
            val buffer = ByteArray(1024)
            try {
                val input = s.inputStream
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(buffer)
                    if (n > 0) {
                        val text = String(buffer, 0, n)
                        runOnUiThread { log("<- $text") }
                    }
                }
            } catch (e: IOException) {
                runOnUiThread { log("Lectura finalizada: ${e.message}") }
            }
        }
    }

    private fun send(msg: String) {
        if (msg.isEmpty()) return
        val s = socket
        if (s == null || !s.isConnected) {
            toast("No conectado")
            return
        }
        thread(start = true) {
            try {
                s.outputStream.write(msg.toByteArray())
                s.outputStream.flush()
                runOnUiThread { log("-> $msg") }
            } catch (e: IOException) {
                runOnUiThread { log("Error enviando: ${e.message}") }
            }
        }
    }

    private fun log(line: String) {
        tvLog.append(line + "\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        readThread?.interrupt()
        readThread = null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        closeSocket()
    }
}
