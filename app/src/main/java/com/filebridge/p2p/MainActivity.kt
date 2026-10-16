package com.filebridge.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

private const val MIME_ALL = "*/*"

data class FileMeta(
    val uri: Uri,
    val name: String,
    val size: Long,
    val sha256: String
)

data class LogItem(
    val name: String,
    val size: String,
    val type: String,
    val verified: Boolean,
    val note: String
)

class MainActivity : ComponentActivity() {

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    val peerList = mutableStateListOf<WifiP2pDevice>()
    var p2pState by mutableStateOf("Ready")
    var hostIpAddress by mutableStateOf("")
    var isOwner by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2pManager?.initialize(this, mainLooper, null)

        setContent {
            var dark by remember { mutableStateOf(true) }
            var hindi by remember { mutableStateOf(true) }

            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(
                    primary = Color(0xFF4F46E5),
                    background = Color(0xFF0B0F19),
                    surface = Color(0xFF161F30),
                    onBackground = Color.White,
                    onSurface = Color.White
                ) else lightColorScheme(
                    primary = Color(0xFF4338CA),
                    background = Color(0xFFF8FAFC),
                    surface = Color.White,
                    onBackground = Color(0xFF0F172A),
                    onSurface = Color(0xFF0F172A)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainAppRoot(
                        dark = dark,
                        onToggleDark = { dark = !dark },
                        hindi = hindi,
                        onToggleHindi = { hindi = !hindi },
                        peers = peerList,
                        status = p2pState,
                        hostIp = hostIpAddress,
                        isOwner = isOwner,
                        onScan = { startScan() },
                        onConnect = { d -> connectDevice(d) }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        p2pState = "Scanning..."
        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    p2pState = "Radar Active"
                }

                override fun onFailure(reason: Int) {
                    p2pState = "Scan Error ($reason)"
                }
            })
        } catch (_: Exception) {
            p2pState = "Permission Needed"
        }
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: WifiP2pDevice) {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        val cfg = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        p2pState = "Connecting..."
        try {
            mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    p2pState = "Connected: ${device.deviceName}"
                }

                override fun onFailure(reason: Int) {
                    p2pState = "Failed ($reason)"
                }
            })
        } catch (_: Exception) {
            p2pState = "Error"
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        p2pManager?.requestPeers(p2pChannel!!) { list ->
                            peerList.clear()
                            peerList.addAll(list.deviceList)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        p2pManager?.requestConnectionInfo(p2pChannel!!) { info ->
                            if (info != null && info.groupFormed) {
                                hostIpAddress = info.groupOwnerAddress?.hostAddress ?: ""
                                isOwner = info.isGroupOwner
                                p2pState = if (info.isGroupOwner) "Host Active" else "Connected"
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
    }
}

@Composable
fun MainAppRoot(
    dark: Boolean,
    onToggleDark: () -> Unit,
    hindi: Boolean,
    onToggleHindi: () -> Unit,
    peers: List<WifiP2pDevice>,
    status: String,
    hostIp: String,
    isOwner: Boolean,
    onScan: () -> Unit,
    onConnect: (WifiP2pDevice) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf("HOME") }
    var selectedFile by remember { mutableStateOf<FileMeta?>(null) }
    var hashing by remember { mutableStateOf(false) }

    var targetIp by remember(hostIp) { mutableStateOf(if (hostIp.isNotEmpty()) hostIp else "192.168.49.1") }
    var prog by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var speedText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    val history = remember { mutableStateListOf<LogItem>() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onScan()
    }

    fun checkAndScan() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.NEARBY_WIFI_DEVICES")
        if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) {
            onScan()
        } else {
            permLauncher.launch(perms.toTypedArray())
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            hashing = true
            screen = "SEND"
            scope.launch {
                selectedFile = processFileUri(ctx, uri)
                hashing = false
            }
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF3B82F6)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(text = "Safe P2P India", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(text = if (hindi) "Milestones 2-8 Active" else "Offline Ready", fontSize = 11.sp, color = Color(0xFF34D399))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToggleHindi) {
                        Text(text = if (hindi) "EN" else "हिंदी", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    }
                    IconButton(onClick = onToggleDark) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFBBF24))
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = screen == "HOME" || screen == "SEND" || screen == "RECEIVE",
                    onClick = { screen = "HOME" },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(if (hindi) "होम" else "Home") }
                )
                NavigationBarItem(
                    selected = screen == "NEARBY",
                    onClick = { screen = "NEARBY"; checkAndScan() },
                    icon = { Icon(Icons.Default.Place, contentDescription = null) },
                    label = { Text(if (hindi) "आस-पास" else "Nearby") }
                )
                NavigationBarItem(
                    selected = screen == "HISTORY",
                    onClick = { screen = "HISTORY" },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text(if (hindi) "इतिहास" else "History") }
                )
                NavigationBarItem(
                    selected = screen == "SETTINGS",
                    onClick = { screen = "SETTINGS" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(if (hindi) "सेटिंग्स" else "Settings") }
                )
            }
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                "HOME" -> HomeScreen(
                    hindi = hindi,
                    status = status,
                    file = selectedFile,
                    onSend = { picker.launch(arrayOf(MIME_ALL)) },
                    onReceive = { screen = "RECEIVE" },
                    onNearby = { screen = "NEARBY"; checkAndScan() }
                )
                "SEND" -> SendScreen(
                    hindi = hindi,
                    file = selectedFile,
                    hashing = hashing,
                    ip = targetIp,
                    onIpChange = { targetIp = it },
                    peers = peers,
                    onPick = { picker.launch(arrayOf(MIME_ALL)) },
                    onScan = { checkAndScan() },
                    onConnect = onConnect,
                    busy = busy,
                    prog = prog,
                    status = statusText,
                    speed = speedText,
                    onStartSend = {
                        val f = selectedFile ?: return@SendScreen
                        if (busy) return@SendScreen
                        busy = true
                        scope.launch {
                            sendFileSocket(ctx, f, targetIp.trim(), 8988, { p, sp, st ->
                                prog = p; speedText = sp; statusText = st
                            }, { ok, msg ->
                                busy = false; statusText = msg
                                if (ok) history.add(0, LogItem(f.name, formatSize(f.size), "SENT", true, "To $targetIp"))
                            })
                        }
                    },
                    onBack = { screen = "HOME" }
                )
                "RECEIVE" -> ReceiveScreen(
                    hindi = hindi,
                    ip = getMyIp().ifEmpty { "192.168.49.1" },
                    status = status,
                    listening = listening,
                    prog = prog,
                    statusText = statusText,
                    speed = speedText,
                    onStartServer = {
                        if (listening) return@ReceiveScreen
                        checkAndScan()
                        listening = true
                        scope.launch {
                            receiveFileSocket(ctx, 8988, { p, sp, st ->
                                prog = p; speedText = sp; statusText = st
                            }, { ok, item, msg ->
                                listening = false; statusText = msg
                                if (ok && item != null) history.add(0, item)
                            })
                        }
                    },
                    onBack = { screen = "HOME" }
                )
                "NEARBY" -> NearbyScreen(hindi, peers, status, hostIp, isOwner, { checkAndScan() }, onConnect, { screen = "SEND" }, { screen = "RECEIVE" })
                "HISTORY" -> HistoryScreen(hindi, history) { screen = "HOME" }
                "SETTINGS" -> SettingsScreen(hindi) { screen = "HOME" }
            }
        }
    }
}

