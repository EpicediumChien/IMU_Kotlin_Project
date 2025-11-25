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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

// --- Data classes to hold the parsed sensor values ---
data class ImuData(val accX: Float, val accY: Float, val accZ: Float,
                   val gyroX: Float, val gyroY: Float, val gyroZ: Float,
                   val magX: Float, val magY: Float, val magZ: Float)

data class RpyData(val roll: Float, val pitch: Float, val yaw: Float)


class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {
    // --- Flag to manage the permission dialog lifecycle ---
    private var isPermissionRequestPending = false

    // --- Companion Object for Constants ---
    companion object {
        private const val VENDOR_ID = 4292
        private const val PRODUCT_ID = 60000
        private const val BAUD_RATE = 921600 // Baud rate from Python script
        private const val TAG = "IMU_USB"
        private const val ACTION_USB_PERMISSION = "com.example.myfirstkotlinapp.USB_PERMISSION"
        private const val POLLING_INTERVAL_MS = 10000L

        // --- NEW: Constants from the Python script's parsing logic ---
        private const val HEADER_1 = 0xAA.toByte()
        private const val HEADER_2 = 0x55.toByte()
        private const val MSG_ID_IMU = 0x2C.toByte()
        private const val MSG_ID_RPY = 0x14.toByte()
        private const val PACKET_LEN_IMU = 47
        private const val PACKET_LEN_RPY = 23
    }

    // --- USB & Serial Variables ---
    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null

    // --- NEW: Data Buffering and State Management ---
    private val receiveBuffer = ByteArrayOutputStream()
    private var lastImuData: ImuData? = null
    private var lastRpyData: RpyData? = null

    // --- UI Elements ---
    private lateinit var statusTextView: TextView
    private lateinit var dataTextView: TextView

