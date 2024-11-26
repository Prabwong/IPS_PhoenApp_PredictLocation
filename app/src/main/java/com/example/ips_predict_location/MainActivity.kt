package com.example.ips_predict_location

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.github.chrisbanes.photoview.PhotoView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import android.util.Log
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import android.location.LocationManager
import android.content.BroadcastReceiver
import android.content.IntentFilter

class MainActivity : AppCompatActivity() {

    private lateinit var floorPlanView: PhotoView
    private lateinit var buildingSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var wifiManager: WifiManager
    private var isPredicting = false
    private var isReceiverRegistered = false
    private val predictionInterval = 3000L // 3 seconds
    private var macAddresses = listOf<String>() // Store MAC addresses
    private val handler = Handler(Looper.getMainLooper())

    // BroadcastReceiver for passive Wi-Fi scanning
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    // Wi-Fi scan was successful
                    val wifiScanResults = wifiManager.scanResults
                    processScanResults(wifiScanResults)
                } else {
                    // Scan failed, handle the error
                    Log.e("Wi-Fi Scan", "Scan failed.")
                }
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val WIFI_PERMISSION_REQUEST_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        floorPlanView = findViewById(R.id.floor_plan_image)
        buildingSpinner = findViewById(R.id.building_spinner)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        // Display "Wait for Response" image initially
        floorPlanView.setImageResource(R.drawable.wait_for_response)

        // Fetch building data when the app starts
        fetchBuildingData()

        buildingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedBuilding = buildingSpinner.selectedItem.toString()
                updateFloorPlanImage(selectedBuilding)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }

        startButton.setOnClickListener {
            val selectedBuilding = buildingSpinner.selectedItem.toString()
            stopButton.isEnabled = true
            startButton.isEnabled = false

            // Request MAC addresses before scanning
            requestMacAddresses(selectedBuilding) {
                // After MAC addresses are fetched, start locating
                startLocating(selectedBuilding)
            }
        }

        stopButton.setOnClickListener {
            stopLocating()
            stopButton.isEnabled = false
            startButton.isEnabled = true
        }
    }

    private fun checkAndRequestPermissions(onPermissionGranted: () -> Unit) {
        val wifiPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_WIFI_STATE)
        val fineLocationPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)

        if (wifiPermission != PackageManager.PERMISSION_GRANTED || fineLocationPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.ACCESS_WIFI_STATE, android.Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            onPermissionGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // Permissions are granted, proceed
            enableWifiAndScan(buildingSpinner.selectedItem.toString())
        } else {
            Toast.makeText(this, "Wi-Fi and location permissions are required for scanning", Toast.LENGTH_SHORT).show()
        }
    }


    private fun requestMacAddresses(buildingName: String, onMacAddressesFetched: () -> Unit) {
        val client = OkHttpClient()

        val requestBody = JSONObject().put("building", buildingName)
            .toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://www.cie-ips-backend.com/get-building-details")  // Your API URL
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    responseData?.let {
                        val jsonObject = JSONObject(it)
                        val macList = jsonObject.getJSONArray("mac_addresses")
                        macAddresses = (0 until macList.length()).map { i -> macList.getString(i) }
                        runOnUiThread { onMacAddressesFetched() }
                    }
                }
            }
        })
    }

//    private fun startLocating(building: String) {
//        isPredicting = true
//        checkAndRequestPermissions {
//            // Once permissions are granted, start the process
//            val handler = Handler(Looper.getMainLooper())
//            val runnable = object : Runnable {
//                override fun run() {
//                    if (isPredicting) {
////                        // Get connected Wi-Fi RSSI
////                        val wifiInfo = wifiManager.connectionInfo
////                        val connectedRSSI: Int = wifiInfo.rssi // Get RSSI of the connected network
////                        Log.d("Connected Wi-Fi", "RSSI: $connectedRSSI dBm")
//
//                        // Get RSSI from other access points and filter them
//                        scanAndSendRSSI(building)
//
//                        // Repeat every 5 seconds
//                        handler.postDelayed(this, predictionInterval)
//                    }
//                }
//            }
//
//            // Start the repeating task
//            handler.post(runnable)
//        }
//    }

    private fun startLocating(building: String) {
        isPredicting = true
        checkAndRequestPermissions {
            // Register the BroadcastReceiver to listen for Wi-Fi scans
            registerWifiScanReceiver()
            startWifiScan(building) // Start scanning
        }
    }

