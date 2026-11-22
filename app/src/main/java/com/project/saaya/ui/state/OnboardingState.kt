package com.project.saaya.ui.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class OnboardingState {

    var fullName by mutableStateOf("")

    var aadhaarNumber by mutableStateOf("")
    var aadhaarVerified by mutableStateOf(false)

    var phoneNumber by mutableStateOf("")
    var otp by mutableStateOf("")
    var otpSent by mutableStateOf(false)
    var phoneVerified by mutableStateOf(false)

    var identifiesAsWoman by mutableStateOf(false)
    var registerAsVolunteer by mutableStateOf(false)
    var privacyAccepted by mutableStateOf(false)
}
