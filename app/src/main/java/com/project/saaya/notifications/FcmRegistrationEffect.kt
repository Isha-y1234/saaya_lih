package com.project.saaya.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun FcmRegistrationEffect(userId: String, onboardingComplete: Boolean) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preferences = context.getSharedPreferences("saaya_notification_permission", 0)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        FcmTokenSync.enqueue(context)
    }
    LaunchedEffect(userId, onboardingComplete) {
        SosNotifications.ensureChannel(context)
        FcmTokenSync.enqueue(context)
        if (onboardingComplete && Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            !preferences.getBoolean("asked", false)) {
            preferences.edit().putBoolean("asked", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    DisposableEffect(lifecycle, userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) FcmTokenSync.enqueue(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
