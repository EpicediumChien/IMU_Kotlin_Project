package com.example.myfirstkotlinapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import com.example.myfirstkotlinapp.viewmodel.ImuRecord
import androidx.lifecycle.ViewModelProvider // Add this

// --- Data classes ---
data class ImuData(val accX: Float, val accY: Float, val accZ: Float,
                   val gyroX: Float, val gyroY: Float, val gyroZ: Float,
                   val magX: Float, val magY: Float, val magZ: Float)

data class RpyData(val roll: Float, val pitch: Float, val yaw: Float)

enum class ImuType {
    HF,
    YAHBOOM
}

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private var currentImuType = ImuType.HF

    // Buffer to store data
    private val imuDataBuffer = Collections.synchronizedList(ArrayList<ImuRecord>())

    // --- NEW: Coroutine Job to handle the timer ---
    private var csvJob: Job? = null

    companion object {
        private const val VENDOR_ID = 4292
        private const val PRODUCT_ID = 60000
        private const val BAUD_RATE = 9600
        private const val TAG = "IMU_USB"
        private const val ACTION_USB_PERMISSION = "com.example.myfirstkotlinapp.USB_PERMISSION"
        private const val POLLING_INTERVAL_MS = 10000L

        // --- CSV CONFIG: 1 Minutes ---
        private const val CSV_SAVE_INTERVAL_MS = 1 * 60 * 1000L

        // --- Protocol Constants ---
        private const val HF_HEADER_1 = 0xAA.toByte()
        private const val HF_HEADER_2 = 0x55.toByte()
        private const val HF_MSG_ID_IMU = 0x2C.toByte()
        private const val HF_MSG_ID_RPY = 0x14.toByte()
        private const val HF_PACKET_LEN_IMU = 47
        private const val HF_PACKET_LEN_RPY = 23

        private const val YAHBOOM_HEADER = 0x55.toByte()
        private const val YAHBOOM_PACKET_LEN = 11
        private const val YAHBOOM_TYPE_ACC = 0x51.toByte()
        private const val YAHBOOM_TYPE_GYRO = 0x52.toByte()
        private const val YAHBOOM_TYPE_ANGLE = 0x53.toByte()
        private const val YAHBOOM_TYPE_MAG = 0x54.toByte() // Add this
    }

    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null

    private val receiveBuffer = ByteArrayOutputStream()
    private var lastImuData: ImuData? = null
    private var lastRpyData: RpyData? = null
    private var isPermissionRequestPending = false

    // Temporary storage for Yahboom
    private var yhAcc = FloatArray(3)
    private var yhGyro = FloatArray(3)
    private var yhAngle = FloatArray(3)
    private var yhMag = FloatArray(3) // Add this to your class properties

    private lateinit var statusTextView: TextView
    private lateinit var dataTextView: TextView
    private lateinit var imuTypeSpinner: Spinner

    private val connectionCheckHandler = Handler(Looper.getMainLooper())
    private lateinit var connectionCheckRunnable: Runnable

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    updateStatus("USB device detected. Checking...")
                    findAndConnectToIMU()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = getDeviceFromIntent(intent)
                    if (device?.vendorId == VENDOR_ID && device.productId == PRODUCT_ID) {
                        disconnect()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    isPermissionRequestPending = false
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        val device: UsbDevice? = getDeviceFromIntent(intent)
                        device?.let { connect(it) }
                    } else {
                        updateStatus("Error: Permission denied.")
                    }
                }
            }
        }
    }

    // --- NEW: Add ViewModel Reference ---
    private lateinit var csvUploadViewModel: CsvUploadViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        dataTextView = findViewById(R.id.dataTextView)
        imuTypeSpinner = findViewById(R.id.imuTypeSpinner)

        dataTextView.movementMethod = ScrollingMovementMethod()

        setupSpinner()

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        initializeConnectionCheckRunnable()

        // Initial start of CSV logic for the default selection
        startCsvRecordingSession()

        updateStatus("Ready. Select IMU type.")

        // --- NEW: Initialize the ViewModel ---
        csvUploadViewModel = ViewModelProvider(this)[CsvUploadViewModel::class.java]
    }

    // --- NEW: Save to Temp Folder (cacheDir) ---
    private suspend fun saveBufferToCsvFile(data: List<ImuRecord>) {
        withContext(Dispatchers.IO) {
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "imu_${currentImuType}_$timeStamp.csv"

                // --- CHANGED: Uses cacheDir (App Installation Temp Folder) ---
                // If you want it accessible via PC easily, use: externalCacheDir
                val dir = cacheDir
                if (!dir.exists()) dir.mkdirs()

                val file = File(dir, fileName)

                val writer = FileWriter(file)
                writer.append("Timestamp,AccX,AccY,AccZ,GyroX,GyroY,GyroZ,MagX,MagY,MagZ,Roll,Pitch,Yaw\n")

                for (record in data) {
                    writer.append(record.toCsvString()).append("\n")
                }

                writer.flush()
                writer.close()

                Log.i(TAG, "Saved CSV: ${file.absolutePath} (${data.size} records)")

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Saved 5min Data to Temp: $fileName", Toast.LENGTH_LONG).show()
                }

            } catch (e: IOException) {
                Log.e(TAG, "Error saving CSV", e)
            }
        }
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ImuType.entries.toTypedArray()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        imuTypeSpinner.adapter = adapter

        imuTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedType = ImuType.entries[position]
                if (currentImuType != selectedType) {
                    currentImuType = selectedType

                    // Reset connection buffers
                    receiveBuffer.reset()
                    lastImuData = null
                    lastRpyData = null
                    yhAcc = FloatArray(3)
                    yhGyro = FloatArray(3)
                    yhAngle = FloatArray(3)

                    updateUI(null, null)

                    // --- NEW: Reset the CSV Timer immediately upon selection ---
                    startCsvRecordingSession()

                    Log.i(TAG, "Switched IMU Type to: $currentImuType. CSV Timer Reset.")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    override fun onNewData(data: ByteArray) {
        receiveBuffer.write(data)
        if (currentImuType == ImuType.HF) {
            processHFBuffer()
        } else {
            processYahboomBuffer()
        }
    }

    // ... (Rest of your Protocol Parsing logic remains exactly the same) ...

    private fun processHFBuffer() {
        val buffer = receiveBuffer.toByteArray()
        var searchIndex = 0
        while (searchIndex < buffer.size - 1) {
            if (buffer[searchIndex] == HF_HEADER_1 && buffer[searchIndex + 1] == HF_HEADER_2) {
                if (searchIndex + 2 < buffer.size) {
                    val msgId = buffer[searchIndex + 2]
                    val packetLen = if (msgId == HF_MSG_ID_IMU) HF_PACKET_LEN_IMU else if (msgId == HF_MSG_ID_RPY) HF_PACKET_LEN_RPY else 0
                    if (packetLen > 0) {
                        if (searchIndex + packetLen <= buffer.size) {
                            val packetBytes = buffer.copyOfRange(searchIndex, searchIndex + packetLen)
                            parseHFPacket(packetBytes)
                            searchIndex += packetLen
                            continue
                        } else {
                            break
                        }
                    }
                }
            }
            searchIndex++
        }
        cleanBuffer(searchIndex, buffer)
    }

    private fun parseHFPacket(packet: ByteArray) {
        val msgId = packet[2]
        var isNewData = false
        try {
            val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            if (msgId == HF_MSG_ID_IMU) {
                val gx = bb.getFloat(3); val gy = bb.getFloat(7); val gz = bb.getFloat(11)
                val ax = bb.getFloat(15); val ay = bb.getFloat(19); val az = bb.getFloat(23)
                //val mx = bb.getFloat(27); val my = bb.getFloat(31); val mz = bb.getFloat(35)
                val mx = 0f; val my = 0f; val mz = 0f;
                lastImuData = ImuData(ax, ay, az, gx, gy, gz, mx, my, mz)
                isNewData = true
            } else if (msgId == HF_MSG_ID_RPY) {
                val roll = bb.getFloat(11)
                val pitch = bb.getFloat(15)
                val yaw = bb.getFloat(19)
                lastRpyData = RpyData(roll, pitch, yaw)
                isNewData = true
            }
            if (isNewData) updateUI(lastImuData, lastRpyData)
        } catch (e: Exception) {
            Log.e(TAG, "HF Parse Error: ${e.message}")
        }
    }

    private fun processYahboomBuffer() {
        val buffer = receiveBuffer.toByteArray()
        var searchIndex = 0
        while (searchIndex < buffer.size) {
            if (buffer[searchIndex] == YAHBOOM_HEADER) {
                if (searchIndex + YAHBOOM_PACKET_LEN <= buffer.size) {
                    val packetBytes = buffer.copyOfRange(searchIndex, searchIndex + YAHBOOM_PACKET_LEN)
                    if (verifyYahboomChecksum(packetBytes)) {
                        parseYahboomPacket(packetBytes)
                        searchIndex += YAHBOOM_PACKET_LEN
                        continue
                    }
                } else {
                    break
                }
            }
            searchIndex++
        }
        cleanBuffer(searchIndex, buffer)
    }

    private fun verifyYahboomChecksum(packet: ByteArray): Boolean {
        var sum = 0
        for (i in 0 until 10) {
            sum += (packet[i].toInt() and 0xFF)
        }
        return (sum.toByte() == packet[10])
    }

    private fun parseYahboomPacket(packet: ByteArray) {
        val type = packet[1]
        fun getShort(offset: Int): Short {
            val low = packet[offset].toInt() and 0xFF
            val high = packet[offset+1].toInt() and 0xFF
            return (high shl 8 or low).toShort()
        }
        try {
            var uiUpdateNeeded = false
            when (type) {
                YAHBOOM_TYPE_ACC -> {
                    yhAcc[0] = getShort(2) / 32768.0f * 16.0f
                    yhAcc[1] = getShort(4) / 32768.0f * 16.0f
                    yhAcc[2] = getShort(6) / 32768.0f * 16.0f
                }
                YAHBOOM_TYPE_GYRO -> {
                    yhGyro[0] = getShort(2) / 32768.0f * 2000.0f
                    yhGyro[1] = getShort(4) / 32768.0f * 2000.0f
                    yhGyro[2] = getShort(6) / 32768.0f * 2000.0f
                    lastImuData = ImuData(yhAcc[0], yhAcc[1], yhAcc[2],
                        yhGyro[0], yhGyro[1], yhGyro[2],
                        yhMag[0], yhMag[1], yhMag[2])
                    uiUpdateNeeded = true
                }
                // --- NEW CODE START ---
                YAHBOOM_TYPE_MAG -> {
                    // Standard IMU Mag data handling (Units: uT usually)
                    yhMag[0] = getShort(2).toFloat() // Often requires specific scaling depending on sensor
                    yhMag[1] = getShort(4).toFloat()
                    yhMag[2] = getShort(6).toFloat()

                    lastImuData = ImuData(yhAcc[0], yhAcc[1], yhAcc[2],
                        yhGyro[0], yhGyro[1], yhGyro[2],
                        0f, 0f, 0f) // Force Mag to 0
                    uiUpdateNeeded = true
                }
                YAHBOOM_TYPE_ANGLE -> {
                    yhAngle[0] = getShort(2) / 32768.0f * 180.0f
                    yhAngle[1] = getShort(4) / 32768.0f * 180.0f
                    yhAngle[2] = getShort(6) / 32768.0f * 180.0f
                    lastRpyData = RpyData(yhAngle[0], yhAngle[1], yhAngle[2])
                    uiUpdateNeeded = true
                }
            }
            if (uiUpdateNeeded) updateUI(lastImuData, lastRpyData)
        } catch (e: Exception) {
            Log.e(TAG, "Yahboom Parse Error: ${e.message}")
        }
    }

    private fun cleanBuffer(processCount: Int, buffer: ByteArray) {
        if (processCount > 0) {
            val remaining = buffer.copyOfRange(processCount, buffer.size)
            receiveBuffer.reset()
            receiveBuffer.write(remaining)
        }
    }

    private fun updateUI(imu: ImuData?, rpy: RpyData?) {
        // Collect data for CSV
        if (imu != null && rpy != null) {
            val record = ImuRecord(
                timestamp = System.currentTimeMillis(),
                accX = imu.accX, accY = imu.accY, accZ = imu.accZ,
                gyroX = imu.gyroX, gyroY = imu.gyroY, gyroZ = imu.gyroZ,
                magX = imu.magX, magY = imu.magY, magZ = imu.magZ,
                roll = rpy.roll, pitch = rpy.pitch, yaw = rpy.yaw
            )
            imuDataBuffer.add(record)
        }

        val accStr = if (imu != null) "x=${"%.2f".format(imu.accX)}, y=${"%.2f".format(imu.accY)}, z=${"%.2f".format(imu.accZ)}" else "..."
        val gyroStr = if (imu != null) "x=${"%.2f".format(imu.gyroX)}, y=${"%.2f".format(imu.gyroY)}, z=${"%.2f".format(imu.gyroZ)}" else "..."
        val magStr = if (imu != null) "x=${"%.2f".format(imu.magX)}, y=${"%.2f".format(imu.magY)}, z=${"%.2f".format(imu.magZ)}" else "..."
        val rpyStr = if (rpy != null) "R=${"%.2f".format(rpy.roll)}, P=${"%.2f".format(rpy.pitch)}, Y=${"%.2f".format(rpy.yaw)}" else "..."

        val outputText = """
        |Mode:         ${currentImuType.name}
        |Buffered:     ${imuDataBuffer.size}
        |Accel (g):    $accStr
        |Gyro (°/s):   $gyroStr
        |Mag (uT):     $magStr
        |RPY (°):      $rpyStr
        |------------------------------------
        |
        """.trimMargin()

        runOnUiThread {
            dataTextView.text = outputText
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial Error: ${e.message}")
        runOnUiThread { disconnect() }
    }

    override fun onResume() {
        super.onResume()
        connectionCheckHandler.post(connectionCheckRunnable)
    }

    override fun onPause() {
        super.onPause()
        connectionCheckHandler.removeCallbacks(connectionCheckRunnable)
        if (!isChangingConfigurations && !isPermissionRequestPending) {
            disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnect()
    }

    private fun initializeConnectionCheckRunnable() {
        connectionCheckRunnable = Runnable {
            if (serialPort == null || !serialPort!!.isOpen) {
                findAndConnectToIMU()
            }
            connectionCheckHandler.postDelayed(connectionCheckRunnable, POLLING_INTERVAL_MS)
        }
    }

    private fun findAndConnectToIMU() {
        if (serialPort != null && serialPort!!.isOpen) return
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) return

        val imuDriver = availableDrivers.find { it.device.vendorId == VENDOR_ID && it.device.productId == PRODUCT_ID }
        if (imuDriver == null) return

        val device = imuDriver.device
        if (usbManager.hasPermission(device)) {
            connect(device)
        } else {
            if (!isPermissionRequestPending) {
                isPermissionRequestPending = true
                val flags = PendingIntent.FLAG_IMMUTABLE
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    private fun connect(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return
        val connection = usbManager.openDevice(driver.device) ?: return

        serialPort = driver.ports[0]
        try {
            serialPort?.open(connection)
            serialPort?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort?.dtr = true
            serialPort?.rts = true
            serialIoManager = SerialInputOutputManager(serialPort, this)
            Executors.newSingleThreadExecutor().submit(serialIoManager)
            updateStatus("Connected! (${currentImuType.name})")
        } catch (e: IOException) {
            Log.e(TAG, "Error opening port: ${e.message}")
            disconnect()
        }
    }

    private fun disconnect() {
        try {
            serialIoManager?.stop()
            serialPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error opening port: ${e.message}")
        } finally {
            serialIoManager = null
            serialPort = null
            updateStatus("Disconnected")
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { statusTextView.text = message }
    }

    private fun getDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    // --- MODIFIED: The Timer Logic ---
    private fun startCsvRecordingSession() {
        csvJob?.cancel()

        synchronized(imuDataBuffer) {
            imuDataBuffer.clear()
        }

        Log.i(TAG, "CSV Logging Started: Waiting $CSV_SAVE_INTERVAL_MS ms")

        csvJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                // 1. Wait for 5 minutes
                delay(CSV_SAVE_INTERVAL_MS)

                // 2. Extract data safely
                val dataToProcess: List<ImuRecord> = synchronized(imuDataBuffer) {
                    if (imuDataBuffer.isEmpty()) {
                        emptyList()
                    } else {
                        val copy = ArrayList(imuDataBuffer)
                        imuDataBuffer.clear()
                        copy
                    }
                }

                if (dataToProcess.isNotEmpty()) {
                    // Action A: Save to Local Storage (Keep your backup)
                    saveBufferToCsvFile(dataToProcess)

                    // Action B: Upload to AWS S3 (NEW)
                    Log.i(TAG, "Triggering S3 Upload for ${dataToProcess.size} records...")
                    csvUploadViewModel.uploadImuData(dataToProcess)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Saving Local & Uploading to S3...", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.d(TAG, "Timer: No data to save/upload.")
                }
            }
        }
    }
}