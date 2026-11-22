package com.project.saaya.ui.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaSurface
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun IsolateScreen(
    contentPadding: PaddingValues
) {

    var active by rememberSaveable {
        mutableStateOf(false)
    }

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    SaayaWhite
                )
                .padding(
                    contentPadding
                )
                .statusBarsPadding()
                .padding(
                    horizontal = 20.dp,
                    vertical = 18.dp
                ),

        verticalArrangement =
            Arrangement.Center,

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Surface(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(20.dp),

            color =
                SaayaSurface
        ) {

            Column(

                modifier =
                    Modifier.padding(24.dp),

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {

                Icon(

                    imageVector =
                        Icons.Outlined.Lock,

                    contentDescription =
                        null,

                    tint =
                        SaayaBlack
                )


                Spacer(
                    Modifier.height(14.dp)
                )


                Text(

                    text =
                        if (active)
                            "Isolation Mode Active"
                        else
                            "Isolation Mode",

                    color =
                        SaayaBlack,

                    fontSize = 21.sp
                )


                Spacer(
                    Modifier.height(7.dp)
                )


                Text(

                    text =
                        if (active)
                            "Nearby Saaya users can be alerted if you need help."
                        else
                            "Stay connected with Saaya while you feel uncomfortable or unsafe.",

                    color =
                        SaayaTextSecondary,

                    fontSize = 11.sp
                )


                Spacer(
                    Modifier.height(20.dp)
                )


                Button(

                    onClick = {
                        active = !active
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    shape =
                        RoundedCornerShape(12.dp),

                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                SaayaBlack
                        )
                ) {

                    Text(

                        text =
                            if (active)
                                "Turn Off Isolation Mode"
                            else
                                "Activate Isolation Mode"
                    )
                }
            }
        }
    }
}