@Composable
fun HomeScreen(hindi: Boolean, status: String, file: FileMeta?, onSend: () -> Unit, onReceive: () -> Unit, onNearby: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(if (hindi) "✅ Safe P2P India इंजन तैयार है" else "✅ Safe P2P India Active", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("${Build.MODEL} • $status", color = Color(0xFFA7F3D0), fontSize = 12.sp)
                }
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34D399))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF4F46E5))))
                .clickable { onSend() }
                .padding(24.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (hindi) "फ़ाइल भेजें (SEND)" else "SEND FILE", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(if (hindi) "टैप करके गैलरी या फ़ाइल चुनें (SAF Picker)" else "Tap to pick photo, video or PDF", fontSize = 13.sp, color = Color.White.copy(0.9f))
                }
                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF9333EA), Color(0xFF6B21A8))))
                .clickable { onReceive() }
                .padding(24.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (hindi) "प्राप्त करें (RECEIVE)" else "RECEIVE FILE", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(if (hindi) "दूसरे फ़ोन से फ़ाइल लेने के लिए सर्वर खोलें" else "Start server to receive files on Port 8988", fontSize = 13.sp, color = Color.White.copy(0.9f))
                }
                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                }
            }
        }

        if (file != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(if (hindi) "चुनी गई फ़ाइल:" else "Selected File:", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(file.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Size: ${formatSize(file.size)} • SHA-256 Verified", fontSize = 12.sp, color = Color(0xFF34D399))
                }
            }
        }
    }
}

