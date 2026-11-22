package com.project.saaya.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.R
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaSurface
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    onRetrySession: (() -> Unit)? = null,
    onResources: () -> Unit = {}
) {
    Scaffold(
        containerColor = SaayaWhite,
        bottomBar = {
            Surface(
                modifier = Modifier.navigationBarsPadding(),
                color = SaayaWhite,
                shadowElevation = 6.dp
            ) {
                Button(
                    onClick = onGetStarted,
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 20.dp,
                            vertical = 16.dp
                        )
                        .height(54.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaayaBlack,
                        contentColor = SaayaWhite
                    )
                ) {
                    Text(
                        text = if (isLoading) "Signing in…" else "Sign in with Google",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextButton(onClick = onResources) { Text("Legal & Emergency Resources · 112") }
            Spacer(Modifier.height(52.dp))

            Icon(
                painter = painterResource(id = R.drawable.ic_saaya_logo),
                contentDescription = "Saaya Logo",
                modifier = Modifier.size(64.dp),
                tint = Color.Unspecified
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Saaya",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = SaayaBlack,
                letterSpacing = (-1).sp
            )

            Text(
                text = "never alone.",
                style = MaterialTheme.typography.bodyMedium,
                color = SaayaTextSecondary
            )

            Spacer(Modifier.height(46.dp))

            WelcomeFeatureCard(
                icon = Icons.Outlined.Map,
                text = "Travel with monitored check-ins"
            )

            Spacer(Modifier.height(12.dp))

            WelcomeFeatureCard(
                icon = Icons.Outlined.Groups,
                text = "Reach verified people when isolated"
            )

            Spacer(Modifier.height(12.dp))

            WelcomeFeatureCard(
                icon = Icons.Outlined.Security,
                text = "Document and report suspicious activity securely"
            )

            Spacer(Modifier.height(32.dp))
            error?.let { Text(it, color = SaayaTextSecondary) }
            onRetrySession?.let { retry ->
                TextButton(onClick = retry, enabled = !isLoading) { Text("Retry session") }
            }
        }
    }
}

@Composable
private fun WelcomeFeatureCard(
    icon: ImageVector,
    text: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        color = SaayaSurface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(25.dp),
                tint = SaayaBlack
            )

            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = SaayaBlack,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
