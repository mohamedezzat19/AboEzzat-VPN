package com.aboezzat.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aboezzat.vpn.data.ProxyProfileStore
import com.aboezzat.vpn.data.AppRouteStore
import com.aboezzat.vpn.core.RouteMode
import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol
import com.aboezzat.vpn.vpn.AboEzzatVpnService
import com.aboezzat.vpn.proxy.ProxyTestResult

private val Midnight = Color(0xFF0A1020)
private val Panel = Color(0xFF141F33)
private val Accent = Color(0xFF62E6B5)
private val Muted = Color(0xFF91A0B8)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val layoutDirection = if (resources.configuration.locales[0].language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
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
}

@Composable
private fun Dashboard(store: ProxyProfileStore) {
    val profiles = remember { mutableStateListOf<ProxyProfile>().apply { addAll(store.loadProfiles()) } }
    val credentialWarning = remember(store) { store.hasCredentialReadFailure() }
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }
    var vpnPermissionGranted by remember { mutableStateOf(VpnService.prepare(context) == null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        vpnPermissionGranted = VpnService.prepare(context) == null
        notice = if (vpnPermissionGranted) {
            "Android VPN permission granted. Tunnel is still OFF."
        } else {
            "VPN permission was not granted."
        }
    }
    val routeStore = remember(context) { AppRouteStore(context) }
    val installedApps = remember(context) {
        mutableStateListOf<LaunchableApp>().apply { addAll(loadLaunchableApps(context)) }
    }
    val appRoutes = remember(routeStore) {
        mutableStateMapOf<String, RouteMode>().apply { putAll(routeStore.load()) }
    }
    var routeSearch by remember { mutableStateOf("") }
    var selectedRouteApp by remember { mutableStateOf<LaunchableApp?>(null) }
    var activeName by remember { mutableStateOf(store.activeProfileName()) }
    var editorProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf("") }
    val testResults = remember { mutableStateMapOf<String, ProxyTestResult>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var tunnelActive by remember { mutableStateOf(AboEzzatVpnService.isTunnelRunning()) }
    var tunnelStatus by remember { mutableStateOf(if (tunnelActive) "Tunnel active" else "Not connected") }
    val tunnelResultReceiver = remember(mainHandler) {
        object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                tunnelStatus = resultData?.getString(AboEzzatVpnService.EXTRA_MESSAGE)
                    ?: "Tunnel status updated."
                when (resultCode) {
                    AboEzzatVpnService.RESULT_STARTED, AboEzzatVpnService.RESULT_STOPPING -> tunnelActive = true
                    AboEzzatVpnService.RESULT_STOPPED, AboEzzatVpnService.RESULT_FAILED -> tunnelActive = false
                }
                notice = tunnelStatus
            }
        }
    }

    fun sendTunnelCommand(action: String, profile: ProxyProfile? = null) {
        val serviceIntent = Intent(context, AboEzzatVpnService::class.java).apply {
            this.action = action
            putExtra(AboEzzatVpnService.EXTRA_RESULT_RECEIVER, tunnelResultReceiver)
            if (profile != null) {
                putExtra(AboEzzatVpnService.EXTRA_NAME, profile.name)
                putExtra(AboEzzatVpnService.EXTRA_HOST, profile.host)
                putExtra(AboEzzatVpnService.EXTRA_PORT, profile.port)
                putExtra(AboEzzatVpnService.EXTRA_PROTOCOL, profile.protocol.name)
                putExtra(AboEzzatVpnService.EXTRA_USERNAME, profile.username)
                putExtra(AboEzzatVpnService.EXTRA_PASSWORD, profile.password)
                putExtra(AboEzzatVpnService.EXTRA_REMOTE_DNS, profile.remoteDns)
            }
        }
        try {
            if (action == AboEzzatVpnService.ACTION_START_TUNNEL) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (error: Exception) {
            notice = error.message ?: "Could not send the tunnel command."
        }
    }

    fun connectSelectedProfile() {
        val profile = profiles.firstOrNull { it.name == activeName }
        if (profile == null) {
            notice = "Add and select a proxy profile first."
            return
        }
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
            notice = "Grant Android VPN permission, then tap Connect again."
            return
        }
        vpnPermissionGranted = true
        sendTunnelCommand(AboEzzatVpnService.ACTION_START_TUNNEL, profile)
    }

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
                TextButton(onClick = {
                    val current = context.resources.configuration.locales[0].language
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (current == "ar") "en" else "ar"))
                }) {
                    Text(if (context.resources.configuration.locales[0].language == "ar") "English" else "العربية", color = Accent)
                }
                Icon(Icons.Rounded.Security, contentDescription = null, tint = Accent)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(tx("CONNECTION STATUS"), color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    Text(tx(tunnelStatus), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(tx("The current tunnel sends device traffic through the selected upstream proxy."),
                        color = Muted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Text(tx("Per-app routing rules are saved, but this first tunnel mode applies to the whole device."),
                        color = Accent, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Text(tx("TCP is sent through the proxy. General UDP support depends on SOCKS5 UDP ASSOCIATE; HTTP CONNECT does not carry arbitrary UDP."),
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tx("Android VPN permission"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                tx(if (vpnPermissionGranted) "Permission granted · tunnel remains off"
                                else "Permission required before a VPN can be started"),
                                color = if (vpnPermissionGranted) Accent else Muted,
                                fontSize = 10.sp
                            )
                        }
                        Button(
                            onClick = {
                                val prepareIntent = VpnService.prepare(context)
                                if (prepareIntent == null) {
                                    vpnPermissionGranted = true
                                    notice = "Android VPN permission already granted. Tunnel is still OFF."
                                } else {
                                    vpnPermissionLauncher.launch(prepareIntent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Midnight)
                        ) {
                            Text(tx(if (vpnPermissionGranted) "Check" else "Authorize"))
                        }
                    }
                    Text(tx("Use Connect after selecting a working proxy profile. The tunnel stays active in the background with a notification."),
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { connectSelectedProfile() },
                            enabled = !tunnelActive && profiles.any { it.name == activeName },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Midnight)
                        ) { Text(tx("Connect")) }
                        Button(
                            onClick = { sendTunnelCommand(AboEzzatVpnService.ACTION_STOP_TUNNEL) },
                            enabled = tunnelActive,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34445F), contentColor = Color.White)
                        ) { Text(tx("Disconnect")) }
                    }
                }
            }

            if (credentialWarning) {
                InfoCard(
                    "Saved credentials need attention",
                    "Android could not read or decrypt saved proxy profile data. Existing data is protected from being overwritten. Restore the original app data and Android Keystore state before editing or saving profiles."
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tx("PROXY PROFILES"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(tx("Save the first phone's proxy connection details"), color = Muted, fontSize = 11.sp)
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
                    Text(tx(" Add"))
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
                        Text(tx("No proxy profiles yet"), color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(tx("Add the IP address and port shown by Android Server Proxy on your first phone."), color = Muted, fontSize = 12.sp)
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
                                Icon(Icons.Rounded.Edit, contentDescription = tx("Edit profile"), tint = Accent)
                            }
                            IconButton(onClick = {
                                val previousProfiles = profiles.toList()
                                profiles.remove(profile)
                                if (store.saveProfiles(profiles.toList())) {
                                    if (activeName == profile.name) {
                                        activeName = profiles.firstOrNull()?.name.orEmpty()
                                        store.setActiveProfile(activeName)
                                    }
                                    notice = "Profile deleted"
                                } else {
                                    profiles.clear()
                                    profiles.addAll(previousProfiles)
                                    notice = "Profile was not deleted. Secure profile storage could not save changes."
                                }
                            }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = tx("Delete profile"), tint = Color(0xFFFF8B8B))
                            }
                        }
                        Divider(color = Color(0xFF26344D))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.protocol.name.replace("_", " "), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(tx(if (profile.username.isNullOrEmpty()) "No authentication" else "Authentication saved securely"), color = Muted, fontSize = 10.sp)
                        }
                        Button(
                            onClick = {
                                testing[profile.name] = true
                                testResults.remove(profile.name)
                                val resultReceiver = object : ResultReceiver(mainHandler) {
                                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                                        val success = resultData?.getBoolean(
                                            AboEzzatVpnService.EXTRA_SUCCESS,
                                            resultCode == AboEzzatVpnService.RESULT_OK
                                        ) ?: (resultCode == AboEzzatVpnService.RESULT_OK)
                                        val message = resultData?.getString(AboEzzatVpnService.EXTRA_MESSAGE)
                                            ?: "Proxy service returned no diagnostic message."
                                        val latency = resultData?.getLong(AboEzzatVpnService.EXTRA_LATENCY) ?: 0L
                                        testResults[profile.name] = ProxyTestResult(success, message, latency)
                                        testing.remove(profile.name)
                                    }
                                }
                                val serviceIntent = Intent(context, AboEzzatVpnService::class.java).apply {
                                    action = AboEzzatVpnService.ACTION_TEST_UPSTREAM_PROXY
                                    putExtra(AboEzzatVpnService.EXTRA_RESULT_RECEIVER, resultReceiver)
                                    putExtra(AboEzzatVpnService.EXTRA_NAME, profile.name)
                                    putExtra(AboEzzatVpnService.EXTRA_HOST, profile.host)
                                    putExtra(AboEzzatVpnService.EXTRA_PORT, profile.port)
                                    putExtra(AboEzzatVpnService.EXTRA_PROTOCOL, profile.protocol.name)
                                    putExtra(AboEzzatVpnService.EXTRA_USERNAME, profile.username)
                                    putExtra(AboEzzatVpnService.EXTRA_PASSWORD, profile.password)
                                    putExtra(AboEzzatVpnService.EXTRA_REMOTE_DNS, profile.remoteDns)
                                }
                                try {
                                    context.startService(serviceIntent)
                                } catch (error: Exception) {
                                    testing.remove(profile.name)
                                    testResults[profile.name] = ProxyTestResult(
                                        false,
                                        error.message ?: "Could not start the VPN service for proxy testing.",
                                        0L
                                    )
                                }
                            },
                            enabled = testing[profile.name] != true,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Midnight)
                        ) {
                            Text(tx(if (testing[profile.name] == true) "Testing protected socket…" else "Test via VPN service"))
                        }
                        Text(tx("This runs the proxy handshake inside the VPN service and calls protect() before connect. No TUN tunnel or device traffic routing is active yet."),
                            color = Muted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                        testResults[profile.name]?.let { result ->
                            Text(
                                (if (result.success) "HANDSHAKE OK" else "FAILED") +
                                    " · " + result.message +
                                    if (result.success) " · ${result.latencyMs} ms" else "",
                                color = if (result.success) Accent else Color(0xFFFF8B8B),
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            if (notice.isNotBlank()) {
                Text(tx(notice), color = Accent, fontSize = 12.sp)
            }

            Text(tx("PER-APP ROUTING POLICIES"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(tx("Choose a route for each installed app. These policies are saved, but the current tunnel mode still routes the whole device through the selected proxy."),
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp
            )
            OutlinedTextField(
                value = routeSearch,
                onValueChange = { routeSearch = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tx("Search installed apps")) },
                singleLine = true
            )
            Text("${installedApps.size} ${tx("installed apps found")}", color = Muted, fontSize = 10.sp)
            installedApps.filter { it.label.contains(routeSearch, ignoreCase = true) || it.packageName.contains(routeSearch, ignoreCase = true) }
                .forEach { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Panel)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(app.packageName, color = Muted, fontSize = 9.sp)
                            }
                            TextButton(onClick = { selectedRouteApp = app }) {
                                Text((appRoutes[app.packageName] ?: RouteMode.VPN_ONLY).displayName(), color = Accent, fontSize = 10.sp)
                            }
                        }
                    }
                }
            InfoCard(
                "Banking mode · manual policy",
                "For banking apps such as Tabby, choose Direct bypass on that app's row. This is a saved policy only and does not guarantee a bank will accept the connection."
            )
            Text(tx("Per-app policies are not enforced by the current whole-device tunnel. Banking bypass and mixed routing will be enabled in a later routing-engine stage."),
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(tx("ABOEZZAT VPN  •  PRIVATE NETWORK CONTROL"), color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }

    selectedRouteApp?.let { app ->
        AlertDialog(
            onDismissRequest = { selectedRouteApp = null },
            containerColor = Panel,
            title = { Text(tx("Route for ${app.label}"), color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RouteMode.values().forEach { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (appRoutes[app.packageName] ?: RouteMode.VPN_ONLY) == mode,
                                onClick = {
                                    appRoutes[app.packageName] = mode
                                    routeStore.save(appRoutes.toMap())
                                    selectedRouteApp = null
                                }
                            )
                            Column {
                                Text(mode.displayName(), color = Color.White, fontSize = 13.sp)
                                Text(mode.description(), color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedRouteApp = null }) { Text(tx("Close"), color = Accent) } }
        )
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
                    val previousProfiles = profiles.toList()
                    if (editorProfile == null) profiles.add(profile)
                    else {
                        val index = profiles.indexOfFirst { it.name == editorProfile?.name }
                        if (index >= 0) profiles[index] = profile
                    }
                    if (!store.saveProfiles(profiles.toList())) {
                        profiles.clear()
                        profiles.addAll(previousProfiles)
                        editorError = "Secure profile storage could not save changes. Existing profiles were preserved."
                        return@ProfileEditorDialog
                    }
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
private fun tx(english: String): String {
    if (LocalConfiguration.current.locales[0].language != "ar") return english
    return when {
        english.startsWith("Active profile: ") -> "ملف البروكسي النشط: " + english.removePrefix("Active profile: ")
        english.startsWith("Route for ") -> "مسار التطبيق: " + english.removePrefix("Route for ")
        english.startsWith("Proxy service returned no diagnostic message.") -> "لم تُرجع خدمة البروكسي أي رسالة تشخيصية."
        english.startsWith("Could not start the VPN service for proxy testing.") -> "تعذر تشغيل خدمة VPN لاختبار البروكسي."
        english.startsWith("HTTP proxy rejected CONNECT:") -> "رفض بروكسي HTTP طلب CONNECT: " + english.substringAfter(":").trim()
        english.startsWith("SOCKS5 CONNECT failed") -> "فشل اتصال SOCKS5: " + english.substringAfter("failed")
        english.startsWith("HTTP/1.1 407") -> "رفض البروكسي المصادقة (HTTP 407)."
        english.startsWith("HTTP/1.0 407") -> "رفض البروكسي المصادقة (HTTP 407)."
        else -> arabicUi[english] ?: english
    }
}

private val arabicUi = mapOf(
    "CONNECTION STATUS" to "حالة الاتصال",
    "Not connected" to "غير متصل",
    "Tunnel active" to "النفق نشط",
    "TUN established. Starting the upstream proxy tunnel." to "تم إنشاء واجهة TUN. جارٍ تشغيل نفق البروكسي الأصلي.",
    "Tunnel is already stopped." to "النفق متوقف بالفعل.",
    "Tunnel stop requested. Waiting for native cleanup." to "تم طلب إيقاف النفق. جارٍ انتظار إنهاء المحرك وتنظيف الموارد.",
    "The native engine did not accept the stop request." to "لم يقبل المحرك الأصلي طلب الإيقاف.",
    "Tunnel stopped and native resources released." to "توقف النفق وتم تحرير موارد المحرك.",
    "Tunnel engine exited." to "انتهى تشغيل محرك النفق.",
    "Tunnel status updated." to "تم تحديث حالة النفق.",
    "Native tunnel stopped with error code" to "توقف محرك النفق برمز خطأ",

    "The current tunnel sends device traffic through the selected upstream proxy." to "النفق الحالي يمرر حركة مرور الجهاز عبر البروكسي المحدد.",
    "Per-app routing rules are saved, but this first tunnel mode applies to the whole device." to "قواعد التوجيه لكل تطبيق محفوظة، لكن وضع النفق الأول يطبق على الجهاز بالكامل.",
    "TCP is sent through the proxy. General UDP support depends on SOCKS5 UDP ASSOCIATE; HTTP CONNECT does not carry arbitrary UDP." to "يتم تمرير TCP عبر البروكسي. دعم UDP العام يعتمد على UDP ASSOCIATE في SOCKS5؛ أما HTTP CONNECT فلا ينقل UDP العام.",
    "Use Connect after selecting a working proxy profile. The tunnel stays active in the background with a notification." to "اضغط اتصال بعد اختيار ملف بروكسي يعمل. سيظل النفق نشطًا في الخلفية مع إشعار واضح.",
    "Choose a route for each installed app. These policies are saved, but the current tunnel mode still routes the whole device through the selected proxy." to "اختر مسارًا لكل تطبيق مثبت. تُحفظ السياسات، لكن وضع النفق الحالي يمرر حركة مرور الجهاز بالكامل عبر البروكسي المحدد.",
    "Per-app policies are not enforced by the current whole-device tunnel. Banking bypass and mixed routing will be enabled in a later routing-engine stage." to "لا تُطبّق سياسات كل تطبيق في نفق الجهاز الحالي. سيتم تفعيل تجاوز التطبيقات البنكية والتوجيه المختلط في مرحلة لاحقة من محرك التوجيه.",
    "Connect" to "اتصال",
    "Disconnect" to "قطع الاتصال",
    "Add and select a proxy profile first." to "أضف ملف بروكسي وحدده أولًا.",
    "Grant Android VPN permission, then tap Connect again." to "امنح إذن VPN من Android ثم اضغط اتصال مرة أخرى.",
    "Could not send the tunnel command." to "تعذر إرسال أمر النفق.",
    "Tunnel status updated." to "تم تحديث حالة النفق.",
    "A tunnel session is already active." to "توجد جلسة نفق نشطة بالفعل.",
    "Native tunnel engine could not be loaded." to "تعذر تحميل محرك النفق الأصلي.",
    "Android VPN permission is required." to "يلزم منح إذن VPN من Android.",
    "Could not resolve the proxy server address." to "تعذر تحديد عنوان خادم البروكسي.",
    "Android could not establish the TUN interface." to "تعذر على Android إنشاء واجهة TUN.",
    "The selected proxy profile is not valid for the tunnel." to "ملف البروكسي المحدد غير صالح للنفق.",
    "The native tunnel worker is not available." to "عامل تشغيل النفق الأصلي غير متاح.",
    "TUN established. Starting the upstream proxy tunnel." to "تم إنشاء واجهة TUN. جارٍ تشغيل نفق البروكسي الأصلي.",
    "Tunnel is already stopped." to "النفق متوقف بالفعل.",
    "Tunnel stop requested. Waiting for native cleanup." to "تم طلب إيقاف النفق. جارٍ انتظار إنهاء المحرك وتنظيف الموارد.",
    "The native engine did not accept the stop request." to "لم يقبل المحرك الأصلي طلب الإيقاف.",
    "Tunnel stopped and native resources released." to "توقف النفق وتم تحرير موارد المحرك.",
    "Tunnel engine exited." to "انتهى تشغيل محرك النفق.",
    "Native tunnel stopped with error code" to "توقف محرك النفق برمز خطأ",

    "Android VPN permission granted. Tunnel is still OFF." to "تم منح إذن VPN من Android. النفق لا يزال متوقفًا.",
    "VPN permission was not granted." to "لم يتم منح إذن VPN.",
    "Android VPN permission already granted. Tunnel is still OFF." to "تم منح إذن VPN من Android بالفعل. النفق لا يزال متوقفًا.",
    "Permission granted · tunnel remains off" to "تم منح الإذن · النفق لا يزال متوقفًا",
    "Permission required before a VPN can be started" to "يلزم منح الإذن قبل تشغيل VPN",
    "Proxy CONNECT handshake succeeded. Destination TLS was not tested." to "نجحت مصافحة اتصال البروكسي. لم يتم اختبار TLS مع الوجهة.",
    "Connection timed out. Check server IP, port and network." to "انتهت مهلة الاتصال. تحقق من عنوان الخادم والمنفذ والشبكة.",
    "Could not resolve the proxy server address." to "تعذر تحديد عنوان خادم البروكسي.",
    "Could not protect the upstream socket from VPN routing." to "تعذرت حماية مقبس البروكسي من التوجيه عبر VPN.",
    "Proxy authentication required or credentials rejected (HTTP 407)." to "يتطلب البروكسي المصادقة أو تم رفض بيانات الدخول (HTTP 407).",
    "SOCKS5 server requires a username and password." to "يتطلب خادم SOCKS5 اسم مستخدم وكلمة مرور.",
    "SOCKS5 authentication failed." to "فشلت مصادقة SOCKS5.",
    "SOCKS5 server rejected all authentication methods." to "رفض خادم SOCKS5 جميع طرق المصادقة.",
    "Server is not a SOCKS5 proxy." to "الخادم ليس بروكسي SOCKS5.",
    "Invalid proxy profile." to "ملف البروكسي غير صالح.",
    "Proxy handshake failed." to "فشلت مصافحة البروكسي.",
    "Proxy test could not be completed." to "تعذر إكمال اختبار البروكسي.",
    "Edit profile" to "تعديل الملف",
    "Delete profile" to "حذف الملف",
    "Banking mode · manual policy" to "وضع التطبيقات البنكية · إعداد يدوي",
    "For banking apps such as Tabby, choose Direct bypass on that app's row. This is a saved policy only and does not guarantee a bank will accept the connection." to "للتطبيقات البنكية مثل Tabby، اختر الاتصال المباشر من صف التطبيق. هذا إعداد محفوظ فقط ولا يضمن قبول البنك للاتصال.",
    "Not connected" to "غير متصل",
    "Setup stage · No device traffic is routed or intercepted yet." to "مرحلة الإعداد · لم يتم توجيه حركة مرور الجهاز بعد.",
    "We are preparing and testing the upstream proxy before enabling the VPN tunnel." to "نجهز ونختبر البروكسي الأصلي قبل تفعيل نفق VPN.",
    "Android VPN permission" to "إذن VPN من Android",
    "Permission granted · tunnel remains off" to "تم منح الإذن · النفق متوقف",
    "Permission required before a VPN can be started" to "يلزم منح الإذن قبل تشغيل VPN",
    "This only checks Android consent. It does NOT connect, create a TUN interface, or redirect traffic." to "هذا يتحقق من إذن Android فقط، ولا ينشئ اتصالًا أو واجهة TUN ولا يوجّه حركة المرور.",
    "PROXY PROFILES" to "ملفات البروكسي",
    "Save the first phone's proxy connection details" to "احفظ بيانات بروكسي الهاتف الأول",
    " Add" to " إضافة",
    "No proxy profiles yet" to "لا توجد ملفات بروكسي بعد",
    "Add the IP address and port shown by Android Server Proxy on your first phone." to "أدخل عنوان IP والمنفذ الظاهرين في Android Server Proxy على هاتفك الأول.",
    "Saved credentials need attention" to "بيانات الاعتماد المحفوظة تحتاج مراجعة",
    "Android could not read or decrypt saved proxy profile data. Existing data is protected from being overwritten. Restore the original app data and Android Keystore state before editing or saving profiles." to "تعذر على Android قراءة أو فك تشفير بيانات البروكسي. تمت حماية البيانات من الاستبدال. استعد بيانات التطبيق الأصلية وحالة Android Keystore قبل التعديل أو الحفظ.",
    "No authentication" to "بدون مصادقة",
    "Authentication saved securely" to "تم حفظ بيانات المصادقة بأمان",
    "Testing protected socket…" to "جارٍ اختبار الاتصال المحمي…",
    "Test via VPN service" to "اختبار عبر خدمة VPN",
    "This runs the proxy handshake inside the VPN service and calls protect() before connect. No TUN tunnel or device traffic routing is active yet." to "يختبر هذا الاتصال بالبروكسي داخل خدمة VPN ويستدعي protect() قبل الاتصال. لا يوجد نفق TUN أو توجيه لحركة المرور حاليًا.",
    "HANDSHAKE OK" to "نجاح المصافحة",
    "FAILED" to "فشل",
    "PER-APP ROUTING POLICIES" to "سياسات التوجيه لكل تطبيق",
    "Choose a route for each installed app. These choices are saved on this phone; enforcement remains disabled until the real packet engine is ready." to "اختر مسارًا لكل تطبيق مثبت. تُحفظ الاختيارات على الهاتف، لكن تطبيقها معطل حتى يصبح محرك الحزم جاهزًا.",
    "Search installed apps" to "ابحث عن التطبيقات المثبتة",
    "Routing policies are saved but not active yet. The VPN service still does not create a TUN interface, so normal device traffic is not redirected." to "تم حفظ السياسات لكنها غير مفعّلة. خدمة VPN لا تنشئ واجهة TUN بعد، لذلك لا تتم إعادة توجيه حركة مرور الجهاز.",
    "ABOEZZAT VPN  •  PRIVATE NETWORK CONTROL" to "ABOEZZAT VPN • التحكم في الشبكة الخاصة",
    "Close" to "إغلاق",
    "Add proxy profile" to "إضافة ملف بروكسي",
    "Edit proxy profile" to "تعديل ملف البروكسي",
    "Profile name" to "اسم الملف",
    "Server IP / hostname" to "عنوان IP للخادم / اسم المضيف",
    "Port" to "المنفذ",
    "PROXY TYPE" to "نوع البروكسي",
    "AUTHENTICATION (OPTIONAL)" to "المصادقة (اختيارية)",
    "Username" to "اسم المستخدم",
    "Password" to "كلمة المرور",
    "Remote DNS" to "DNS عن بُعد",
    "Use proxy-side DNS when supported" to "استخدم DNS افتراضيًا عبر البروكسي عند تفعيله، أو مرّر استعلامات DNS عبر TCP من خلال البروكسي عند إيقافه.",
    "Credentials are encrypted with Android Keystore before being saved." to "تُشفّر بيانات الاعتماد باستخدام Android Keystore قبل حفظها.",
    "Save" to "حفظ",
    "Cancel" to "إلغاء",
    "VPN only" to "VPN فقط",
    "Original proxy" to "البروكسي الأصلي",
    "Proxy → VPN" to "البروكسي ثم VPN",
    "Direct bypass" to "اتصال مباشر",
    "Route through the VPN tunnel" to "التوجيه عبر نفق VPN",
    "Use the first phone's proxy" to "استخدم بروكسي الهاتف الأول",
    "Send through proxy, then VPN" to "مرّر الاتصال عبر البروكسي ثم VPN",
    "Use the normal network directly" to "استخدم الشبكة العادية مباشرة",
    "Android VPN permission already granted. Tunnel is still OFF." to "تم منح إذن VPN من Android. النفق لا يزال متوقفًا.",
    "VPN permission was not granted." to "لم يتم منح إذن VPN.",
    "Profile deleted" to "تم حذف ملف البروكسي",
    "Profile was not deleted. Secure profile storage could not save changes." to "لم يتم حذف الملف. تعذر حفظ التغييرات في التخزين الآمن.",
    "Profile saved on this device" to "تم حفظ ملف البروكسي على هذا الجهاز",
    "A profile with this name already exists" to "يوجد ملف بهذا الاسم بالفعل",
    "Secure profile storage could not save changes. Existing profiles were preserved." to "تعذر حفظ التغييرات في التخزين الآمن. تم الحفاظ على الملفات الحالية.",
    "installed apps found" to "تطبيقات قابلة للتشغيل",
    "Check" to "تحقق",
    "Authorize" to "منح الإذن",
    "Profile name is required" to "اسم ملف البروكسي مطلوب",
    "Enter a valid server IP or hostname" to "أدخل عنوان IP أو اسم مضيف صحيحًا",
    "Port must be between 1 and 65535" to "يجب أن يكون المنفذ بين 1 و65535",
    "Username and password must both be provided, or both left empty" to "يجب إدخال اسم المستخدم وكلمة المرور معًا أو تركهما فارغين",
    "HTTP CONNECT" to "HTTP CONNECT",
    "SOCKS5" to "SOCKS5"
)

@Composable
private fun InfoCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(tx(title), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(tx(subtitle), color = Muted, fontSize = 11.sp)
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
        title = { Text(tx(if (initial == null) "Add proxy profile" else "Edit proxy profile"), color = Color.White) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(490.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(tx("Profile name")) }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(tx("Server IP / hostname")) }, singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text(tx("Port")) }, singleLine = true)
                Text(tx("PROXY TYPE"), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = protocol == ProxyProtocol.HTTP_CONNECT, onClick = { protocol = ProxyProtocol.HTTP_CONNECT })
                    Text(tx("HTTP CONNECT"), color = Color.White, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = protocol == ProxyProtocol.SOCKS5, onClick = { protocol = ProxyProtocol.SOCKS5 })
                    Text(tx("SOCKS5"), color = Color.White, fontSize = 12.sp)
                }
                Divider(color = Color(0xFF33415B))
                Text(tx("AUTHENTICATION (OPTIONAL)"), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(tx("Username")) }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(tx("Password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tx("Remote DNS"), color = Color.White, fontSize = 13.sp)
                        Text(tx("Use proxy-side DNS when supported"), color = Muted, fontSize = 10.sp)
                    }
                    Switch(checked = remoteDns, onCheckedChange = { remoteDns = it })
                }
                if (error.isNotBlank()) Text(tx(error), color = Color(0xFFFF8B8B), fontSize = 12.sp)
                Text(tx("Credentials are encrypted with Android Keystore before being saved."), color = Muted, fontSize = 10.sp)
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
            }) { Text(tx("Save"), color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx("Cancel"), color = Muted) } }
    )
}


private data class LaunchableApp(val label: String, val packageName: String)

private fun loadLaunchableApps(context: android.content.Context): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            LaunchableApp(
                label = info.loadLabel(context.packageManager)?.toString()?.ifBlank { packageName } ?: packageName,
                packageName = packageName
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun RouteMode.displayName(): String = when (this) {
    RouteMode.VPN_ONLY -> tx("VPN only")
    RouteMode.PROXY_ONLY -> tx("Original proxy")
    RouteMode.PROXY_THEN_VPN -> tx("Proxy → VPN")
    RouteMode.DIRECT -> tx("Direct bypass")
}

@Composable
private fun RouteMode.description(): String = when (this) {
    RouteMode.VPN_ONLY -> tx("Route through the VPN tunnel")
    RouteMode.PROXY_ONLY -> tx("Use the first phone's proxy")
    RouteMode.PROXY_THEN_VPN -> tx("Send through proxy, then VPN")
    RouteMode.DIRECT -> tx("Use the normal network directly")
}
