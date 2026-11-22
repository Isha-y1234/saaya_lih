package com.project.saaya.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.project.saaya.data.model.UserProfile
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.saayaDataStore by preferencesDataStore(
    name = "saaya_user_preferences"
)

class UserPreferencesRepository(
    private val context: Context
) {
    private object Keys {
        val AuthenticatedOwner = stringPreferencesKey("authenticated_owner")
        val FullName = stringPreferencesKey("full_name")
        val PhoneNumber = stringPreferencesKey("phone_number")
        val SosContactNumber = stringPreferencesKey("sos_contact_number")

        val AadhaarVerified =
            booleanPreferencesKey("aadhaar_verified")

        val PhoneVerified =
            booleanPreferencesKey("phone_verified")

        val IdentifiesAsWoman =
            booleanPreferencesKey("identifies_as_woman")

        val RegisterAsVolunteer =
            booleanPreferencesKey("register_as_volunteer")

        val PrivacyAccepted =
            booleanPreferencesKey("privacy_accepted")

        val OnboardingComplete =
            booleanPreferencesKey("onboarding_complete")
    }

    val userProfile: Flow<UserProfile?> =
        context.saayaDataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                UserProfile(
                    fullName =
                        preferences[Keys.FullName].orEmpty(),

                    phoneNumber =
                        preferences[Keys.PhoneNumber].orEmpty(),

                    sosContactNumber =
                        preferences[Keys.SosContactNumber].orEmpty(),

                    aadhaarVerified =
                        preferences[Keys.AadhaarVerified] ?: false,

                    phoneVerified =
                        preferences[Keys.PhoneVerified] ?: false,

                    identifiesAsWoman =
                        preferences[Keys.IdentifiesAsWoman] ?: false,

                    registerAsVolunteer =
                        preferences[Keys.RegisterAsVolunteer] ?: false,

                    privacyAccepted =
                        preferences[Keys.PrivacyAccepted] ?: false,

                    authenticatedUserId = preferences[Keys.AuthenticatedOwner].orEmpty(),
                    onboardingComplete =
                        preferences[Keys.OnboardingComplete] ?: false
                )
            }

    suspend fun saveUserProfile(
        profile: UserProfile
    ) {
        context.saayaDataStore.edit { preferences ->

            preferences[Keys.FullName] =
                profile.fullName

            preferences[Keys.PhoneNumber] =
                profile.phoneNumber

            preferences[Keys.SosContactNumber] =
                profile.sosContactNumber

            preferences[Keys.AadhaarVerified] =
                profile.aadhaarVerified

            preferences[Keys.PhoneVerified] =
                profile.phoneVerified

            preferences[Keys.IdentifiesAsWoman] =
                profile.identifiesAsWoman

            preferences[Keys.RegisterAsVolunteer] =
                profile.registerAsVolunteer

            preferences[Keys.PrivacyAccepted] =
                profile.privacyAccepted

            preferences[Keys.OnboardingComplete] =
                profile.onboardingComplete
        }
    }

    /** Keep profile preferences account-scoped; migrate away from old mock verification flags. */
    suspend fun bindAuthenticatedUser(userId: String, name: String) {
        require(userId.isNotBlank())
        context.saayaDataStore.edit { preferences ->
            if (preferences[Keys.AuthenticatedOwner] != userId) preferences.clear()
            preferences[Keys.AuthenticatedOwner] = userId
            preferences[Keys.FullName] = name
            preferences[Keys.AadhaarVerified] = false
            preferences[Keys.PhoneVerified] = false
        }
    }

    suspend fun updateSosContact(
        phoneNumber: String
    ) {
        context.saayaDataStore.edit { preferences ->
            preferences[Keys.SosContactNumber] =
                phoneNumber
        }
    }

    suspend fun clearUserData() {
        context.saayaDataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
