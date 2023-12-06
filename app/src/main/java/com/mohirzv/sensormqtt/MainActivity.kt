package com.mohirzv.sensormqtt

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.auth.CognitoCredentialsProvider
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttLastWillAndTestament
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.iot.AWSIotClient
import com.mohirzv.sensormqtt.databinding.ActivityMainBinding
import java.lang.Exception
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mqttManager: AWSIotMqttManager
    private var mqttConnected = false
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private lateinit var locationManager: LocationManager

    companion object {
        const val COGNITO_POOL_ID = "eu-north-1:<REPLACE_WITH_REAL_ID>"
        const val CUSTOMER_SPECIFIC_ENDPOINT = "<REPLACE_WITH_REAL_ID>.iot.eu-north-1.amazonaws.com"
        const val TOPIC = "sensorData"
        val MY_REGION = Regions.EU_NORTH_1
        const val TAG = "Mohi"
        const val REQUEST_PERMISSIONS_CODE = 7777
    }

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Not used in this example
        }

        override fun onSensorChanged(event: SensorEvent?) {
            when (event?.sensor?.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    binding.tvAcc.text = "$x, $y, $z"
                    if (mqttConnected) {
                        mqttManager.publishString("Acc, $x, $y, $z", TOPIC, AWSIotMqttQos.QOS0)
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    binding.tvGyro.text = "$x, $y, $z"
                    if (mqttConnected) {
                        mqttManager.publishString("Gyro, $x, $y, $z", TOPIC, AWSIotMqttQos.QOS0)
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    binding.tvMagneto.text = "$x, $y, $z"
                    if (mqttConnected) {
                        mqttManager.publishString("Magneto, $x, $y, $z", TOPIC, AWSIotMqttQos.QOS0)
                    }
                }
                else -> {}
            }
        }
    }

    private val locationListener = LocationListener {
        val lat = it.latitude
        val lon = it.longitude
        val alt = it.altitude
        binding.tvGps.text = "$lat, $lon"
        if (mqttConnected) {
            mqttManager.publishString("GPS, $lat, $lon", TOPIC, AWSIotMqttQos.QOS0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        /* Check if Internet connection is available */
        if (!isConnectedToInternet()) {
            Log.d(TAG, "Internet connection NOT available")
            Toast.makeText(applicationContext, "Internet connection NOT available", Toast.LENGTH_LONG).show()
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        checkAndRequestPermissions()
        setupSensors()
        setupMQTT()
    }

    private fun setupMQTT() {
        // MQTT client IDs are required to be unique per AWS IoT account.
        // This UUID is "practically unique" but does not _guarantee_
        // uniqueness.
        val clientId = UUID.randomUUID().toString()

        val credentialsProvider = CognitoCachingCredentialsProvider(
            applicationContext,
            COGNITO_POOL_ID,
            MY_REGION
        )

        mqttManager = AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT)
        mqttManager.keepAlive = 10

        try {
            mqttManager.connect(credentialsProvider, AWSIotMqttClientStatusCallback {
                    status, throwable ->
                Log.d(TAG, "Status = $status")
                when(status) {
                    AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                        try {
                            mqttManager.publishString("Test messg Android Mh", TOPIC, AWSIotMqttQos.QOS0)
                        } catch (e: Exception) {
                            Log.e(TAG, "Publish error: $e")
                        }
                        mqttConnected = true
                    }
                    else -> {
                        mqttConnected = false
                    }
                }
            }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: $e")
        }
    }

    private fun startListening() {
        try {
            // Request location updates with a minimum time interval and minimum distance change
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000, // 1 second
                1f,   // 1 meter
                locationListener
            )
        } catch (e: SecurityException) {
            Log.e("LocationListener", "Error: ${e.message}")
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accelerometer == null) {
            Log.e(TAG, "Accelerometer sensor not available on this device.")
        }
        if (gyroscope == null) {
            Log.e(TAG, "Gyroscope sensor not available on this device.")
        }
        if (magnetometer == null) {
            Log.e(TAG, "Magnetometer sensor not available on this device.")
        }
    }

    private fun isConnectedToInternet(): Boolean {
        var result = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        if (capabilities != null) {
            result = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> true
                else -> false
            }
        }
        return result
    }

    private fun checkAndRequestPermissions() {
        val permissionLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)

        val neededPermissions = mutableListOf<String>()
        if (permissionLocation != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (neededPermissions.isNotEmpty()) {
            requestPermissions(neededPermissions.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val perms: MutableMap<String, Int> = HashMap()
            // Initialize the map with both permissions
            perms[android.Manifest.permission.ACCESS_FINE_LOCATION] = PackageManager.PERMISSION_GRANTED
            // Fill with actual results from user
            if (grantResults.isNotEmpty()) {
                for (i in permissions.indices) {
                    perms[permissions[i]] = grantResults[i]
                }
                // Check for both permissions
                if (perms[android.Manifest.permission.ACCESS_FINE_LOCATION] == PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "location services permission granted")
                    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
                    startListening()
                } else {
                    Toast.makeText(
                        this,
                        "Location permission is required!",
                        Toast.LENGTH_LONG
                    ).show()
                    //finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startListening()
        accelerometer?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        magnetometer?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        gyroscope?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(sensorListener)
    }

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        mqttManager.disconnect()
        super.onDestroy()
    }
}