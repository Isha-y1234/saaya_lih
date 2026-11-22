package com.project.saaya.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.project.saaya.notifications.InvitationNotificationTarget
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.project.saaya.data.local.UserPreferencesRepository
import com.project.saaya.data.model.UserProfile
import com.project.saaya.ui.screens.*
import com.project.saaya.ui.state.OnboardingState
import com.project.saaya.ui.state.AuthViewModel
import com.project.saaya.ui.state.TrustedCircleViewModel
import com.project.saaya.data.remote.TrustedCircleDependencies
import com.project.saaya.data.remote.IncidentApiService
import com.project.saaya.data.repository.IncidentRepository
import com.project.saaya.ui.state.IncidentViewModel
import com.project.saaya.ui.sos.IncidentSosHost
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.project.saaya.notifications.FcmRegistrationEffect
import com.project.saaya.notifications.SosNotificationPayload
import com.project.saaya.data.remote.NotificationApiService
import com.project.saaya.notifications.SosNotifications
import com.project.saaya.data.remote.LiveSafetyApiService
import com.project.saaya.data.repository.LiveSafetyRepository
import com.project.saaya.ui.state.LiveSafetyViewModel
import com.project.saaya.live.LiveKitMediaClient
import com.project.saaya.data.repository.IncidentChatRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

private object Routes {
    const val Community = "onboarding/community"

    const val Home = "home"
    const val Journey = "journey"
    const val Network = "network"
    const val Activity = "activity"
    const val Profile = "profile"

    const val Chat = "chat"
    const val TrustedCircle = "trusted_circle"
    const val IncidentAlert = "incident_alert/{incidentId}"
    const val LiveSafety = "live_safety/{incidentId}?rejoin={rejoin}"
    const val IncidentHistory = "incident_history"
    const val LiveSafetyList = "live_safety_sessions"
    const val SosCountdown = "sos_countdown"
}

