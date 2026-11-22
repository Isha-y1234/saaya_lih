package com.project.saaya.data.model

data class UserProfile(
    val fullName: String = "",
    val phoneNumber: String = "",
    val sosContactNumber: String = "",
    val aadhaarVerified: Boolean = false,
    val phoneVerified: Boolean = false,
    val identifiesAsWoman: Boolean = false,
    val registerAsVolunteer: Boolean = false,
    val privacyAccepted: Boolean = false,
    val onboardingComplete: Boolean = false,
    val authenticatedUserId: String = ""
)
