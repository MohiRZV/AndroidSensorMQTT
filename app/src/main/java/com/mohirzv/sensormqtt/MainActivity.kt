package com.mohirzv.sensormqtt

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.amazonaws.regions.Regions
import com.google.gson.Gson
import com.mohirzv.sensormqtt.databinding.ActivityMainBinding
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Timer
import java.util.TimerTask
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
    val gson = Gson()
    // interval between mqtt messages
    private val interval = 50 //ms
    // object to store all sensor data as they arrive
    private var sensorData = SensorData(
        SensorAxis(0f, 0f, 0f),
        SensorAxis(0f, 0f, 0f),
        SensorAxis(0f, 0f, 0f),
        GpsCoords(0.0, 0.0)
    )
    companion object {
        // get from https://eu-north-1.console.aws.amazon.com/iot/home?region=eu-north-1#/settings
        const val CUSTOMER_SPECIFIC_ENDPOINT = "a1epn6oate0yl-ats.iot.eu-west-1.amazonaws.com"
        const val TOPIC = "sensorData"
        val MY_REGION = Regions.EU_WEST_1
        const val TAG = "Mohi"
        const val REQUEST_PERMISSIONS_CODE = 7777
    }

    private val timer = Timer()

    // Schedule a task to run at a fixed interval
    private val timerTask = object : TimerTask() {
        override fun run() {
            if (mqttConnected) {
                // in case gps data was not yet retrieved by the device, don't publish
                if (sensorData.gps.lat != 0.0) {
                    val data = gson.toJson(sensorData)
                    mqttManager.publishString(data, TOPIC, AWSIotMqttQos.QOS0)
                }
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Not used in this app
        }

        override fun onSensorChanged(event: SensorEvent?) {
            when (event?.sensor?.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    sensorData.accelerometer = SensorAxis(x, y, z)
                    binding.tvAcc.text = "$x, $y, $z"
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    sensorData.gyroscope = SensorAxis(x, y, z)
                    binding.tvGyro.text = "$x, $y, $z"
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    sensorData.magnetometer = SensorAxis(x, y, z)
                    binding.tvMagneto.text = "$x, $y, $z"
                }
                else -> {}
            }
        }
    }

    private val locationListener = LocationListener {
        val lat = it.latitude
        val lon = it.longitude
        val alt = it.altitude
        sensorData.gps = GpsCoords(lat, lon)
        binding.tvGps.text = "$lat, $lon"
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
        timer.schedule(timerTask, 0, interval.toLong())
    }

    private fun setupMQTT() {
        // MQTT client IDs are required to be unique per AWS IoT account.
        // This UUID is "practically unique" but does not _guarantee_
        // uniqueness.
        val clientId = UUID.randomUUID().toString()

        val keystore = setupCredentials()

        mqttManager = AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT)
        mqttManager.keepAlive = 30

        try {
            mqttManager.connect(keystore, AWSIotMqttClientStatusCallback {
                    status, throwable ->
                Log.d(TAG, "Status = $status")
                when(status) {
                    AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                        mqttConnected = true
                    }
                    else -> {
                        mqttConnected = false
                    }
                }
                throwable?.let{
                    Log.e(TAG, throwable.message.toString())
                }

            }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: $e")
        }
    }

    // for this we need the certificate and private key for AWS IoT
    // obtained when we create a certificate for a thing
    // files are stored in res/raw
    private fun setupCredentials(): KeyStore {
        val alias = "keyawws"
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (keyStore.containsAlias(alias)) {
                return keyStore
            }

            val certificateInputStream = resources.openRawResource(R.raw.certificate)
            val privateKeyInputStream = resources.openRawResource(R.raw.private_key)
            val publicKeyInputStream = resources.openRawResource(R.raw.public_key)

            val certificateFactory = CertificateFactory.getInstance("X.509")

            // Load device certificate
            val certificate: Certificate =
                certificateFactory.generateCertificate(certificateInputStream)
            keyStore.setCertificateEntry(alias, certificate)

            // Load private key from PEM file
            val privateKeyBytes: ByteArray = privateKeyInputStream.readBytes()
            val privateKeyPEM = String(privateKeyBytes)
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\n", "")
                .trim()
            Log.d("PEM Content", privateKeyPEM)
            val keyFactory = KeyFactory.getInstance("RSA")
            val keySpec = PKCS8EncodedKeySpec(android.util.Base64.decode(privateKeyPEM, android.util.Base64.DEFAULT))
            val privateKey: PrivateKey = keyFactory.generatePrivate(keySpec)

//            val publicKeyBytes: ByteArray = publicKeyInputStream.readBytes()
//            val publicKeyPEM = String(publicKeyBytes)
//                .replace("-----BEGIN PUBLIC KEY-----", "")
//                .replace("-----END PUBLIC KEY-----", "")
//                .replace("\n", "")
//                .trim()
//            Log.d("PEM Content", publicKeyPEM)
//            val keyFactoryPublic = KeyFactory.getInstance("RSA")
//            val keySpecPublic = RSAPublicKeySpec(Base64.decode(publicKeyPEM, Base64.DEFAULT))
//            val publicKey: PublicKey = keyFactoryPublic.generatePublic(keySpecPublic)

            keyStore.setKeyEntry(alias, privateKey, null, arrayOf(certificate))
            return keyStore
        } catch (e: Exception) {
            // Handle exceptions appropriately
            e.printStackTrace()
            throw RuntimeException("Error setting up connection", e)
        }
    }
    private fun startListening() {
        try {
            // Request location updates with a minimum time interval and minimum distance change
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                100, // 100 ms
                0.5f,   // 0.5 meters
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