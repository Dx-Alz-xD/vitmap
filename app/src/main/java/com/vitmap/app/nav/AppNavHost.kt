package com.vitmap.app.nav

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vitmap.app.home.AboutScreen
import com.vitmap.app.home.HomeScreen
import com.vitmap.app.home.SettingsScreen
import com.vitmap.app.vitmap
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object SettingsRoute

@Serializable
object AboutRoute

@Serializable
data class ToolRoute(val id: String, val arg: String? = null)

@Composable
fun AppNavHost(deepLink: ToolRoute?, onDeepLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    val app = LocalContext.current.vitmap

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            nav.navigate(deepLink) { launchSingleTop = true }
            onDeepLinkHandled()
        }
    }

    fun openTool(id: String, arg: String? = null) {
        nav.navigate(ToolRoute(id, arg)) { launchSingleTop = true }
    }

    SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = nav,
                startDestination = HomeRoute,
                enterTransition = { fadeIn(tween(320, delayMillis = 60)) + scaleIn(tween(380), initialScale = 0.94f) },
                exitTransition = { fadeOut(tween(220)) + scaleOut(tween(380), targetScale = 1.04f) },
                popEnterTransition = { fadeIn(tween(320, delayMillis = 60)) + scaleIn(tween(380), initialScale = 1.04f) },
                popExitTransition = { fadeOut(tween(220)) + scaleOut(tween(380), targetScale = 0.94f) },
            ) {
                composable<HomeRoute> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                        HomeScreen(
                            onOpenTool = { openTool(it) },
                            onOpenSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } },
                        )
                    }
                }
                composable<SettingsRoute> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                        SettingsScreen(onBack = { nav.popBackStack() }, onAbout = { nav.navigate(AboutRoute) })
                    }
                }
                composable<AboutRoute> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                        AboutScreen(onBack = { nav.popBackStack() })
                    }
                }
                composable<ToolRoute> { entry ->
                    val route = entry.toRoute<ToolRoute>()
                    LaunchedEffect(route.id) { app.settings.noteToolOpened(route.id) }
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .sharedToolBounds("tool-${route.id}")
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            ToolHost(route, onBack = { nav.popBackStack() }, onOpenTool = { id, arg -> openTool(id, arg) })
                        }
                    }
                }
            }
        }
    }
}