    // --- Handler and Runnable for periodic connection checks ---
    private val connectionCheckHandler = Handler(Looper.getMainLooper())
    private lateinit var connectionCheckRunnable: Runnable

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached (Broadcast)")
                    updateStatus("USB device detected. Checking for IMU...")
                    findAndConnectToIMU()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = getDeviceFromIntent(intent)
                    if (device?.vendorId == VENDOR_ID && device.productId == PRODUCT_ID) {
                        Log.w(TAG, "Our IMU device detached (Broadcast)")
                        updateStatus("IMU disconnected.")
                        disconnect()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    isPermissionRequestPending = false // Reset the flag when the dialog is dismissed
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Log.d(TAG, "USB Permission GRANTED")
                        updateStatus("Permission granted. Connecting...")
                        val device: UsbDevice? = getDeviceFromIntent(intent)
                        device?.let { connect(it) }
                    } else {
                        Log.e(TAG, "USB Permission DENIED")
                        updateStatus("Error: USB permission denied.")
                    }
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        dataTextView = findViewById(R.id.dataTextView)
        dataTextView.movementMethod = ScrollingMovementMethod()

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        Log.d(TAG, "Activity created and receiver registered.")
        initializeConnectionCheckRunnable()
    }

    // --- MODIFIED: onNewData now uses the robust packet parser ---
    override fun onNewData(data: ByteArray) {
        // --- ADD THIS LOG LINE ---
        Log.d(TAG, "Read ${data.size} bytes: ${data.joinToString { "%02x".format(it) }}")
        // -------------------------

        // Add incoming data to our buffer
        receiveBuffer.write(data)
        // Process the buffer to find and parse complete packets
        processReceiveBuffer()
    }

    private fun processReceiveBuffer() {
        val buffer = receiveBuffer.toByteArray()
        var searchIndex = 0

        while (searchIndex < buffer.size - 1) {
            // Find the start of a packet
            if (buffer[searchIndex] == HEADER_1 && buffer[searchIndex + 1] == HEADER_2) {
                // Found a potential packet header. Check the message ID.
                if (searchIndex + 2 < buffer.size) {
                    val msgId = buffer[searchIndex + 2]
                    val packetLen = if (msgId == MSG_ID_IMU) PACKET_LEN_IMU else if (msgId == MSG_ID_RPY) PACKET_LEN_RPY else 0

                    if (packetLen > 0) {
                        // We have a known packet type. Check if we have the full packet in our buffer.
                        if (searchIndex + packetLen <= buffer.size) {
                            // We have a full packet! Let's parse it.
                            val packetBytes = buffer.copyOfRange(searchIndex, searchIndex + packetLen)
                            parsePacket(packetBytes)

                            // Move the search index past this packet
                            searchIndex += packetLen
                            continue // Continue the loop to look for more packets
                        } else {
                            // Not enough data for a full packet yet, break and wait for more data
                            break
                        }
                    }
                }
            }
            // If we're here, we didn't find a valid packet, so move to the next byte
            searchIndex++
        }

        // Clean up the buffer: remove the bytes we've already processed
        if (searchIndex > 0) {
            val remainingBytes = buffer.copyOfRange(searchIndex, buffer.size)
            receiveBuffer.reset()
            receiveBuffer.write(remainingBytes)
        }
    }

    private fun parsePacket(packet: ByteArray) {
        val msgId = packet[2]
        try {
            when (msgId) {
                MSG_ID_IMU -> {
                    // Use ByteBuffer to handle Little-Endian float conversion
                    val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                    // Note the order from the python script: gyro, accel, mag
                    val gx = bb.getFloat(3)
                    val gy = bb.getFloat(7)
                    val gz = bb.getFloat(11)
                    val ax = bb.getFloat(15)
                    val ay = bb.getFloat(19)
                    val az = bb.getFloat(23)
                    val mx = bb.getFloat(27)
                    val my = bb.getFloat(31)
                    val mz = bb.getFloat(35)
                    lastImuData = ImuData(ax, ay, az, gx, gy, gz, mx, my, mz)
                }
                MSG_ID_RPY -> {
                    val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                    val roll = bb.getFloat(11)
                    val pitch = bb.getFloat(15)
                    val yaw = bb.getFloat(19)
                    lastRpyData = RpyData(roll, pitch, yaw)
                }
            }

            // Once we have both packets, update the UI and reset
            if (lastImuData != null && lastRpyData != null) {
                updateUI(lastImuData!!, lastRpyData!!)
                lastImuData = null
                lastRpyData = null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse packet: ${e.message}")
        }
    }

    private fun updateUI(imu: ImuData, rpy: RpyData) {
        val outputText = """
        |Accel (g):    x=${"%.2f".format(imu.accX)}, y=${"%.2f".format(imu.accY)}, z=${"%.2f".format(imu.accZ)}
        |Gyro (°/s):   x=${"%.2f".format(imu.gyroX)}, y=${"%.2f".format(imu.gyroY)}, z=${"%.2f".format(imu.gyroZ)}
        |Mag (uT):     x=${"%.2f".format(imu.magX)}, y=${"%.2f".format(imu.magY)}, z=${"%.2f".format(imu.magZ)}
        |RPY (°):      Roll=${"%.2f".format(rpy.roll)}, Pitch=${"%.2f".format(rpy.pitch)}, Yaw=${"%.2f".format(rpy.yaw)}
        |------------------------------------
        |
        """.trimMargin()

        runOnUiThread {
            dataTextView.text = outputText // Replace text instead of appending for a cleaner look
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial IO Manager error: ${e.message}", e)
        runOnUiThread {
            updateStatus("Error: Connection lost. ${e.message}")
            disconnect()
        }
    }

    // --- The rest of the file is unchanged. All functions below this line are the same as before. ---

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
                Log.d(TAG, "Polling: Searching for IMU device...")
                findAndConnectToIMU()
            }
            connectionCheckHandler.postDelayed(connectionCheckRunnable, POLLING_INTERVAL_MS)
        }
    }

    private fun findAndConnectToIMU() {
        if (serialPort != null && serialPort!!.isOpen) {
            Log.d(TAG, "Already connected.")
            return
        }
        updateStatus("Searching for IMU...")
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            updateStatus("No USB serial devices found. Will check again later.")
            return
        }
        val imuDriver = availableDrivers.find { it.device.vendorId == VENDOR_ID && it.device.productId == PRODUCT_ID }
        if (imuDriver == null) {
            updateStatus("IMU not found (VID:$VENDOR_ID, PID:$PRODUCT_ID). Will check again later.")
            Log.w(TAG, "IMU driver not found.")
            return
        }
        val device = imuDriver.device
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Permission already granted, connecting.")
            connect(device)
        } else {
            Log.w(TAG, "Permission not granted, requesting...")
            updateStatus("Requesting USB permission...")
            isPermissionRequestPending = true
            val flags = PendingIntent.FLAG_IMMUTABLE
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connect(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            updateStatus("Error: No driver for device.")
            return
        }
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            updateStatus("Error: Could not open connection.")
            if (!usbManager.hasPermission(device)) {
                val flags = PendingIntent.FLAG_IMMUTABLE
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                usbManager.requestPermission(device, permissionIntent)
            }
            return
        }
        serialPort = driver.ports[0]
        try {
            serialPort?.open(connection)
            serialPort?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // --- ADD THESE TWO LINES HERE ---
            serialPort?.dtr = true
            serialPort?.rts = true
            // --------------------------------

            serialIoManager = SerialInputOutputManager(serialPort, this)
            Executors.newSingleThreadExecutor().submit(serialIoManager)
            updateStatus("Connected!")
            Log.i(TAG, "Serial port opened successfully!")
        } catch (e: IOException) {
            Log.e(TAG, "Error opening serial port: ${e.message}", e)
            updateStatus("Error: ${e.message}")
            disconnect()
        }
    }

    private fun disconnect() {
        if (serialIoManager == null && serialPort == null) return
        Log.i(TAG, "Disconnecting...")
        updateStatus("Disconnected")
        try {
            serialIoManager?.stop()
            serialPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing port: ${e.message}", e)
        } finally {
            serialIoManager = null
            serialPort = null
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusTextView.text = getString(R.string.statusText, message)
        }
    }

    private fun getDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}