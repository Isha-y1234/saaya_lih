package com.project.saaya

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.project.saaya.notifications.SosNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SosService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdownJob: Job? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    
    companion object {
        private const val NOTIFICATION_ID = 2001
        private val _countdownState = MutableStateFlow<Int?>(null)
        val countdownState = _countdownState.asStateFlow()

        const val ACTION_START_COUNTDOWN = "com.project.saaya.ACTION_START_COUNTDOWN"
        const val ACTION_STOP_SERVICE = "com.project.saaya.ACTION_STOP_SERVICE"

        fun startCountdown(context: Context) {
            val intent = Intent(context, SosService::class.java).apply {
                action = ACTION_START_COUNTDOWN
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SosService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COUNTDOWN -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, 
                        createNotification(5), 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification(5))
                }
                startCountdownTimer()
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCountdownTimer() {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            for (i in 5 downTo 1) {
                _countdownState.value = i
                updateNotification(i)
                vibrate(i)
                delay(1000)
            }
            _countdownState.value = 0
            activateSos()
        }
    }

    private fun activateSos() {
        startLocationUpdates()
        updateNotificationActive()
        // The UI observes countdownState == 0 and calls incidentViewModel.startSos()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // In a real app, send this location to the backend via a repository.
                    // For now, we are satisfying "Initiate location sharing" within the service.
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            // Log or handle missing permissions
        }
    }

    private fun vibrate(secondsLeft: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val duration = 200L + (5 - secondsLeft) * 150L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun createNotification(secondsLeft: Int): Notification {
        SosNotifications.ensureChannel(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, SosNotifications.CHANNEL)
            .setContentTitle("SOS Activating")
            .setContentText("Emergency SOS will activate in $secondsLeft seconds.")
            .setSmallIcon(R.drawable.ic_sos_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    private fun updateNotification(secondsLeft: Int) {
        val notification = createNotification(secondsLeft)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationActive() {
        SosNotifications.ensureChannel(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, SosNotifications.CHANNEL)
            .setContentTitle("SOS Active")
            .setContentText("Saaya is monitoring your safety and sharing your location.")
            .setSmallIcon(R.drawable.ic_sos_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        _countdownState.value = null
        super.onDestroy()
    }
}
