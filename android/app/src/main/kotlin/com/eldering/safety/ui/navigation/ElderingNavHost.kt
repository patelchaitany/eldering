package com.eldering.safety.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.eldering.safety.R
import com.eldering.safety.core.appGraph
import com.eldering.safety.ui.alert.SosRoute
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.contacts.EmergencyContactsRoute
import com.eldering.safety.ui.familyalert.FamilyAlertRoute
import com.eldering.safety.ui.home.HomeRoute
import com.eldering.safety.ui.map.LocalMapStyleUrl
import com.eldering.safety.ui.onboarding.AuthRoute
import com.eldering.safety.ui.onboarding.FamilySetupRoute
import com.eldering.safety.ui.onboarding.InviteRoute
import com.eldering.safety.ui.onboarding.WelcomeScreen
import com.eldering.safety.ui.permissions.PermissionsRoute
import com.eldering.safety.ui.settings.FamilyManageRoute
import com.eldering.safety.ui.settings.SettingsRoute
import com.eldering.safety.ui.sharing.SharingRoute
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.trip.TripRoute
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop

/**
 * App root: decides the start screen, then hosts navigation.
 *
 * @param pendingIncident an incident id from a family-alert notification (see `MainActivity.familyAlertIntent`);
 *   opened once the user is signed in, then reported back through [onIncidentConsumed].
 */
@Composable
fun ElderingRoot(pendingIncident: StateFlow<String?>, onIncidentConsumed: () -> Unit) {
    val graph = LocalContext.current.appGraph
    CompositionLocalProvider(LocalMapStyleUrl provides graph.config.mapStyleUrl) {
        val startViewModel = appViewModel { StartViewModel(it.authRepository, it.appPreferences) }
        val start by startViewModel.start.collectAsStateWithLifecycle()
        when (val destination = start) {
            null -> Splash()
            else -> ElderingNavHost(destination, pendingIncident, onIncidentConsumed)
        }
    }
}

@Composable
private fun Splash() {
    val label = stringResource(R.string.app_name)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Eldering.palette.ground)
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Eldering.palette.safe),
        ) {
            Icon(ElderingIcons.ShieldCheck, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun ElderingNavHost(
    start: StartDestination,
    pendingIncident: StateFlow<String?>,
    onIncidentConsumed: () -> Unit,
) {
    val graph = LocalContext.current.appGraph
    val nav = rememberNavController()

    // Signed out anywhere (Settings, or the server rejected the refresh token): back to Welcome, nothing to go back to.
    LaunchedEffect(nav) {
        graph.authRepository.isSignedIn.drop(1).collect { signedIn ->
            if (!signedIn) nav.navigate(Route.Welcome) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }
    val incident by pendingIncident.collectAsStateWithLifecycle()
    LaunchedEffect(incident) {
        val id = incident ?: return@LaunchedEffect
        if (graph.authRepository.currentSession() != null) nav.navigate(Route.FamilyAlert(id)) { launchSingleTop = true }
        onIncidentConsumed()
    }

    NavHost(navController = nav, startDestination = start.toRoute()) {
        composable<Route.Welcome> {
            WelcomeScreen(
                onCreateAccount = { nav.navigate(Route.Auth(register = true)) },
                onLogIn = { nav.navigate(Route.Auth(register = false)) },
            )
        }
        composable<Route.Auth> { entry ->
            val route = entry.toRoute<Route.Auth>()
            AuthRoute(
                register = route.register,
                onBack = { nav.back() },
                onSignedIn = { destination -> nav.replaceAll(destination.toRoute()) },
            )
        }
        composable<Route.FamilySetup> { entry ->
            val route = entry.toRoute<Route.FamilySetup>()
            FamilySetupRoute(canGoBack = route.canGoBack, onBack = { nav.back() }, onNavigate = { nav.replaceAll(it) })
        }
        composable<Route.Invite> { entry ->
            val route = entry.toRoute<Route.Invite>()
            InviteRoute(
                familyId = route.familyId,
                code = route.code,
                expiresAtMs = route.expiresAtMs,
                onboarding = route.onboarding,
                onBack = { nav.back() },
                onContinue = { nav.replaceAll(Route.Permissions(onboarding = true)) },
            )
        }
        composable<Route.Permissions> { entry ->
            val route = entry.toRoute<Route.Permissions>()
            PermissionsRoute(
                onboarding = route.onboarding,
                onFinished = { nav.replaceAll(Route.Home) },
                onBack = { nav.back() },
            )
        }
        composable<Route.Home> {
            HomeRoute(onNavigate = { nav.navigate(it) { launchSingleTop = true } })
        }
        composable<Route.Trip> { entry ->
            val route = entry.toRoute<Route.Trip>()
            TripRoute(route.userId, route.incidentId, route.name, onBack = { nav.back() })
        }
        composable<Route.FamilyAlert> { entry ->
            val route = entry.toRoute<Route.FamilyAlert>()
            FamilyAlertRoute(route.incidentId, onBack = { nav.back() }, onNavigate = { nav.navigate(it) })
        }
        composable<Route.Sos> {
            SosRoute(onClose = { nav.backToHome() })
        }
        composable<Route.Sharing> {
            SharingRoute(
                onBack = { nav.back() },
                onContacts = { nav.navigate(Route.Contacts) { launchSingleTop = true } },
                onSettings = { nav.navigate(Route.Settings) { launchSingleTop = true } },
            )
        }
        composable<Route.Contacts> { EmergencyContactsRoute(onBack = { nav.back() }) }
        composable<Route.Settings> {
            SettingsRoute(onBack = { nav.back() }, onNavigate = { nav.navigate(it) { launchSingleTop = true } })
        }
        composable<Route.Family> {
            FamilyManageRoute(
                onBack = { nav.back() },
                onNavigate = { route ->
                    if (route is Route.FamilySetup && !route.canGoBack) nav.replaceAll(route) else nav.navigate(route)
                },
            )
        }
    }
}

/** Pops, or goes to Home if this is the only screen (e.g. opened from a notification). */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack() else replaceAll(Route.Home)
}

private fun NavHostController.backToHome() {
    if (!popBackStack(Route.Home, inclusive = false)) replaceAll(Route.Home)
}

/** Navigates and clears the back stack (onboarding steps and sign-out must not be reachable with Back). */
private fun NavHostController.replaceAll(route: Route) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
