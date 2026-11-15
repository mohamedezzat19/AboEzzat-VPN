package com.aboezzat.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aboezzat.vpn.data.ProxyProfileStore
import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol

private val Midnight = Color(0xFF0A1020)
private val Panel = Color(0xFF141F33)
private val Accent = Color(0xFF62E6B5)
private val Muted = Color(0xFF91A0B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent,
                background = Midnight,
                surface = Panel,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                Dashboard(ProxyProfileStore(applicationContext))
            }
        }
    }
}

@Composable
private fun Dashboard(store: ProxyProfileStore) {
    val profiles = remember { mutableStateListOf<ProxyProfile>().apply { addAll(store.loadProfiles()) } }
    var activeName by remember { mutableStateOf(store.activeProfileName()) }
    var editorProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = Midnight) {
        Column(
            modifier = Modifier.fillMaxSize().background(Midnight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ABOEZZAT", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("VPN", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                }
                Icon(Icons.Rounded.Security, contentDescription = null, tint = Accent)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("CONNECTION STATUS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    Text("Not connected", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Setup stage · No device traffic is routed or intercepted yet.",
                        color = Muted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Text(
                        "We are preparing and testing the upstream proxy before enabling the VPN tunnel.",
                        color = Accent, fontSize = 12.sp, lineHeight = 18.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PROXY PROFILES", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Save the first phone's proxy connection details", color = Muted, fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        editorProfile = null
                        editorError = ""
                        showEditor = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Midnight)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(" Add")
                }
            }

            if (profiles.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.SettingsEthernet, contentDescription = null, tint = Accent)
                        Text("No proxy profiles yet", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Add the IP address and port shown by Android Server Proxy on your first phone.", color = Muted, fontSize = 12.sp)
                    }
                }
            }

            profiles.forEach { profile ->
                val selected = profile.name == activeName
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel)
                ) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    activeName = profile.name
                                    store.setActiveProfile(profile.name)
                                    notice = "Active profile: ${profile.name}"
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("${profile.host}:${profile.port}", color = Muted, fontSize = 12.sp)
                            }
                            IconButton(onClick = {
                                editorProfile = profile
                                editorError = ""
                                showEditor = true
                            }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit profile", tint = Accent)
                            }
                            IconButton(onClick = {
                                profiles.remove(profile)
                                store.saveProfiles(profiles.toList())
                                if (activeName == profile.name) {
                                    activeName = profiles.firstOrNull()?.name.orEmpty()
                                    store.setActiveProfile(activeName)
                                }
                                notice = "Profile deleted"
                            }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete profile", tint = Color(0xFFFF8B8B))
                            }
                        }
                        Divider(color = Color(0xFF26344D))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.protocol.name.replace("_", " "), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(if (profile.username.isNullOrEmpty()) "No authentication" else "Authentication saved securely", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }

            if (notice.isNotBlank()) {
                Text(notice, color = Accent, fontSize = 12.sp)
            }

            Text("ROUTING FEATURES", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            InfoCard("Per-app routing", "VPN only · Proxy only · Proxy then VPN · Direct bypass")
            InfoCard("Banking mode", "A separate direct route policy for selected banking apps")
            Text(
                "Important: profile management is available now. Connection testing and the real VPN packet engine are the next stages.",
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            Text("ABOEZZAT VPN  •  PRIVATE NETWORK CONTROL", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }

    if (showEditor) {
        ProfileEditorDialog(
            initial = editorProfile,
            error = editorError,
            onDismiss = { showEditor = false },
            onSave = { profile ->
                val validation = profile.validate()
                if (validation != null) {
                    editorError = validation
                } else if (profiles.any { it.name.equals(profile.name, true) && it.name != editorProfile?.name }) {
                    editorError = "A profile with this name already exists"
                } else {
                    if (editorProfile == null) profiles.add(profile)
                    else {
                        val index = profiles.indexOfFirst { it.name == editorProfile?.name }
                        if (index >= 0) profiles[index] = profile
                    }
                    store.saveProfiles(profiles.toList())
                    if (activeName.isBlank()) {
                        activeName = profile.name
                        store.setActiveProfile(profile.name)
                    } else if (editorProfile?.name == activeName) {
                        activeName = profile.name
                        store.setActiveProfile(profile.name)
                    }
                    showEditor = false
                    notice = "Profile saved on this device"
                }
            }
        )
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ProfileEditorDialog(
    initial: ProxyProfile?,
    error: String,
    onDismiss: () -> Unit,
    onSave: (ProxyProfile) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf(initial?.port?.toString().orEmpty()) }
    var protocol by remember(initial) { mutableStateOf(initial?.protocol ?: ProxyProtocol.HTTP_CONNECT) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var remoteDns by remember(initial) { mutableStateOf(initial?.remoteDns ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (initial == null) "Add proxy profile" else "Edit proxy profile", color = Color.White) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(490.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile name") }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Server IP / hostname") }, singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true)
                Text("PROXY TYPE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = protocol == ProxyProtocol.HTTP_CONNECT, onClick = { protocol = ProxyProtocol.HTTP_CONNECT })
                    Text("HTTP CONNECT", color = Color.White, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = protocol == ProxyProtocol.SOCKS5, onClick = { protocol = ProxyProtocol.SOCKS5 })
                    Text("SOCKS5", color = Color.White, fontSize = 12.sp)
                }
                Divider(color = Color(0xFF33415B))
                Text("AUTHENTICATION (OPTIONAL)", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remote DNS", color = Color.White, fontSize = 13.sp)
                        Text("Use proxy-side DNS when supported", color = Muted, fontSize = 10.sp)
                    }
                    Switch(checked = remoteDns, onCheckedChange = { remoteDns = it })
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), fontSize = 12.sp)
                Text("Credentials are encrypted with Android Keystore before being saved.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    ProxyProfile(
                        name = name.trim(),
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 0,
                        protocol = protocol,
                        username = username.takeIf { it.isNotBlank() },
                        password = password.takeIf { it.isNotBlank() },
                        remoteDns = remoteDns
                    )
                )
            }) { Text("Save", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