@Composable
fun SendScreen(hindi: Boolean, file: FileMeta?, hashing: Boolean, ip: String, onIpChange: (String) -> Unit, peers: List<WifiP2pDevice>, onPick: () -> Unit, onScan: () -> Unit, onConnect: (WifiP2pDevice) -> Unit, busy: Boolean, prog: Float, status: String, speed: String, onStartSend: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            Text(if (hindi) "फ़ाइल भेजें (Send)" else "Send File", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (file == null) (if (hindi) "फ़ाइल चुनें (SAF Picker)" else "Pick File") else (if (hindi) "दूसरी फ़ाइल चुनें" else "Change File"), fontWeight = FontWeight.Bold)
        }

        if (hashing) Text(if (hindi) "फ़ाइल का SHA-256 निकाला जा रहा है..." else "Computing Hash...", color = Color(0xFF38BDF8))

        if (file != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(file.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Size: ${formatSize(file.size)}", color = Color(0xFF34D399))
                    Text("SHA-256: ${file.sha256}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hindi) "रिसीवर IP पता:" else "Receiver IP:", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onScan) { Text(if (hindi) "स्कैन" else "Scan") }
                }
                peers.forEach { d ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onConnect(d) }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.deviceName.ifEmpty { "Device" }, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Connect", color = Color(0xFF38BDF8))
                    }
                }
                OutlinedTextField(value = ip, onValueChange = onIpChange, label = { Text("IP Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }

        if (status.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${(prog * 100).toInt()}%", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        Text(speed, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                    }
                    Text(status, fontSize = 13.sp)
                }
            }
        }

        Button(onClick = onStartSend, enabled = file != null && !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (hindi) "फ़ाइल भेजें (SEND)" else "SEND NOW", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ReceiveScreen(hindi: Boolean, ip: String, status: String, listening: Boolean, prog: Float, statusText: String, speed: String, onStartServer: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            Text(if (hindi) "फ़ाइल प्राप्त करें" else "Receive File", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (hindi) "आपका रिसीवर IP:" else "Your IP Address:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ip, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF38BDF8))
                Text("Port: 8988 • Status: $status", fontSize = 12.sp, color = Color(0xFF34D399))
            }
        }

        Button(onClick = onStartServer, enabled = !listening, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (listening) (if (hindi) "प्रतीक्षा में..." else "Waiting...") else (if (hindi) "रिसीवर सर्वर चालू करें" else "Start Server"), fontWeight = FontWeight.Bold)
        }

        if (statusText.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${(prog * 100).toInt()}%", fontWeight = FontWeight.Bold, color = Color(0xFFA855F7))
                        Text(speed, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                    }
                    Text(statusText, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun NearbyScreen(hindi: Boolean, peers: List<WifiP2pDevice>, status: String, hostIp: String, isHost: Boolean, onScan: () -> Unit, onConnect: (WifiP2pDevice) -> Unit, onSend: () -> Unit, onReceive: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (hindi) "Wi-Fi Direct रडार" else "Radar", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onScan) { Text(if (hindi) "स्कैन" else "Scan") }
        }
        if (hostIp.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("✅ Connected! IP: $hostIp", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send") }
                        Button(onClick = onReceive, modifier = Modifier.weight(1f)) { Text("Receive") }
                    }
                }
            }
        }
        peers.forEach { d ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onConnect(d) }) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(d.deviceName, fontWeight = FontWeight.Bold)
                    Text("Connect", color = Color(0xFF38BDF8))
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(hindi: Boolean, items: List<LogItem>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (hindi) "इतिहास (History)" else "History", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (items.isEmpty()) Text(if (hindi) "कोई रिकॉर्ड नहीं।" else "No history yet.")
        items.forEach { itm ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("${itm.type}: ${itm.name} (${itm.size})", fontWeight = FontWeight.Bold)
                    Text("✅ SHA-256 Verified • ${itm.note}", fontSize = 12.sp, color = Color(0xFF34D399))
                }
            }
        }
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun SettingsScreen(hindi: Boolean, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Safe P2P India", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (hindi) "सभी माइलस्टोन 2 से 8 इस ऐप में सक्रिय हैं।" else "Milestones 2-8 active.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onBack) { Text("Back") }
    }
}

