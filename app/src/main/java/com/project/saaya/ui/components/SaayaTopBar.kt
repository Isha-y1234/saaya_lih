package com.project.saaya.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.R
import com.project.saaya.ui.theme.SaayaBlack

@Composable
fun SaayaTopBar(
    onTrustedCircleClick: () -> Unit,
    onChatClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_saaya_logo),
            contentDescription = "Saaya",
            tint = Color.Unspecified,
            modifier = Modifier.size(28.dp)
        )

        Spacer(Modifier.width(8.dp))

        Text(
            text = "Saaya",
            color = SaayaBlack,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onTrustedCircleClick) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = "Trusted Circle",
                tint = SaayaBlack
            )
        }

        IconButton(onClick = onChatClick) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = "Chat",
                tint = SaayaBlack
            )
        }
    }
}
