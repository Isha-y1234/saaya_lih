package com.project.saaya.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.SosService
import com.project.saaya.ui.theme.SaayaEmergencyRed
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun SosCountdownScreen(
    onCancel: () -> Unit,
    onActivated: () -> Unit
) {
    val context = LocalContext.current
    val secondsLeft by SosService.countdownState.collectAsState()

    LaunchedEffect(secondsLeft) {
        if (secondsLeft == 0) {
            onActivated()
        }
    }

    // Start service if not already started
    LaunchedEffect(Unit) {
        if (secondsLeft == null) {
            SosService.startCountdown(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SaayaEmergencyRed),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Activating SOS",
                color = SaayaWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Surface(
                modifier = Modifier.size(160.dp),
                shape = CircleShape,
                color = SaayaWhite.copy(alpha = 0.2f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = (secondsLeft ?: 5).toString(),
                        color = SaayaWhite,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(64.dp))
            
            Button(
                onClick = {
                    SosService.stop(context)
                    onCancel()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaayaWhite,
                    contentColor = SaayaEmergencyRed
                ),
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(56.dp),
                shape = CircleShape
            ) {
                Text(
                    text = "CANCEL",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Emergency services and trusted contacts will be notified.",
                color = SaayaWhite.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
                fontSize = 14.sp
            )
        }
    }
}
