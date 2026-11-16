package app.michufit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingTargetDate: String = LocalDate.now().toString()

    private var sensorManager: SensorManager? = null
    private var stepCounterSensor: Sensor? = null
    private var latestSensorStepsToday: Long = 0L

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    private val requestHealthPermissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ ->
        readHealthDataAndSendToWeb(pendingTargetDate)
    }

    private val requestActivityPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startStepSensor()
        }
        triggerHealthConnectOrFallback(pendingTargetDate)
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val dataUri = result.data?.data
            val clipData = result.data?.clipData
            val uris = mutableListOf<Uri>()
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else if (dataUri != null) {
                uris.add(dataUri)
            }
            callback?.onReceiveValue(uris.toTypedArray())
        } else {
            callback?.onReceiveValue(null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        stepCounterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (hasActivityRecognitionPermission()) {
            startStepSensor()
        }

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(MichuHealthBridge(), "MichuAndroidHealth")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val host = url.host ?: ""
                if (url.scheme == "file" || host.equals("michu-fit.github.io", ignoreCase = true)) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    view?.loadUrl("file:///android_asset/index.html")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    fileUploadCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl("https://michu-fit.github.io/michufit/")
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun startStepSensor() {
        stepCounterSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            val totalSinceBoot = event.values.firstOrNull()?.toLong() ?: return
            val todayKey = "base_" + LocalDate.now().toString()
            val prefs = getSharedPreferences("michufit_steps", Context.MODE_PRIVATE)
            var base = prefs.getLong(todayKey, -1L)
            if (base < 0L || totalSinceBoot < base) {
                base = totalSinceBoot
                prefs.edit().putLong(todayKey, base).apply()
            }
            latestSensorStepsToday = (totalSinceBoot - base).coerceAtLeast(0L)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    inner class MichuHealthBridge {
        @JavascriptInterface
        fun requestAndReadTodayHealth(dateStr: String?) {
            runOnUiThread {
                val safeDate = if (!dateStr.isNullOrBlank()) dateStr else LocalDate.now().toString()
                pendingTargetDate = safeDate
                if (!hasActivityRecognitionPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestActivityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                } else {
                    startStepSensor()
                    triggerHealthConnectOrFallback(safeDate)
                }
            }
        }
    }

    private fun triggerHealthConnectOrFallback(targetDate: String) {
        val sdkStatus = HealthConnectClient.getSdkStatus(this)
        if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
            val client = HealthConnectClient.getOrCreate(this)
            lifecycleScope.launch {
                try {
                    val granted = client.permissionController.getGrantedPermissions()
                    if (granted.containsAll(healthPermissions)) {
                        readHealthDataAndSendToWeb(targetDate)
                    } else {
                        requestHealthPermissionsLauncher.launch(healthPermissions)
                    }
                } catch (_: Exception) {
                    readHealthDataAndSendToWeb(targetDate)
                }
            }
        } else {
            sendSensorFallbackToWeb(targetDate)
        }
    }

    private fun readHealthDataAndSendToWeb(targetDate: String) {
        val sdkStatus = HealthConnectClient.getSdkStatus(this)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            sendSensorFallbackToWeb(targetDate)
            return
        }

        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            try {
                val parsedDate = try {
                    LocalDate.parse(targetDate)
                } catch (_: Exception) {
                    LocalDate.now()
                }
                val zone = ZoneId.systemDefault()
                val startInstant = parsedDate.atStartOfDay(zone).toInstant()
                val endInstant = parsedDate.plusDays(1).atStartOfDay(zone).minusSeconds(1).toInstant()

                val aggResult = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(
                            StepsRecord.COUNT_TOTAL,
                            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                        ),
                        timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant)
                    )
                )

                var steps = aggResult[StepsRecord.COUNT_TOTAL] ?: 0L
                val activeKcal = aggResult[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0

                if (steps <= 0L && parsedDate == LocalDate.now() && latestSensorStepsToday > 0L) {
                    steps = latestSensorStepsToday
                }

                val exercisesResponse = client.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant)
                    )
                )

                val exercisesJson = JSONArray()
                for (session in exercisesResponse.records) {
                    val durMinutes = Duration.between(session.startTime, session.endTime).toMinutes().coerceAtLeast(1)
                    val obj = JSONObject()
                    obj.put("title", session.title ?: mapExerciseTypeToSpanish(session.exerciseType))
                    obj.put("durationMinutes", durMinutes)
                    obj.put("calories", 0)
                    exercisesJson.put(obj)
                }

                val payload = JSONObject()
                payload.put("date", parsedDate.toString())
                payload.put("steps", steps)
                payload.put("activeCalories", activeKcal)
                payload.put("exercises", exercisesJson)
                payload.put("source", "Samsung Health / Health Connect")

                dispatchPayloadToWeb(payload)
            } catch (_: Exception) {
                sendSensorFallbackToWeb(targetDate)
            }
        }
    }

    private fun sendSensorFallbackToWeb(targetDate: String) {
        val payload = JSONObject()
        payload.put("date", targetDate)
        payload.put("steps", latestSensorStepsToday)
        payload.put("activeCalories", 0)
        payload.put("exercises", JSONArray())
        payload.put("source", "Sensor Podómetro Android")
        dispatchPayloadToWeb(payload)
        if (latestSensorStepsToday == 0L) {
            Toast.makeText(
                this,
                "Instalá o activá Health Connect en Samsung Health (Ajustes -> Health Connect) para sincronizar pasos históricos.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun dispatchPayloadToWeb(payload: JSONObject) {
        runOnUiThread {
            val js = "window.onMichuHealthDataReceived && window.onMichuHealthDataReceived(${payload});"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun mapExerciseTypeToSpanish(type: Int): String {
        return when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Caminata"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Running / Cinta"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Ciclismo / Bicicleta"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Natación"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Entrenamiento de Fuerza"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT / Funcional"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
            else -> "Entrenamiento registrado"
        }
    }
}
