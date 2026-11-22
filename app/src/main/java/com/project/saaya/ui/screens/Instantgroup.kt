package com.project.saaya.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaBorder
import com.project.saaya.ui.theme.SaayaEmergencyRed
import com.project.saaya.ui.theme.SaayaSurface
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

private data class GroupMessage(
    val user: String,
    val message: String,
    val time: String,
    val isMe: Boolean
)

@Composable
fun InstantGroupScreen(
    onBack: () -> Unit
) {

    var groupCreated by rememberSaveable {
        mutableStateOf(false)
    }

    var messageText by rememberSaveable {
        mutableStateOf("")
    }

    var showLeaveDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val messages = remember {

        mutableStateListOf(

            GroupMessage(
                user = "Aarohi",
                message = "Hey, I’m nearby. Everything okay?",
                time = "4:18 PM",
                isMe = false
            ),

            GroupMessage(
                user = "Meera",
                message = "I can stay around the main gate for a while.",
                time = "4:19 PM",
                isMe = false
            ),

            GroupMessage(
                user = "You",
                message = "Thanks. I noticed something unusual near the entrance.",
                time = "4:20 PM",
                isMe = true
            ),

            GroupMessage(
                user = "Riya",
                message = "I'm about 2 minutes away.",
                time = "4:21 PM",
                isMe = false
            ),

            GroupMessage(
                user = "Aarohi",
                message = "Okay, we’re here with you.",
                time = "4:22 PM",
                isMe = false
            )
        )
    }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {

        if (messages.isNotEmpty()) {

            listState.animateScrollToItem(
                messages.lastIndex
            )
        }
    }

    Scaffold(
        containerColor = SaayaWhite,

        bottomBar = {

            if (groupCreated) {

                Surface(
                    color = SaayaWhite,
                    shadowElevation = 8.dp
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(
                                horizontal = 12.dp,
                                vertical = 9.dp
                            ),

                        verticalAlignment =
                            Alignment.Bottom
                    ) {

                        OutlinedTextField(
                            value = messageText,

                            onValueChange = {
                                messageText = it
                            },

                            modifier =
                                Modifier.weight(1f),

                            placeholder = {
                                Text(
                                    "Message the group...",
                                    fontSize = 11.sp
                                )
                            },

                            maxLines = 4,

                            shape =
                                RoundedCornerShape(22.dp)
                        )

                        Spacer(
                            Modifier.width(7.dp)
                        )

                        Surface(
                            onClick = {

                                val trimmed =
                                    messageText.trim()

                                if (trimmed.isNotEmpty()) {

                                    messages.add(
                                        GroupMessage(
                                            user = "You",
                                            message = trimmed,
                                            time = "Now",
                                            isMe = true
                                        )
                                    )

                                    messageText = ""
                                }
                            },

                            modifier =
                                Modifier.size(48.dp),

                            shape =
                                CircleShape,

                            color =
                                SaayaBlack
                        ) {

                            Box(
                                contentAlignment =
                                    Alignment.Center
                            ) {

                                Icon(
                                    imageVector =
                                        Icons.AutoMirrored.Outlined.Send,

                                    contentDescription =
                                        "Send",

                                    tint =
                                        SaayaWhite,

                                    modifier =
                                        Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            /*
             * Move the ENTIRE screen content down.
             * The header is no longer inside Scaffold's topBar,
             * so the spacing actually moves the header itself.
             */
            Spacer(
                Modifier.height(28.dp)
            )

            /*
             * Header
             */
            Surface(
                modifier =
                    Modifier.fillMaxWidth(),

                color =
                    SaayaWhite,

                shadowElevation =
                    2.dp
            ) {

                Column {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 4.dp),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        IconButton(
                            onClick = onBack
                        ) {

                            Icon(
                                imageVector =
                                    Icons.AutoMirrored.Outlined.ArrowBack,

                                contentDescription =
                                    "Back",

                                tint =
                                    SaayaBlack
                            )
                        }

                        Column(
                            modifier =
                                Modifier.weight(1f)
                        ) {

                            Text(
                                text =
                                    "Instant Group",

                                fontSize =
                                    18.sp,

                                fontWeight =
                                    FontWeight.SemiBold,

                                color =
                                    SaayaBlack
                            )

                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                Surface(
                                    modifier =
                                        Modifier.size(6.dp),

                                    shape =
                                        CircleShape,

                                    color =
                                        if (groupCreated)
                                            Color(0xFF6F9278)
                                        else
                                            SaayaTextSecondary
                                ) {}

                                Spacer(
                                    Modifier.width(5.dp)
                                )

                                Text(
                                    text =
                                        if (groupCreated)
                                            "4 people nearby"
                                        else
                                            "Not active",

                                    fontSize =
                                        9.sp,

                                    color =
                                        SaayaTextSecondary
                                )
                            }
                        }

                        Surface(
                            modifier =
                                Modifier.size(38.dp),

                            shape =
                                CircleShape,

                            color =
                                SaayaSurface
                        ) {

                            Box(
                                contentAlignment =
                                    Alignment.Center
                            ) {

                                Icon(
                                    imageVector =
                                        Icons.Outlined.Groups,

                                    contentDescription =
                                        null,

                                    tint =
                                        SaayaBlack,

                                    modifier =
                                        Modifier.size(19.dp)
                                )
                            }
                        }
                    }

                    if (groupCreated) {

                        Surface(
                            modifier =
                                Modifier.fillMaxWidth(),

                            color =
                                Color(0xFFF7F8F7)
                        ) {

                            Row(
                                modifier =
                                    Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 9.dp
                                    ),

                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                Icon(
                                    imageVector =
                                        Icons.Outlined.LocationOn,

                                    contentDescription =
                                        null,

                                    tint =
                                        SaayaTextSecondary,

                                    modifier =
                                        Modifier.size(15.dp)
                                )

                                Spacer(
                                    Modifier.width(6.dp)
                                )

                                Text(
                                    text =
                                        "Temporary group • within 100 m",

                                    fontSize =
                                        9.sp,

                                    color =
                                        SaayaTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            /*
             * Main content
             */
            if (!groupCreated) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),

                    verticalArrangement =
                        Arrangement.Center,

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Surface(
                        modifier =
                            Modifier.size(78.dp),

                        shape =
                            CircleShape,

                        color =
                            SaayaSurface
                    ) {

                        Box(
                            contentAlignment =
                                Alignment.Center
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Outlined.Groups,

                                contentDescription =
                                    null,

                                tint =
                                    SaayaBlack,

                                modifier =
                                    Modifier.size(38.dp)
                            )
                        }
                    }

                    Spacer(
                        Modifier.height(18.dp)
                    )

                    Text(
                        text =
                            "Instant Group",

                        color =
                            SaayaBlack,

                        fontSize =
                            22.sp,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        text =
                            "Create a temporary safety group with opted-in Saaya users around you.",

                        color =
                            SaayaTextSecondary,

                        fontSize =
                            11.sp,

                        textAlign =
                            TextAlign.Center,

                        lineHeight =
                            17.sp
                    )

                    Spacer(
                        Modifier.height(24.dp)
                    )

                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),

                        shape =
                            RoundedCornerShape(15.dp),

                        color =
                            Color(0xFFF8F8F8),

                        border =
                            BorderStroke(
                                1.dp,
                                SaayaBorder
                            )
                    ) {

                        Column(
                            modifier =
                                Modifier.padding(15.dp)
                        ) {

                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                Icon(
                                    imageVector =
                                        Icons.Outlined.LocationOn,

                                    contentDescription =
                                        null,

                                    tint =
                                        SaayaTextSecondary,

                                    modifier =
                                        Modifier.size(18.dp)
                                )

                                Spacer(
                                    Modifier.width(8.dp)
                                )

                                Column {

                                    Text(
                                        text =
                                            "Nearby users",

                                        fontSize =
                                            11.sp,

                                        fontWeight =
                                            FontWeight.SemiBold,

                                        color =
                                            SaayaBlack
                                    )

                                    Text(
                                        text =
                                            "Only opted-in users within 100 m can join.",

                                        fontSize =
                                            9.sp,

                                        color =
                                            SaayaTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(
                        Modifier.height(18.dp)
                    )

                    Button(
                        onClick = {
                            groupCreated = true
                        },

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp),

                        shape =
                            RoundedCornerShape(12.dp),

                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    SaayaBlack
                            )
                    ) {

                        Icon(
                            imageVector =
                                Icons.Outlined.Groups,

                            contentDescription =
                                null,

                            modifier =
                                Modifier.size(19.dp)
                        )

                        Spacer(
                            Modifier.width(8.dp)
                        )

                        Text(
                            text =
                                "Create Instant Group",

                            fontSize =
                                13.sp,

                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }
                }

            } else {

                Column(
                    modifier =
                        Modifier.fillMaxSize()
                ) {

                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),

                        color =
                            Color(0xFFF9F9F9)
                    ) {

                        Row(
                            modifier =
                                Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 10.dp
                                ),

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Outlined.Warning,

                                contentDescription =
                                    null,

                                tint =
                                    Color(0xFF8B7777),

                                modifier =
                                    Modifier.size(16.dp)
                            )

                            Spacer(
                                Modifier.width(7.dp)
                            )

                            Text(
                                text =
                                    "Share only what is necessary. This group is temporary.",

                                fontSize =
                                    9.sp,

                                color =
                                    SaayaTextSecondary
                            )
                        }
                    }

                    LazyColumn(
                        modifier =
                            Modifier.fillMaxSize(),

                        state =
                            listState,

                        contentPadding =
                            PaddingValues(
                                horizontal = 14.dp,
                                vertical = 14.dp
                            ),

                        verticalArrangement =
                            Arrangement.spacedBy(11.dp)
                    ) {

                        item {

                            Text(
                                text =
                                    "TODAY",

                                fontSize =
                                    8.sp,

                                fontWeight =
                                    FontWeight.Bold,

                                color =
                                    SaayaTextSecondary,

                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            bottom = 2.dp
                                        ),

                                textAlign =
                                    TextAlign.Center
                            )
                        }

                        items(messages) { message ->

                            GroupMessageBubble(
                                message = message
                            )
                        }

                        item {

                            Spacer(
                                Modifier.height(4.dp)
                            )

                            Surface(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showLeaveDialog = true
                                        },

                                shape =
                                    RoundedCornerShape(12.dp),

                                color =
                                    Color(0xFFFAFAFA),

                                border =
                                    BorderStroke(
                                        1.dp,
                                        SaayaBorder
                                    )
                            ) {

                                Text(
                                    text =
                                        "Leave / dissolve group",

                                    fontSize =
                                        9.sp,

                                    fontWeight =
                                        FontWeight.SemiBold,

                                    color =
                                        SaayaTextSecondary,

                                    modifier =
                                        Modifier.padding(12.dp),

                                    textAlign =
                                        TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLeaveDialog) {

        AlertDialog(

            onDismissRequest = {
                showLeaveDialog = false
            },

            title = {
                Text(
                    "Leave group?"
                )
            },

            text = {
                Text(
                    "This temporary group will no longer be available to you."
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {

                        showLeaveDialog = false
                        groupCreated = false
                    }
                ) {

                    Text(
                        "Leave",

                        color =
                            SaayaEmergencyRed,

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        showLeaveDialog = false
                    }
                ) {

                    Text("Cancel")
                }
            }
        )
    }
}


@Composable
private fun GroupMessageBubble(
    message: GroupMessage
) {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        horizontalArrangement =
            if (message.isMe)
                Arrangement.End
            else
                Arrangement.Start,

        verticalAlignment =
            Alignment.Bottom
    ) {

        if (!message.isMe) {

            Surface(
                modifier =
                    Modifier.size(32.dp),

                shape =
                    CircleShape,

                color =
                    SaayaSurface
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        text =
                            message.user
                                .first()
                                .uppercase(),

                        fontSize =
                            11.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            SaayaBlack
                    )
                }
            }

            Spacer(
                Modifier.width(8.dp)
            )
        }

        Column(
            horizontalAlignment =
                if (message.isMe)
                    Alignment.End
                else
                    Alignment.Start,

            modifier =
                Modifier.widthIn(
                    max = 285.dp
                )
        ) {

            if (!message.isMe) {

                Text(
                    text =
                        message.user,

                    fontSize =
                        8.sp,

                    fontWeight =
                        FontWeight.SemiBold,

                    color =
                        SaayaTextSecondary,

                    modifier =
                        Modifier.padding(
                            start = 5.dp,
                            bottom = 3.dp
                        )
                )
            }

            Surface(
                shape =
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart =
                            if (message.isMe)
                                16.dp
                            else
                                4.dp,
                        bottomEnd =
                            if (message.isMe)
                                4.dp
                            else
                                16.dp
                    ),

                color =
                    if (message.isMe)
                        SaayaBlack
                    else
                        SaayaSurface
            ) {

                Column(
                    modifier =
                        Modifier.padding(
                            horizontal = 13.dp,
                            vertical = 9.dp
                        )
                ) {

                    Text(
                        text =
                            message.message,

                        fontSize =
                            10.sp,

                        color =
                            if (message.isMe)
                                SaayaWhite
                            else
                                SaayaBlack,

                        lineHeight =
                            15.sp
                    )

                    Spacer(
                        Modifier.height(3.dp)
                    )

                    Text(
                        text =
                            message.time,

                        fontSize =
                            7.sp,

                        color =
                            if (message.isMe)
                                SaayaWhite.copy(
                                    alpha = 0.55f
                                )
                            else
                                SaayaTextSecondary
                    )
                }
            }
        }
    }
}