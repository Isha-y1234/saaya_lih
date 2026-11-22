package com.project.saaya.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaEmergencyRed
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun SaayaBottomNavigationBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit
) {
    val items = listOf(
        Triple(HomeTab.Home, Icons.Outlined.Home, "Home"),
        Triple(HomeTab.Journey, Icons.Outlined.Route, "Journey"),
        Triple(HomeTab.Network, Icons.Outlined.Map, "Network"),
        Triple(HomeTab.Activity, Icons.Outlined.History, "Activity"),
        Triple(HomeTab.Profile, Icons.Outlined.Person, "Profile")
    )

    Surface(
        color = SaayaWhite,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = 8.dp,
                    vertical = 7.dp
                ),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (tab, icon, label) ->
                val selected = selectedTab == tab

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = {
                            onTabSelected(tab)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (selected) {
                                SaayaBlack
                            } else {
                                SaayaTextSecondary
                            },
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Text(
                        text = label,
                        color = if (selected) {
                            SaayaBlack
                        } else {
                            SaayaTextSecondary
                        },
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SmallSosButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = SaayaWhite,
        border = BorderStroke(
            2.dp,
            SaayaEmergencyRed
        ),
        shadowElevation = 6.dp
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Text(
                "SOS",
                color = SaayaEmergencyRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}
