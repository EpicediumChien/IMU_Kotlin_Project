package com.example.myfirstkotlinapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private val ACTION_USB_PERMISSION = "com.example.myfristkotlinapp.USB_PERMISSION"

    // This receiver is now correctly structured to handle the permission result
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                // Check if the user granted permission
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    Log.d("USB", "USB Permission GRANTED")
                    // Permission granted, now we can get the device and connect
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let {
                        openSerialPort(it) // Call the connection function
                    }
                } else {
                    Log.d("USB", "USB Permission DENIED")
                    // Handle the case where the user denies permission
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // It's good practice to have a layout

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Register the receiver with the required export flag
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        Log.d("USB", "Registering usbReceiver...")
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        // Try to connect when the app resumes
        findAndConnectToIMU()
    }

    private fun findAndConnectToIMU() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Log.e("IMU", "No USB serial device found")
            return
        }

        // Let's connect to the first available driver
        val driver = availableDrivers[0]
        val device = driver.device

        if (usbManager.hasPermission(device)) {
            // Permission already granted, we can connect directly
            Log.d("IMU", "Permission already granted, opening port.")
            openSerialPort(device)
        } else {
            // Permission not granted yet, request it
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            Log.w("IMU", "Permission not granted, requesting...")
        }
    }

    private fun openSerialPort(device: UsbDevice) {
        // Find the correct driver for the given device
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            Log.e("IMU", "No driver found for the device.")
            return
        }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            Log.e("IMU", "Failed to open USB device connection")
            return
        }

        // Use the first port
        serialPort = driver.ports[0]
        try {
            serialPort?.open(connection)
            serialPort?.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Log.i("IMU", "Serial port opened successfully!")
            // You can now start reading/writing data from/to the serialPort
        } catch (e: Exception) {
            Log.e("IMU", "Error opening serial port: ${e.message}", e)
            serialPort?.close() // Ensure port is closed on failure
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // It's important to unregister the receiver and close the port
        unregisterReceiver(usbReceiver)
        try {
            serialPort?.close()
            Log.i("IMU", "Serial port closed.")
        } catch (e: Exception) {
            Log.e("IMU", "Error closing serial port: ${e.message}", e)
        }
    }
}