@Composable
fun SaayaNavHost(
    repository: UserPreferencesRepository,
    authViewModel: AuthViewModel,
    pendingSosAlert: SosNotificationAlertPayload? = null,
    onSosAlertHandled: () -> Unit = {},
    pendingInvitation: InvitationNotificationTarget? = null,
    onInvitationHandled: () -> Unit = {}
) {
    val authState by authViewModel.uiState.collectAsState()
    val activityContext = LocalContext.current
    val storedProfile by repository.userProfile
        .collectAsState(initial = null)

    var splashFinished by rememberSaveable {
        mutableStateOf(false)
    }

    if (!splashFinished || storedProfile == null) {
        SplashScreen(
            onFinished = {
                splashFinished = true
            }
        )
        return
    }

    val user = authState.user
    var publicResources by rememberSaveable { mutableStateOf(false) }
    if (user == null) {
        if (publicResources) {
            SupportResourcesScreen(onBack = { publicResources = false })
            return
        }
        WelcomeScreen(
            onResources = { publicResources = true },
            onGetStarted = { authViewModel.signIn(activityContext) },
            isLoading = authState.isLoading,
            error = authState.error,
            onRetrySession = if (authState.canRetryRestore) ({ authViewModel.retryRestore() }) else null
        )
        return
    }
    val currentProfile = storedProfile ?: return
    if (currentProfile.authenticatedUserId != user.id) {
        SplashScreen(onFinished = {})
        return
    }
    key(user.id, "profile-safety-settings-v2") {
        val navController = rememberNavController()
        val activityApi = remember(authViewModel.retrofit) {
            authViewModel.retrofit.create(com.project.saaya.data.remote.ActivityEvidenceApi::class.java)
        }
        LaunchedEffect(user.id) {
            com.project.saaya.data.repository.ActivityUploadWork.cancelLegacyUploads(activityContext, user.id)
        }
        FcmRegistrationEffect(user.id, currentProfile.onboardingComplete)
        val notificationApi = remember(authViewModel.retrofit) {
            authViewModel.retrofit.create(NotificationApiService::class.java)
        }
        LaunchedEffect(pendingSosAlert, user.id, currentProfile.onboardingComplete) {
            val alert = pendingSosAlert ?: return@LaunchedEffect
            val destination = alert.destinationFor(user.id, currentProfile.onboardingComplete)
            if (destination != null) {
                navController.navigate(destination) { launchSingleTop = true }
                onSosAlertHandled()
            } else if (!alert.belongsTo(user.id)) {
                onSosAlertHandled()
            }
        }
        val circleFactory = remember(user.id, authViewModel.retrofit) {
            TrustedCircleDependencies.viewModelFactory(user.id, authViewModel.retrofit)
        }
        val circleViewModel: TrustedCircleViewModel = viewModel(
            key = "trusted-circle:${user.id}", factory = circleFactory)
        DisposableEffect(circleViewModel) {
            onDispose { circleViewModel.clearSessionState() }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, circleViewModel, currentProfile.onboardingComplete) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && currentProfile.onboardingComplete) circleViewModel.refresh()
            }
            lifecycle.addObserver(observer)
            if (currentProfile.onboardingComplete && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                circleViewModel.refresh()
            }
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(pendingInvitation, user.id, currentProfile.onboardingComplete) {
            val target = pendingInvitation ?: return@LaunchedEffect
            val destination = target.destinationFor(user.id, currentProfile.onboardingComplete)
            if (destination != null) {
                navController.navigate(destination) { launchSingleTop = true }
                circleViewModel.refresh()
                onInvitationHandled()
            } else if (!target.belongsTo(user.id)) {
                onInvitationHandled()
            }
        }
        val coroutineScope = rememberCoroutineScope()

        val onboardingState = remember {
            OnboardingState()
        }

        val startDestination =
            if (currentProfile.onboardingComplete) {
                Routes.Home
            } else {
                Routes.Community
            }

        val incidentFactory = remember(user.id, authViewModel.retrofit) {
            IncidentViewModel.Factory(IncidentRepository(
                authViewModel.retrofit.create(IncidentApiService::class.java), user.id
            ))
        }
        val incidentViewModel: IncidentViewModel = viewModel(
            key = "incidents:${user.id}", factory = incidentFactory
        )

        val liveSafetyRepository = remember(authViewModel.retrofit) {
            LiveSafetyRepository(authViewModel.retrofit.create(LiveSafetyApiService::class.java))
        }
        val context = LocalContext.current
        val liveSafetyFactory = remember(liveSafetyRepository) {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == LiveSafetyViewModel::class.java)
                    @Suppress("UNCHECKED_CAST")
                    return LiveSafetyViewModel(
                        liveSafetyRepository,
                        IncidentChatRepository(authViewModel.retrofit),
                        LiveKitMediaClient(context.applicationContext),
                        onSessionEnded = { SosNotifications.clear(context.applicationContext, it) }
                    ) as T
                }
            }
        }
        val liveSafetyViewModel: LiveSafetyViewModel = viewModel(
            key = "live-safety-global:${user.id}", factory = liveSafetyFactory
        )

        IncidentSosHost(incidentViewModel, liveSafetyViewModel, onLiveSafetyRequested = {
            navController.navigate("live_safety/$it") { launchSingleTop = true }
        }) {
        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
            composable(Routes.Community) {
                CommunityConfirmationScreen(
                    state = onboardingState,
                    onBack = { authViewModel.signOut() },
                    onComplete = {
                        val completedProfile =
                            UserProfile(
                                fullName = user.name,
                                identifiesAsWoman = onboardingState.identifiesAsWoman,
                                registerAsVolunteer = onboardingState.registerAsVolunteer,
                                privacyAccepted = onboardingState.privacyAccepted,
                                onboardingComplete = true
                            )

                        coroutineScope.launch {
                            repository.saveUserProfile(completedProfile)

                            navController.navigate(Routes.Home) {
                                popUpTo(Routes.Community) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }

            composable(Routes.Home) {
                HomeScreen(
                    userProfile = currentProfile,
                    onJourneyClick = { navController.navigate(Routes.Journey) { launchSingleTop = true } },
                    onNetworkClick = { navController.navigate(Routes.Network) { launchSingleTop = true } },
                    onActivityClick = { navController.navigate(Routes.Activity) { launchSingleTop = true } },
                    onProfileClick = { navController.navigate(Routes.Profile) { launchSingleTop = true } },
                    onChatClick = { navController.navigate(Routes.Chat) },
                    onAllIncidentsClick = { navController.navigate(Routes.IncidentHistory) { launchSingleTop = true } },
                    onIncidentChatClick = { incidentId ->
                        val incident = incidentViewModel.uiState.value.activeIncident
                        if (incident?.id == incidentId) {
                            navController.navigate("live_safety/$incidentId") { launchSingleTop = true }
                        } else {
                            navController.navigate("incident_chat/$incidentId")
                        }
                    },
                    onEvidenceClick = { navController.navigate("evidence/$it") },
                    onReportsClick = { navController.navigate("reports/$it") },
                    onResourcesClick = { navController.navigate("support_resources") },
                    onLiveSafetyClick = { navController.navigate("live_safety/$it") { launchSingleTop = true } },
                    onTrustedCircleClick = { navController.navigate(Routes.TrustedCircle) },
                    onSaveSosContact = { phoneNumber ->
                        coroutineScope.launch {
                            repository.updateSosContact(phoneNumber)
                        }
                    },
                    onTriggerSos = {
                        navController.navigate(Routes.SosCountdown)
                    },
                    onResetOnboarding = {
                        authViewModel.signOut()
                    }
                )
            }

            composable(Routes.SosCountdown) {
                val callSosContact = com.project.saaya.ui.sos.rememberSosCallAction(
                    phoneNumber = currentProfile.sosContactNumber,
                    onError = {},
                    latitude = null,
                    longitude = null
                )

                SosCountdownScreen(
                    onCancel = { navController.popBackStack() },
                    onActivated = {
                        callSosContact()
                        navController.popBackStack()
                    }
                )
            }

            composable(Routes.Journey) {
                JourneyScreen(
                    userProfile = currentProfile,
                    onHomeClick = { navController.navigate(Routes.Home) { launchSingleTop = true } },
                    onNetworkClick = { navController.navigate(Routes.Network) { launchSingleTop = true } },
                    onActivityClick = { navController.navigate(Routes.Activity) { launchSingleTop = true } },
                    onProfileClick = { navController.navigate(Routes.Profile) { launchSingleTop = true } },
                    onTrustedCircleClick = { navController.navigate(Routes.TrustedCircle) },
                    onChatClick = { navController.navigate(Routes.Chat) }
                )
            }

            composable(Routes.Network) {
                NetworkScreen(
                    userProfile = currentProfile,
                    onHomeClick = { navController.navigate(Routes.Home) { launchSingleTop = true } },
                    onJourneyClick = { navController.navigate(Routes.Journey) { launchSingleTop = true } },
                    onActivityClick = { navController.navigate(Routes.Activity) { launchSingleTop = true } },
                    onProfileClick = { navController.navigate(Routes.Profile) { launchSingleTop = true } },
                    onTrustedCircleClick = { navController.navigate(Routes.TrustedCircle) },
                    onChatClick = { navController.navigate(Routes.Chat) },
                    onResourcesClick = { navController.navigate("support_resources") }
                )
            }

            composable(Routes.Activity) {
                key(user.id) { ActivityScreen(
                    userProfile = currentProfile,
                    userId = user.id,
                    api = activityApi,
                    onHomeClick = { navController.navigate(Routes.Home) { launchSingleTop = true } },
                    onJourneyClick = { navController.navigate(Routes.Journey) { launchSingleTop = true } },
                    onNetworkClick = { navController.navigate(Routes.Network) { launchSingleTop = true } },
                    onProfileClick = { navController.navigate(Routes.Profile) { launchSingleTop = true } },
                    onTrustedCircleClick = { navController.navigate(Routes.TrustedCircle) },
                    onChatClick = { navController.navigate(Routes.Chat) }
                ) }
            }

            composable(Routes.Profile) {
                ProfileScreen(
                    profile = currentProfile,
                    saayaId = user.saayaId,
                    isSaayaIdLoading = authState.isRefreshingProfile,
                    saayaIdError = authState.profileError,
                    onRetrySaayaId = { authViewModel.refreshProfile() },
                    onHomeClick = { navController.navigate(Routes.Home) { launchSingleTop = true } },
                    onJourneyClick = { navController.navigate(Routes.Journey) { launchSingleTop = true } },
                    onNetworkClick = { navController.navigate(Routes.Network) { launchSingleTop = true } },
                    onActivityClick = { navController.navigate(Routes.Activity) { launchSingleTop = true } },
                    onTrustedCircleClick = { navController.navigate(Routes.TrustedCircle) },
                    onChatClick = { navController.navigate(Routes.Chat) },
                    onSaveSosContact = { phoneNumber ->
                        coroutineScope.launch {
                            repository.updateSosContact(phoneNumber)
                        }
                    },
                    onLiveSafetySessions = { navController.navigate(Routes.LiveSafetyList) { launchSingleTop = true } },
                    onResetOnboarding = {
                        authViewModel.signOut()
                    }
                )
            }

            composable(Routes.Chat) {
                InstantGroupScreen(onBack = { navController.popBackStack() })
            }

            @Suppress("DEPRECATION")
            composable(Routes.TrustedCircle) {
                TrustedCircleScreen(
                    onBack = { navController.popBackStack() },
                    trustedCircleViewModel = circleViewModel
                )
            }
            composable(Routes.IncidentAlert, arguments = listOf(navArgument("incidentId") { type = NavType.StringType })) { entry ->
                val incidentId = entry.arguments?.getString("incidentId").orEmpty()
                IncidentAlertScreen(incidentId, notificationApi,
                    onBack = { navController.popBackStack() },
                    onEvidence = { navController.navigate("evidence/$incidentId") },
                    onReports = { navController.navigate("reports/$incidentId") },
                    onChat = {
                        navController.navigate("live_safety/$incidentId") { launchSingleTop = true }
                    },
                    onWatchLive = { navController.navigate("live_safety/$incidentId") { launchSingleTop = true } })
            }
            composable("incident_chat/{incidentId}", arguments = listOf(navArgument("incidentId") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("incidentId").orEmpty()
                val chatRepository = remember(user.id, id, authViewModel.retrofit) {
                    com.project.saaya.data.repository.IncidentChatRepository(authViewModel.retrofit)
                }
                IncidentChatScreen(id, user.id, chatRepository, onBack = { navController.popBackStack() },
                    onReports = { navController.navigate("reports/$id") },
                    onEvidence = { navController.navigate("evidence/$id") })
            }
            composable("evidence/{incidentId}", arguments = listOf(navArgument("incidentId") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("incidentId").orEmpty()
                val evidenceApi = remember(authViewModel.retrofit) {
                    authViewModel.retrofit.create(com.project.saaya.data.remote.EvidenceApiService::class.java)
                }
                EvidenceScreen(id, user.id, evidenceApi, onBack = { navController.popBackStack() }, onReports = { navController.navigate("reports/$id") })
            }
            composable("support_resources") { SupportResourcesScreen(onBack = { navController.popBackStack() }) }
            composable("reports/{incidentId}", arguments = listOf(navArgument("incidentId") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("incidentId").orEmpty()
                val api = remember(authViewModel.retrofit) { authViewModel.retrofit.create(com.project.saaya.data.remote.ReportsApiService::class.java) }
                IncidentReportsScreen(id,user.id,api,onBack = { navController.popBackStack() },onResources = { navController.navigate("support_resources") })
            }
            composable(Routes.IncidentHistory) {
                IncidentHistoryScreen(incidentViewModel, liveSafetyRepository, user.id,
                    onBack = { navController.popBackStack() },
                    onChat = { navController.navigate("incident_chat/$it") },
                    onReports = { navController.navigate("reports/$it") },
                    onRejoin = { navController.navigate("live_safety/$it?rejoin=true") { launchSingleTop = true } })
            }
            composable(Routes.LiveSafety, arguments = listOf(
                navArgument("incidentId") { type = NavType.StringType },
                navArgument("rejoin") { type = NavType.BoolType; defaultValue = false }
            )) { entry ->
                val id = entry.arguments?.getString("incidentId").orEmpty()
                LiveSafetyScreen(
                    incidentId = id,
                    userId = user.id,
                    onBack = { navController.popBackStack() },
                    onEvidence = { navController.navigate("evidence/$id") },
                    onEndIncident = { incidentViewModel.endIncident(id) },
                    rejoinOnly = entry.arguments?.getBoolean("rejoin") == true
                )
            }
            composable(Routes.LiveSafetyList) {
                LiveSafetyListScreen(liveSafetyRepository, onBack = { navController.popBackStack() },
                    onOpen = { navController.navigate("live_safety/$it") })
            }
        }
        }
    }
}

typealias SosNotificationAlertPayload = SosNotificationPayload