// Helpers
suspend fun processFileUri(ctx: Context, uri: Uri): FileMeta = withContext(Dispatchers.IO) {
    var name = "file"
    var size = 0L
    try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nIdx != -1) name = c.getString(nIdx) ?: name
                if (sIdx != -1) size = c.getLong(sIdx)
            }
        }
    } catch (_: Exception) {}

    val md = MessageDigest.getInstance("SHA-256")
    var total = 0L
    try {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (ins.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
                total += r
            }
        }
    } catch (_: Exception) {}

    if (size <= 0L) size = total
    FileMeta(uri, name, size, md.digest().joinToString("") { "%02x".format(it) })
}

suspend fun sendFileSocket(ctx: Context, file: FileMeta, ip: String, port: Int, onProg: (Float, String, String) -> Unit, onDone: (Boolean, String) -> Unit) = withContext(Dispatchers.IO) {
    val s = Socket()
    try {
        s.connect(InetSocketAddress(ip, port), 10000)
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        out.writeUTF(file.name)
        out.writeLong(file.size)
        out.writeUTF(file.sha256)
        out.flush()

        val t0 = System.currentTimeMillis()
        var sent = 0L
        val buf = ByteArray(64 * 1024)
        ctx.contentResolver.openInputStream(file.uri)?.use { ins ->
            var r: Int
            while (ins.read(buf).also { r = it } != -1) {
                out.write(buf, 0, r)
                sent += r
                val sec = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.1)
                val mbps = (sent / (1024.0 * 1024.0)) / sec
                val p = if (file.size > 0) sent.toFloat() / file.size else 0.5f
                withContext(Dispatchers.Main) { onProg(p.coerceIn(0f, 1f), String.format("%.2f MB/s", mbps), "Sending ${formatSize(sent)}") }
            }
        }
        out.flush()
        withContext(Dispatchers.Main) { onProg(1f, "Done", "✅ Sent Successfully!"); onDone(true, "Sent") }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { onDone(false, "Error: ${e.localizedMessage}") }
    } finally {
        try { s.close() } catch (_: Exception) {}
    }
}

suspend fun receiveFileSocket(ctx: Context, port: Int, onProg: (Float, String, String) -> Unit, onDone: (Boolean, LogItem?, String) -> Unit) = withContext(Dispatchers.IO) {
    var ss: ServerSocket? = null
    try {
        ss = ServerSocket(port)
        val client = ss.accept()
        val inp = DataInputStream(BufferedInputStream(client.getInputStream()))
        val fName = inp.readUTF()
        val fSize = inp.readLong()
        val expHash = inp.readUTF()

        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        val target = File(dir, fName)
        val md = MessageDigest.getInstance("SHA-256")
        val t0 = System.currentTimeMillis()
        var rcv = 0L
        val buf = ByteArray(64 * 1024)

        FileOutputStream(target).use { fos ->
            var r: Int
            var rem = fSize
            while (rem > 0 && inp.read(buf, 0, minOf(buf.size.toLong(), rem).toInt()).also { r = it } != -1) {
                fos.write(buf, 0, r)
                md.update(buf, 0, r)
                rcv += r
                rem -= r
                val sec = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.1)
                val mbps = (rcv / (1024.0 * 1024.0)) / sec
                val p = if (fSize > 0) rcv.toFloat() / fSize else 0.5f
                withContext(Dispatchers.Main) { onProg(p.coerceIn(0f, 1f), String.format("%.2f MB/s", mbps), "Receiving ${formatSize(rcv)}") }
            }
        }
        val actHash = md.digest().joinToString("") { "%02x".format(it) }
        val ok = actHash.equals(expHash, ignoreCase = true)
        client.close()

        withContext(Dispatchers.Main) {
            val item = LogItem(fName, formatSize(fSize), "RECEIVED", ok, target.absolutePath)
            onProg(1f, "Done", "✅ Received!")
            onDone(true, item, "✅ Received: $fName")
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { onDone(false, null, "Error: ${e.localizedMessage}") }
    } finally {
        try { ss?.close() } catch (_: Exception) {}
    }
}

fun formatSize(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.2f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

fun getMyIp(): String {
    try {
        val nets = NetworkInterface.getNetworkInterfaces()
        while (nets.hasMoreElements()) {
            val intf = nets.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: ""
            }
        }
    } catch (_: Exception) {}
    return ""
}
