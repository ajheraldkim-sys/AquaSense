package com.magosilom.aquasense

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Temporary screens wired to the v3 controller state. Tasks 10–11 replace this file.

@Composable
fun NowScreen(vm: AppViewModel, openHistory: () -> Unit) {
    val screen by vm.screen.collectAsState()
    val station by vm.station.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(station, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            when (val s = screen) {
                Screen.Connecting -> "Connecting…"
                Screen.SensorProblem -> "Sensor problem"
                is Screen.NotConnected -> "Not connected" + (s.lastSeen?.let { " · last ${f1(it.ntu)} NTU, ${ago(it.tsMs)}" } ?: "")
                is Screen.Live -> "${s.state} · ${f1(s.ntu)} NTU (limit ${f1(s.limit)})"
            },
            fontSize = 18.sp
        )
        TextButton(openHistory) { Text("History") }
    }
}

@Composable
fun HistoryScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().padding(20.dp)) { Text("Updating…") }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().padding(20.dp)) { Text("Updating…") }
}
