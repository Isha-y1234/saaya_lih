package com.project.saaya.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.data.repository.SupportResources
import com.project.saaya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportResourcesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }

    fun open(action: String, uri: String) {
        try {
            context.startActivity(Intent(action, Uri.parse(uri)))
        } catch (_: Exception) {
            status = "No compatible app is available. Use the displayed number or official URL manually."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Legal & Help",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaayaBlack
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SaayaWhite
                )
            )
        },
        containerColor = SaayaWhite
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            /*
             * DISCLAIMER / INFO
             */
            Surface(
                color = SaayaSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SaayaBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Gavel,
                            contentDescription = null,
                            tint = SaayaBlack,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Important Information",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Essential details are available offline. Immediate danger: Call 112. " +
                                "Calls require phone service; websites require internet. AI analysis is not required for these resources.",
                        fontSize = 11.sp,
                        color = SaayaTextSecondary,
                        lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = SupportResources.disclaimer,
                        fontSize = 10.sp,
                        color = SaayaTextSecondary,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Light
                    )
                }
            }

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    color = SaayaEmergencyRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            /*
             * RESOURCE LIST
             */
            SupportResources.items.forEach { resource ->
                ResourceCard(
                    name = resource.name,
                    category = resource.category,
                    description = resource.description,
                    phone = resource.phone,
                    url = resource.url,
                    source = resource.source,
                    verified = resource.verified,
                    onDial = { resource.dialUri()?.let { open(Intent.ACTION_DIAL, it) } },
                    onVisit = { open(Intent.ACTION_VIEW, resource.url) }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResourceCard(
    name: String,
    category: String,
    description: String,
    phone: String?,
    url: String,
    source: String,
    verified: String,
    onDial: () -> Unit,
    onVisit: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SaayaWhite,
        border = BorderStroke(1.dp, SaayaBorder),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = category.uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = SaayaSuccessGreen,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = SaayaBlack
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = SaayaTextSecondary,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (phone != null) {
                    Button(
                        onClick = onDial,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SaayaBlack),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Outlined.Phone, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Call $phone", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onVisit,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SaayaBlack),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(14.dp), tint = SaayaBlack)
                    Spacer(Modifier.width(6.dp))
                    Text("Website", fontSize = 11.sp, color = SaayaBlack, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))
            
            HorizontalDivider(color = SaayaBorder, thickness = 0.5.dp)
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = "Official source: $source\nVerified: $verified",
                fontSize = 8.sp,
                color = SaayaTextSecondary,
                lineHeight = 12.sp
            )
        }
    }
}
