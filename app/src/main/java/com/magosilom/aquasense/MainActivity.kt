package com.magosilom.aquasense

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { AquaTheme { AquaApp() } }
    }
}

// ------------------------------------------------------------------ palette
// Status colours. "Within limits" is deliberately blue, not green: the app never says "safe".
val Blue = Color(0xFF1E7FD0)
val Amber = Color(0xFFE39A12)
val Red = Color(0xFFD9483B)
val Grey = Color(0xFF9AA7B2)
val Ink = Color(0xFF10293B)

private val LightColors = lightColorScheme(
    primary = Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEEFC), onPrimaryContainer = Color(0xFF06345A),
    secondaryContainer = Color(0xFFDDEEFC), onSecondaryContainer = Color(0xFF06345A),
    background = Color(0xFFF4F8FB), onBackground = Ink,
    surface = Color(0xFFF4F8FB), onSurface = Ink,
    surfaceVariant = Color(0xFFE6EEF4), onSurfaceVariant = Color(0xFF5A6B78),
    surfaceContainer = Color.White, surfaceContainerLow = Color.White,
    outlineVariant = Color(0xFFDCE5EC)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF6CB6F5), onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF0E4A78), onPrimaryContainer = Color(0xFFD3E8FB),
    secondaryContainer = Color(0xFF173A55), onSecondaryContainer = Color(0xFFD3E8FB),
    background = Color(0xFF0C1720), onBackground = Color(0xFFE2EAF0),
    surface = Color(0xFF0C1720), onSurface = Color(0xFFE2EAF0),
    surfaceVariant = Color(0xFF1B2A36), onSurfaceVariant = Color(0xFF9FB0BD),
    surfaceContainer = Color(0xFF14212C), surfaceContainerLow = Color(0xFF14212C),
    outlineVariant = Color(0xFF26394A)
)

@Composable
fun AquaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}

// --------------------------------------------------------------------- shell

private data class Tab(val route: String, val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("now", "Now", Icons.Default.Home),
    Tab("history", "History", Icons.AutoMirrored.Filled.List),
    Tab("settings", "Settings", Icons.Default.Settings)
)

@Composable
fun AquaApp(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route ?: "now"
    val snack = remember { SnackbarHostState() }
    val msg by vm.message.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val open = alerts.count { !it.resolved }

    LaunchedEffect(msg) { msg?.let { vm.message.value = null; snack.showSnackbar(it) } }

    // Pause live polling when the app is not visible (saves battery, avoids silent background traffic).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) vm.setForeground(true)
            if (e == Lifecycle.Event.ON_STOP) vm.setForeground(false)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun go(r: String) = nav.navigate(r) {
        popUpTo("now") { saveState = true }; launchSingleTop = true; restoreState = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { go(t.route) },
                        icon = {
                            BadgedBox(badge = { if (t.route == "history" && open > 0) Badge(containerColor = Red) { Text("$open") } }) {
                                Icon(t.icon, null)
                            }
                        },
                        label = { Text(t.title) }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, "now", Modifier.padding(pad)) {
            composable("now") { NowScreen(vm) { go("history") } }
            composable("history") { HistoryScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}