//    private fun stopLocating() {
//        isPredicting = false
//        handler.removeCallbacksAndMessages(null)
//    }

    private fun stopLocating() {
        isPredicting = false
        handler.removeCallbacksAndMessages(null)
        unregisterWifiScanReceiver() // Unregister when stopping
    }

    private fun startWifiScan(building: String) {
        if (!isPredicting) return

        // Trigger a system Wi-Fi scan, results will be handled by the BroadcastReceiver
        wifiManager.startScan()

        // Repeat the scan every 5 seconds (passive scanning)
        handler.postDelayed({
            startWifiScan(building)
        }, predictionInterval)
    }

    private fun processScanResults(wifiScanResults: List<ScanResult>) {
        // Process the scan results and filter them
        val filteredRSSI = getFilteredRSSI(wifiScanResults)

        // Send the filtered RSSI data to your API
        sendRSSIToAPI(buildingSpinner.selectedItem.toString(), filteredRSSI)
    }

    private fun scanAndSendRSSI(building: String) {
        // Check if both location and Wi-Fi state permissions are granted
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {

            Log.e("Scan", "Location or Wi-Fi state permission not granted.")
            Toast.makeText(this, "Location and Wi-Fi permissions are required to scan", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Perform Wi-Fi scan and get results
            val wifiScanResults = wifiManager.scanResults

            // Process the scan results and filter them
            val filteredRSSI = getFilteredRSSI(wifiScanResults)

            // Send the filtered RSSI data to your API
            sendRSSIToAPI(building, filteredRSSI)
        } catch (e: SecurityException) {
            Log.e("Scan Error", "Error during scanning: ${e.message}")
        }
    }


    private fun getFilteredRSSI(scanResults: List<ScanResult>): JSONObject {
        val rssiData = JSONObject()

        // Log all available MAC addresses and RSSI values in the scan results
        Log.d("RSSI All Results", "All available Wi-Fi access points:")
        for (result in scanResults) {
            Log.d("RSSI All Results", "MAC: ${result.BSSID}, RSSI: ${result.level}")
        }

        // Loop over MAC addresses to filter incoming RSSI values
        for (mac in macAddresses) {
            val scanResult = scanResults.find { it.BSSID == mac }
            val rssiValue = scanResult?.level ?: -100  // Default RSSI value if not found
            rssiData.put(mac, rssiValue)

            // Log the filtered MAC address and corresponding RSSI value
            Log.d("RSSI Filtered", "Filtered MAC: $mac, RSSI: $rssiValue")
        }

        return rssiData
    }

    private fun registerWifiScanReceiver() {
        if (!isReceiverRegistered) {
            registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            isReceiverRegistered = true  // Set to true after registering
        }
    }

    private fun unregisterWifiScanReceiver() {
        if (isReceiverRegistered) {
            unregisterReceiver(wifiScanReceiver)
            isReceiverRegistered = false  // Set to false after unregistering
        }
    }

    private fun sendRSSIToAPI(building: String, rssiData: JSONObject) {
        val client = OkHttpClient()

        val requestBody = JSONObject().apply {
            put("building", building)
            put("rssi_data", rssiData)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://www.cie-ips-backend.com/predict-location")  // Your API URL
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    runOnUiThread {
                        updateLocationFields(responseData)
                    }
                }
            }
        })
    }

    private fun updateLocationFields(responseData: String?) {
        responseData?.let {
            val jsonObject = JSONObject(it)
            val x = jsonObject.getDouble("x")
            val y = jsonObject.getDouble("y")
            var floor = jsonObject.getString("floor")
            val building = buildingSpinner.selectedItem.toString()

            if (building == "CMKL") {
                floor = (floor.toFloat() + 6.0).toString()  // Increment the floor and convert back to String
            }


            // Step 5: Display result and update the picture plane
            findViewById<EditText>(R.id.x_field).setText(String.format(Locale.getDefault(), "%.2f", x))
            findViewById<EditText>(R.id.y_field).setText(String.format(Locale.getDefault(), "%.2f", y))
            findViewById<EditText>(R.id.floor_field).setText(floor)

            updateFloorLayout(x, y, floor, building)
        }
    }

    private fun updateFloorLayout(x: Double, y: Double, floor: String, building: String) {
        val floorPlanDrawable = when {
            building == "CMKL" && floor == "6.0" -> R.drawable.cmkl_floor_6
            building == "CMKL" && floor == "7.0" -> R.drawable.cmkl_floor_7
            else -> R.drawable.wait_for_response
        }

        floorPlanView.setImageResource(floorPlanDrawable)

        val floorPlanBitmap = (floorPlanView.drawable as BitmapDrawable).bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(floorPlanBitmap)

        val paint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val strokePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        val pixelX = scaleToPixelX(x, building)
        val pixelY = scaleToPixelY(y, building)

        canvas.drawCircle(pixelX.toFloat(), pixelY.toFloat(), 5f, paint)
        canvas.drawCircle(pixelX.toFloat(), pixelY.toFloat(), 5f, strokePaint)

        floorPlanView.setImageBitmap(floorPlanBitmap)

        // Zoom in and center the view on the green dot
        zoomToDot(pixelX.toFloat(), pixelY.toFloat())

        // Log pixel coordinates for debugging
        Log.d("Position", "Predicted value x: $x, y:$y")
        Log.d("Position", "Drawing dot at pixelX: $pixelX, pixelY: $pixelY")
    }

    private fun zoomToDot(pixelX: Float, pixelY: Float) {
        // Desired zoom level
        val desiredZoomLevel = 3.0f

        // Get the current zoom level (scale) of the PhotoView
        val currentZoomLevel = floorPlanView.scale

        if (currentZoomLevel != desiredZoomLevel) {
            // If current zoom level is less than the desired level, zoom to the desired level
            floorPlanView.setScale(desiredZoomLevel, pixelX, pixelY, false)
        }
//        else {
//            // If already zoomed in, animate the pan smoothly to the new point
//            smoothPanTo(pixelX, pixelY, currentZoomLevel)
//        }
    }

    private fun smoothPanTo(targetX: Float, targetY: Float, currentZoomLevel: Float) {
        // Get current positions
        val currentX = floorPlanView.displayRect.centerX()
        val currentY = floorPlanView.displayRect.centerY()

        // Create a ValueAnimator for smooth panning
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 500L  // Adjust this value to control pan speed
        animator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Float

            // Interpolate between current and target positions
            val newX = currentX + (targetX - currentX) * animatedValue
            val newY = currentY + (targetY - currentY) * animatedValue

            // Pan the PhotoView while keeping the current zoom level
            floorPlanView.setScale(currentZoomLevel, newX, newY, false)
        }

        animator.start()
    }

    private fun scaleToPixelX(x: Double, building: String): Int {
        return when (building) {
            "CMKL" -> {
                val basePixelX = 152
                val scaleFactor = 35
                (basePixelX + (x * scaleFactor)).toInt()
            }
            else -> 0
        }
    }

    private fun scaleToPixelY(y: Double, building: String): Int {
        return when (building) {
            "CMKL" -> {
                val basePixelY = 1032
                val scaleFactor = 35
                (basePixelY - (y * scaleFactor)).toInt()
            }
            else -> 0
        }
    }

    private fun enableWifiAndScan(building: String) {
        // Check if Wi-Fi is enabled
        if (!wifiManager.isWifiEnabled) {
            // If Wi-Fi is not enabled, prompt the user to enable it
            showEnableWifiDialog()
        } else {
            // Wi-Fi is enabled, now check if location services are enabled
            if (isLocationEnabled()) {
                // If location services are enabled, scan for Wi-Fi networks
                scanAndSendRSSI(building)
            } else {
                // Prompt the user to enable location services
                showEnableLocationDialog()
            }
        }
    }

    // Function to check if location services are enabled
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // Function to show a dialog to enable Wi-Fi
    private fun showEnableWifiDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enable Wi-Fi")
        builder.setMessage("Wi-Fi is required to scan for access points. Do you want to enable it?")
        builder.setPositiveButton("Enable") { _, _ ->
            // Open the Wi-Fi settings to let the user enable it
            val panelIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
            startActivity(panelIntent)
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    // Function to show a dialog to enable location services
    private fun showEnableLocationDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enable Location")
        builder.setMessage("Location services are required to scan Wi-Fi. Please enable them.")
        builder.setPositiveButton("Enable") { _, _ ->
            // Open the location settings to let the user enable it
            val panelIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(panelIntent)
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun fetchBuildingData() {
        val client = OkHttpClient()

        val request = Request.Builder()
            .url("https://www.cie-ips-backend.com/get-buildings")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val buildingList = parseBuildingData(responseData)

                    runOnUiThread {
                        populateBuildingDropdown(buildingList)
                    }
                }
            }
        })
    }

    private fun parseBuildingData(responseData: String?): List<String> {
        val buildings = mutableListOf<String>()
        responseData?.let {
            val jsonObject = JSONObject(it)
            val jsonArray = jsonObject.getJSONArray("buildings")

            for (i in 0 until jsonArray.length()) {
                val building = jsonArray.getString(i)
                buildings.add(building)
            }
        }
        return buildings
    }

    private fun populateBuildingDropdown(buildings: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, buildings)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        buildingSpinner.adapter = adapter
    }

    private fun updateFloorPlanImage(building: String) {
        when (building) {
            "CMKL" -> {
                // Set the initial image for CMKL
                floorPlanView.setImageResource(R.drawable.cmkl_floor_6)
            }
            // Add other building logic here
            "OtherBuilding" -> {
                floorPlanView.setImageResource(R.drawable.other_building_floor)
            }
            else -> {
                floorPlanView.setImageResource(R.drawable.wait_for_response)
            }
        }
    }

}
