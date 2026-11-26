package app.michufit;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.ExerciseSessionType;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.units.Energy;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.PowerManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PERMISSIONS = 1002;
    private static final int REQ_NOTIF_PERMISSION = 1003;
    private static final String SOLAR_CHANNEL_ID = "michufit_solar_alarm_v1";

    private WebView webView;
    private ValueCallback<Uri[]> fileUploadCallback;
    private String pendingTargetDate = "";
    private final Handler solarHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSolarAlarmRunnable = null;
    private PowerManager.WakeLock solarPartialWakeLock = null;

    private SensorManager sensorManager;
    private Sensor stepCounterSensor;
    private long latestSensorStepsToday = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pendingTargetDate = LocalDate.now().toString();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        }
        if (hasActivityRecognitionPermission()) {
            startStepSensor();
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new MichuHealthBridge(), "MichuAndroidHealth");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                Uri url = request.getUrl();
                String scheme = url.getScheme() != null ? url.getScheme() : "";
                String host = url.getHost() != null ? url.getHost() : "";
                if ("file".equalsIgnoreCase(scheme) || "michu-fit.github.io".equalsIgnoreCase(host)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    view.loadUrl("file:///android_asset/index.html");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;
                try {
                    Intent intent;
                    if (fileChooserParams != null) {
                        intent = fileChooserParams.createIntent();
                    } else {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                    }
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
            }
        });

        webView.loadUrl("https://michu-fit.github.io/michufit/");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileUploadCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                ClipData clipData = data.getClipData();
                Uri dataUri = data.getData();
                if (clipData != null) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (dataUri != null) {
                    results = new Uri[]{dataUri};
                }
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
    }

    private boolean hasActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void startStepSensor() {
        if (sensorManager != null && stepCounterSensor != null) {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event != null && event.sensor != null && event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            if (event.values == null || event.values.length == 0) return;
            long totalSinceBoot = (long) event.values[0];
            String todayKey = "base_" + LocalDate.now().toString();
            SharedPreferences prefs = getSharedPreferences("michufit_steps", Context.MODE_PRIVATE);
            long base = prefs.getLong(todayKey, -1L);
            if (base < 0L || totalSinceBoot < base) {
                base = totalSinceBoot;
                prefs.edit().putLong(todayKey, base).apply();
            }
            long diff = totalSinceBoot - base;
            latestSensorStepsToday = Math.max(0L, diff);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public class MichuHealthBridge {
        @JavascriptInterface
        public void requestAndReadTodayHealth(final String dateStr) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingTargetDate = (dateStr != null && !dateStr.trim().isEmpty())
                            ? dateStr.trim()
                            : LocalDate.now().toString();
                    requestPermissionsAndRead(pendingTargetDate);
                }
            });
        }

        @JavascriptInterface
        public void requestSolarNotificationPermission() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ensureSolarNotificationChannel();
                    if (Build.VERSION.SDK_INT >= 33) {
                        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF_PERMISSION);
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean hasSolarNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @JavascriptInterface
        public void postSolarNotification(final String title, final String body) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showNativeSolarNotification(title, body);
                }
            });
        }

        @JavascriptInterface
        public void scheduleSolarNotification(final int delaySec, final String title, final String body) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ensureSolarNotificationChannel();
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF_PERMISSION);
                    }
                    cancelPendingSolarRunnable();
                    acquireSolarPartialWakeLock(Math.max(5, delaySec + 15) * 1000L);
                    pendingSolarAlarmRunnable = new Runnable() {
                        @Override
                        public void run() {
                            pendingSolarAlarmRunnable = null;
                            showNativeSolarNotification(title, body);
                            releaseSolarPartialWakeLock();
                        }
                    };
                    solarHandler.postDelayed(pendingSolarAlarmRunnable, Math.max(1, delaySec) * 1000L);
                }
            });
        }

        @JavascriptInterface
        public void cancelSolarNotification() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelPendingSolarRunnable();
                    releaseSolarPartialWakeLock();
                }
            });
        }
    }

    private void cancelPendingSolarRunnable() {
        if (pendingSolarAlarmRunnable != null) {
            solarHandler.removeCallbacks(pendingSolarAlarmRunnable);
            pendingSolarAlarmRunnable = null;
        }
    }

    private void acquireSolarPartialWakeLock(long timeoutMs) {
        try {
            releaseSolarPartialWakeLock();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                solarPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MichuFit::SolarTimerWakeLock");
                solarPartialWakeLock.setReferenceCounted(false);
                solarPartialWakeLock.acquire(timeoutMs);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseSolarPartialWakeLock() {
        try {
            if (solarPartialWakeLock != null && solarPartialWakeLock.isHeld()) {
                solarPartialWakeLock.release();
            }
            solarPartialWakeLock = null;
        } catch (Exception ignored) {
        }
    }

    private void ensureSolarNotificationChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    SOLAR_CHANNEL_ID,
                    "Alarma Solar Vitamina D",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Avisos cuando cumplis tu tiempo seguro de exposicion solar");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 450, 160, 450, 160, 650});
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {
        }
    }

    private void showNativeSolarNotification(String title, String body) {
        try {
            ensureSolarNotificationChannel();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    701,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            Notification notif = new Notification.Builder(this, SOLAR_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title != null ? title : "Michu Fit")
                    .setContentText(body != null ? body : "Tiempo de exposicion solar completado.")
                    .setStyle(new Notification.BigTextStyle().bigText(body != null ? body : ""))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(7001, notif);
        } catch (Exception ignored) {
        }
    }

    private void requestPermissionsAndRead(String targetDate) {
        if (Build.VERSION.SDK_INT >= 34) {
            String[] perms = new String[]{
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    "android.permission.health.READ_STEPS",
                    "android.permission.health.READ_ACTIVE_CALORIES_BURNED",
                    "android.permission.health.READ_EXERCISE"
            };
            boolean allGranted = true;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                requestPermissions(perms, REQ_PERMISSIONS);
                return;
            }
            startStepSensor();
            readHealthConnectApi34(targetDate);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasActivityRecognitionPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_PERMISSIONS);
        } else {
            startStepSensor();
            sendSensorFallbackToWeb(targetDate);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasActivityRecognitionPermission()) {
                startStepSensor();
            }
            if (Build.VERSION.SDK_INT >= 34) {
                readHealthConnectApi34(pendingTargetDate);
            } else {
                sendSensorFallbackToWeb(pendingTargetDate);
            }
        }
    }

    private void readHealthConnectApi34(final String targetDate) {
        if (Build.VERSION.SDK_INT < 34) {
            sendSensorFallbackToWeb(targetDate);
            return;
        }
        try {
            final HealthConnectManager manager = getSystemService(HealthConnectManager.class);
            if (manager == null) {
                sendSensorFallbackToWeb(targetDate);
                return;
            }
            LocalDate parsed;
            try {
                parsed = LocalDate.parse(targetDate);
            } catch (Exception e) {
                parsed = LocalDate.now();
            }
            final LocalDate finalDate = parsed;
            ZoneId zone = ZoneId.systemDefault();
            Instant startInstant = finalDate.atStartOfDay(zone).toInstant();
            Instant endInstant = finalDate.plusDays(1).atStartOfDay(zone).minusSeconds(1).toInstant();

            final TimeInstantRangeFilter timeFilter = new TimeInstantRangeFilter.Builder()
                    .setStartTime(startInstant)
                    .setEndTime(endInstant)
                    .build();

            final DataOrigin shealthOrigin = new DataOrigin.Builder()
                    .setPackageName("com.sec.android.app.shealth")
                    .build();

            AggregateRecordsRequest<Long> stepsReq = new AggregateRecordsRequest.Builder<Long>(timeFilter)
                    .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL)
                    .addDataOriginsFilter(shealthOrigin)
                    .build();

            manager.aggregate(
                    stepsReq,
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException>() {
                        @Override
                        public void onResult(AggregateRecordsResponse<Long> result) {
                            long shealthAggSteps = 0L;
                            if (result != null) {
                                Long val = result.get(StepsRecord.STEPS_COUNT_TOTAL);
                                if (val != null) shealthAggSteps = val;
                            }
                            refineStepsByDeviceAndOriginApi34(manager, timeFilter, finalDate, shealthAggSteps);
                        }

                        @Override
                        public void onError(HealthConnectException error) {
                            refineStepsByDeviceAndOriginApi34(manager, timeFilter, finalDate, 0L);
                        }
                    }
            );
        } catch (Exception e) {
            sendSensorFallbackToWeb(targetDate);
        }
    }

    private void refineStepsByDeviceAndOriginApi34(
            final HealthConnectManager manager,
            final TimeInstantRangeFilter timeFilter,
            final LocalDate finalDate,
            final long shealthAggSteps
    ) {
        if (Build.VERSION.SDK_INT < 34) {
            sendSensorFallbackToWeb(finalDate.toString());
            return;
        }
        try {
            ReadRecordsRequestUsingFilters<StepsRecord> rawStepsReq =
                    new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                            .setTimeRangeFilter(timeFilter)
                            .setPageSize(2000)
                            .build();

            manager.readRecords(
                    rawStepsReq,
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<ReadRecordsResponse<StepsRecord>, HealthConnectException>() {
                        @Override
                        public void onResult(ReadRecordsResponse<StepsRecord> result) {
                            long shealthPrimarySteps = 0L;
                            long shealthPhoneModelSteps = 0L;
                            long otherOriginsSteps = 0L;

                            if (result != null && result.getRecords() != null) {
                                for (StepsRecord rec : result.getRecords()) {
                                    if (rec == null) continue;
                                    long c = Math.max(0L, rec.getCount());
                                    Metadata meta = rec.getMetadata();
                                    String pkg = (meta != null && meta.getDataOrigin() != null && meta.getDataOrigin().getPackageName() != null)
                                            ? meta.getDataOrigin().getPackageName().trim()
                                            : "";
                                    Device dev = meta != null ? meta.getDevice() : null;
                                    String model = (dev != null && dev.getModel() != null) ? dev.getModel().trim().toUpperCase() : "";
                                    int devType = dev != null ? dev.getType() : Device.DEVICE_TYPE_UNKNOWN;

                                    if ("com.sec.android.app.shealth".equalsIgnoreCase(pkg)) {
                                        boolean isPhoneHardwareEntry = model.contains("A56") || model.contains("SM-A") || devType == Device.DEVICE_TYPE_PHONE;
                                        if (isPhoneHardwareEntry) {
                                            shealthPhoneModelSteps += c;
                                        } else {
                                            shealthPrimarySteps += c;
                                        }
                                    } else {
                                        otherOriginsSteps += c;
                                    }
                                }
                            }

                            long chosenSteps = shealthAggSteps;
                            // Si adentro de Samsung Health vienen separados "Samsung Health" (shealthPrimarySteps) y el teléfono "A56" (shealthPhoneModelSteps),
                            // tomamos exclusivamente el conteo principal de Samsung Health para no sumar el doble con el teléfono.
                            if (shealthPrimarySteps > 0L && shealthPhoneModelSteps > 0L) {
                                chosenSteps = shealthPrimarySteps;
                            } else if (chosenSteps <= 0L) {
                                if (shealthPrimarySteps + shealthPhoneModelSteps > 0L) {
                                    chosenSteps = shealthPrimarySteps + shealthPhoneModelSteps;
                                } else if (otherOriginsSteps > 0L) {
                                    chosenSteps = otherOriginsSteps;
                                } else if (finalDate.equals(LocalDate.now()) && latestSensorStepsToday > 0L) {
                                    chosenSteps = latestSensorStepsToday;
                                }
                            }

                            readCaloriesAndExercisesApi34(manager, timeFilter, finalDate.toString(), chosenSteps);
                        }

                        @Override
                        public void onError(HealthConnectException error) {
                            long fallbackSteps = shealthAggSteps;
                            if (fallbackSteps <= 0L && finalDate.equals(LocalDate.now()) && latestSensorStepsToday > 0L) {
                                fallbackSteps = latestSensorStepsToday;
                            }
                            readCaloriesAndExercisesApi34(manager, timeFilter, finalDate.toString(), fallbackSteps);
                        }
                    }
            );
        } catch (Exception e) {
            readCaloriesAndExercisesApi34(manager, timeFilter, finalDate.toString(), shealthAggSteps);
        }
    }

    private void readCaloriesAndExercisesApi34(
            final HealthConnectManager manager,
            final TimeInstantRangeFilter timeFilter,
            final String dateStr,
            final long steps
    ) {
        if (Build.VERSION.SDK_INT < 34) {
            sendSensorFallbackToWeb(dateStr);
            return;
        }
        try {
            DataOrigin shealthOrigin = new DataOrigin.Builder()
                    .setPackageName("com.sec.android.app.shealth")
                    .build();
            AggregateRecordsRequest<Energy> kcalReq = new AggregateRecordsRequest.Builder<Energy>(timeFilter)
                    .addAggregationType(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                    .addDataOriginsFilter(shealthOrigin)
                    .build();

            manager.aggregate(
                    kcalReq,
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<AggregateRecordsResponse<Energy>, HealthConnectException>() {
                        @Override
                        public void onResult(AggregateRecordsResponse<Energy> result) {
                            double kcal = 0.0;
                            if (result != null) {
                                Energy energy = result.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL);
                                if (energy != null) {
                                    kcal = energy.getInCalories() / 1000.0;
                                }
                            }
                            readExerciseSessionsApi34(manager, timeFilter, dateStr, steps, kcal);
                        }

                        @Override
                        public void onError(HealthConnectException error) {
                            readExerciseSessionsApi34(manager, timeFilter, dateStr, steps, 0.0);
                        }
                    }
            );
        } catch (Exception e) {
            readExerciseSessionsApi34(manager, timeFilter, dateStr, steps, 0.0);
        }
    }

    private void readExerciseSessionsApi34(
            final HealthConnectManager manager,
            final TimeInstantRangeFilter timeFilter,
            final String dateStr,
            final long steps,
            final double activeKcal
    ) {
        if (Build.VERSION.SDK_INT < 34) {
            sendSensorFallbackToWeb(dateStr);
            return;
        }
        try {
            ReadRecordsRequestUsingFilters<ExerciseSessionRecord> exReq =
                    new ReadRecordsRequestUsingFilters.Builder<>(ExerciseSessionRecord.class)
                            .setTimeRangeFilter(timeFilter)
                            .build();

            manager.readRecords(
                    exReq,
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<ReadRecordsResponse<ExerciseSessionRecord>, HealthConnectException>() {
                        @Override
                        public void onResult(ReadRecordsResponse<ExerciseSessionRecord> result) {
                            JSONArray exercisesJson = new JSONArray();
                            if (result != null) {
                                List<ExerciseSessionRecord> records = result.getRecords();
                                if (records != null) {
                                    for (ExerciseSessionRecord session : records) {
                                        try {
                                            long mins = Math.max(1L, Duration.between(session.getStartTime(), session.getEndTime()).toMinutes());
                                            JSONObject obj = new JSONObject();
                                            CharSequence title = session.getTitle();
                                            obj.put("title", (title != null && title.length() > 0) ? title.toString() : mapExerciseType(session.getExerciseType()));
                                            obj.put("durationMinutes", mins);
                                            obj.put("calories", 0);
                                            exercisesJson.put(obj);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                            }
                            try {
                                JSONObject payload = new JSONObject();
                                payload.put("date", dateStr);
                                payload.put("steps", steps);
                                payload.put("activeCalories", activeKcal);
                                payload.put("exercises", exercisesJson);
                                payload.put("source", "Samsung Health / Health Connect");
                                dispatchPayloadToWeb(payload);
                            } catch (Exception e) {
                                sendSensorFallbackToWeb(dateStr);
                            }
                        }

                        @Override
                        public void onError(HealthConnectException error) {
                            try {
                                JSONObject payload = new JSONObject();
                                payload.put("date", dateStr);
                                payload.put("steps", steps);
                                payload.put("activeCalories", activeKcal);
                                payload.put("exercises", new JSONArray());
                                payload.put("source", "Samsung Health / Health Connect");
                                dispatchPayloadToWeb(payload);
                            } catch (Exception e) {
                                sendSensorFallbackToWeb(dateStr);
                            }
                        }
                    }
            );
        } catch (Exception e) {
            sendSensorFallbackToWeb(dateStr);
        }
    }

    private void sendSensorFallbackToWeb(final String targetDate) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("date", targetDate);
            payload.put("steps", latestSensorStepsToday);
            payload.put("activeCalories", 0);
            payload.put("exercises", new JSONArray());
            payload.put("source", "Sensor Podómetro Android");
            dispatchPayloadToWeb(payload);
            if (latestSensorStepsToday == 0L) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(
                                MainActivity.this,
                                "Caminá unos pasos con el teléfono o activá Health Connect en Samsung Health (Ajustes -> Health Connect).",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private void dispatchPayloadToWeb(final JSONObject payload) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String js = "window.onMichuHealthDataReceived && window.onMichuHealthDataReceived(" + payload.toString() + ");";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private String mapExerciseType(int type) {
        if (Build.VERSION.SDK_INT >= 34) {
            switch (type) {
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_WALKING:
                    return "Caminata";
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING:
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING_TREADMILL:
                    return "Running / Cinta";
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING:
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING_STATIONARY:
                    return "Ciclismo / Bicicleta";
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_OPEN_WATER:
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_POOL:
                    return "Natación";
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING:
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_WEIGHTLIFTING:
                    return "Entrenamiento de Fuerza";
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING:
                    return "HIIT / Funcional";
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_YOGA:
                    return "Yoga";
                case ExerciseSessionType.EXERCISE_SESSION_TYPE_PILATES:
                    return "Pilates";
                default:
                    return "Entrenamiento registrado";
            }
        }
        return "Entrenamiento registrado";
    }
}
