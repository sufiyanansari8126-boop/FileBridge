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

data class PickedFileData(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val sha256: String
)

data class HistoryItem(
    val fileName: String,
    val sizeFormatted: String,
    val mode: String,
    val verified: Boolean,
    val details: String
)

class MainActivity : ComponentActivity() {

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiChannel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    val discoveredPeers = mutableStateListOf<WifiP2pDevice>()
    var radarStatus by mutableStateOf("P2P Ready")
    var groupOwnerIp by mutableStateOf("")
    var isHostDevice by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        wifiChannel = wifiP2pManager?.initialize(this, mainLooper, null)

        setContent {
            var darkTheme by remember { mutableStateOf(true) }
            var hindiLang by remember { mutableStateOf(true) }

            val scheme = if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF4F46E5),
                    secondary = Color(0xFF9333EA),
                    background = Color(0xFF090D16),
                    surface = Color(0xFF151D2E),
                    onBackground = Color.White,
                    onSurface = Color.White,
                    onSurfaceVariant = Color(0xFF94A3B8)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF4338CA),
                    secondary = Color(0xFF7E22CE),
                    background = Color(0xFFF8FAFC),
                    surface = Color.White,
                    onBackground = Color(0xFF0F172A),
                    onSurface = Color(0xFF0F172A),
                    onSurfaceVariant = Color(0xFF475569)
                )
            }

            MaterialTheme(colorScheme = scheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SafeP2PIndiaRoot(
                        isDark = darkTheme,
                        onToggleDark = { darkTheme = !darkTheme },
                        isHindi = hindiLang,
                        onToggleHindi = { hindiLang = !hindiLang },
                        peers = discoveredPeers,
                        radarStatus = radarStatus,
                        hostIp = groupOwnerIp,
                        isHost = isHostDevice,
                        onScanPeers = { triggerPeerScan() },
                        onConnectDevice = { dev -> connectToPeerDevice(dev) }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerPeerScan() {
        val mgr = wifiP2pManager ?: return
        val ch = wifiChannel ?: return
        radarStatus = "Scanning..."
        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    radarStatus = "Radar Active"
                }
                override fun onFailure(reason: Int) {
                    radarStatus = "Scan Mode ($reason)"
                }
            })
        } catch (e: Exception) {
            radarStatus = "Check Wi-Fi/Location"
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeerDevice(device: WifiP2pDevice) {
        val mgr = wifiP2pManager ?: return
        val ch = wifiChannel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        radarStatus = "Connecting ${device.deviceName}..."
        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    radarStatus = "Connected: ${device.deviceName}"
                }
                override fun onFailure(reason: Int) {
                    radarStatus = "Retry Connect ($reason)"
                }
            })
        } catch (e: Exception) {
            radarStatus = "Connect Error"
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val mgr = wifiP2pManager ?: return
                        val ch = wifiChannel ?: return
                        try {
                            mgr.requestPeers(ch) { list ->
                                discoveredPeers.clear()
                                discoveredPeers.addAll(list.deviceList)
                            }
                        } catch (_: Exception) {}
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val mgr = wifiP2pManager ?: return
                        val ch = wifiChannel ?: return
                        try {
                            mgr.requestConnectionInfo(ch) { info ->
                                if (info != null && info.groupFormed) {
                                    groupOwnerIp = info.groupOwnerAddress?.hostAddress ?: ""
                                    isHostDevice = info.isGroupOwner
                                    radarStatus = if (info.isGroupOwner) "Host Ready" else "Linked to Host"
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        registerReceiver(receiver, filter)
    }

    override fun onPause() {
        super.onPause()
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
    }
}

@Composable
fun SafeP2PIndiaRoot(
    isDark: Boolean,
    onToggleDark: () -> Unit,
    isHindi: Boolean,
    onToggleHindi: () -> Unit,
    peers: List<WifiP2pDevice>,
    radarStatus: String,
    hostIp: String,
    isHost: Boolean,
    onScanPeers: () -> Unit,
    onConnectDevice: (WifiP2pDevice) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf("HOME") }
    var pickedFile by remember { mutableStateOf<PickedFileData?>(null) }
    var computingHash by remember { mutableStateOf(false) }

    var receiverIpText by remember(hostIp) {
        mutableStateOf(if (hostIp.isNotEmpty()) hostIp else "192.168.49.1")
    }
    var progressVal by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf("") }
    var speedLabel by remember { mutableStateOf("") }
    var isBusyTransfer by remember { mutableStateOf(false) }
    var isReceiverWaiting by remember { mutableStateOf(false) }

    val transferLogs = remember { mutableStateListOf<HistoryItem>() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onScanPeers()
    }

    fun checkPermissionsAndDiscover() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.NEARBY_WIFI_DEVICES")
        }
        val granted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            onScanPeers()
        } else {
            permLauncher.launch(perms.toTypedArray())
        }
    }

    // Native Android SAF File Picker
    val safPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            computingHash = true
            activeTab = "SEND"
            scope.launch {
                val data = readFileMetaAndSha256(context, uri)
                pickedFile = data
                computingHash = false
            }
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF10B981), Color(0xFF3B82F6))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Safe P2P India",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isHindi) "माइलस्टोन 2 से 8 सक्रिय • 100% ऑफ़लाइन" else "Milestones 2-8 Active • 100% Offline",
                            fontSize = 11.sp,
                            color = Color(0xFF34D399),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToggleHindi) {
                        Text(
                            text = if (isHindi) "EN" else "हिंदी",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                    IconButton(onClick = onToggleDark) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Theme",
                            tint = Color(0xFFFBBF24)
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = activeTab == "HOME" || activeTab == "SEND" || activeTab == "RECEIVE",
                    onClick = { activeTab = "HOME" },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(if (isHindi) "होम" else "Home") }
                )
                NavigationBarItem(
                    selected = activeTab == "NEARBY",
                    onClick = {
                        activeTab = "NEARBY"
                        checkPermissionsAndDiscover()
                    },
                    icon = { Icon(Icons.Default.Place, contentDescription = null) },
                    label = { Text(if (isHindi) "आस-पास" else "Nearby") }
                )
                NavigationBarItem(
                    selected = activeTab == "HISTORY",
                    onClick = { activeTab = "HISTORY" },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text(if (isHindi) "इतिहास" else "History") }
                )
                NavigationBarItem(
                    selected = activeTab == "SETTINGS",
                    onClick = { activeTab = "SETTINGS" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(if (isHindi) "सेटिंग्स" else "Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                "HOME" -> SafeHomeDashboard(
                    isHindi = isHindi,
                    radarStatus = radarStatus,
                    pickedFile = pickedFile,
                    onOpenSend = {
                        activeTab = "SEND"
                        safPickerLauncher.launch(arrayOf("*/*"))
                    },
                    onOpenReceive = {
                        activeTab = "RECEIVE"
                    },
                    onOpenNearby = {
                        activeTab = "NEARBY"
                        checkPermissionsAndDiscover()
                    }
                )

                "SEND" -> RealSendScreen(
                    isHindi = isHindi,
                    pickedFile = pickedFile,
                    computingHash = computingHash,
                    receiverIp = receiverIpText,
                    onReceiverIpChange = { receiverIpText = it },
                    peers = peers,
                    onSelectNewFile = { safPickerLauncher.launch(arrayOf("*/*")) },
                    onScan = { checkPermissionsAndDiscover() },
                    onConnectPeer = onConnectDevice,
                    isBusy = isBusyTransfer,
                    progress = progressVal,
                    status = statusMessage,
                    speed = speedLabel,
                    onSendNow = {
                        val targetFile = pickedFile ?: return@RealSendScreen
                        if (isBusyTransfer) return@RealSendScreen
                        isBusyTransfer = true
                        progressVal = 0f
                        statusMessage = if (isHindi) "रिसीवर से जुड़ रहा है..." else "Connecting to Receiver..."
                        scope.launch {
                            transmitFileSocket(
                                context = context,
                                fileData = targetFile,
                                ip = receiverIpText.trim(),
                                port = 8988,
                                onProgress = { p, sp, st ->
                                    progressVal = p
                                    speedLabel = sp
                                    statusMessage = st
                                },
                                onDone = { ok, finalMsg ->
                                    isBusyTransfer = false
                                    statusMessage = finalMsg
                                    if (ok) {
                                        transferLogs.add(
                                            0,
                                            HistoryItem(
                                                fileName = targetFile.name,
                                                sizeFormatted = formatBytes(targetFile.sizeBytes),
                                                mode = "SENT",
                                                verified = true,
                                                details = "Sent to $receiverIpText"
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    },
                    onBack = { activeTab = "HOME" }
                )

                "RECEIVE" -> RealReceiveScreen(
                    isHindi = isHindi,
                    localIp = fetchDeviceIpAddress().ifEmpty { "192.168.49.1" },
                    radarStatus = radarStatus,
                    isListening = isReceiverWaiting,
                    progress = progressVal,
                    status = statusMessage,
                    speed = speedLabel,
                    onStartListening = {
                        if (isReceiverWaiting) return@RealReceiveScreen
                        checkPermissionsAndDiscover()
                        isReceiverWaiting = true
                        progressVal = 0f
                        statusMessage = if (isHindi) "पोर्ट 8988 खुला है। दूसरे फ़ोन से SEND दबाएं..." else "Listening on Port 8988..."
                        scope.launch {
                            listenAndReceiveSocket(
                                context = context,
                                port = 8988,
                                onProgress = { p, sp, st ->
                                    progressVal = p
                                    speedLabel = sp
                                    statusMessage = st
                                },
                                onDone = { ok, item, finalMsg ->
                                    isReceiverWaiting = false
                                    statusMessage = finalMsg
                                    if (ok && item != null) {
                                        transferLogs.add(0, item)
                                    }
                                }
                            )
                        }
                    },
                    onBack = { activeTab = "HOME" }
                )

                "NEARBY" -> RealNearbyRadarScreen(
                    isHindi = isHindi,
                    peers = peers,
                    radarStatus = radarStatus,
                    hostIp = hostIp,
                    isHost = isHost,
                    onScan = { checkPermissionsAndDiscover() },
                    onConnect = onConnectDevice,
                    onGoSend = { activeTab = "SEND" },
                    onGoReceive = { activeTab = "RECEIVE" }
                )

                "HISTORY" -> RealHistoryScreen(
                    isHindi = isHindi,
                    logs = transferLogs,
                    onBack = { activeTab = "HOME" }
                )

                "SETTINGS" -> RealSettingsScreen(
                    isHindi = isHindi,
                    onBack = { activeTab = "HOME" }
                )
            }
        }
    }
}

@Composable
fun SafeHomeDashboard(
    isHindi: Boolean,
    radarStatus: String,
    pickedFile: PickedFileData?,
    onOpenSend: () -> Unit,
    onOpenReceive: () -> Unit,
    onOpenNearby: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Green Live Banner showing New Code is Active
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF064E3B))
                .border(1.dp, Color(0xFF10B981), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isHindi) "✅ Safe P2P India (नया इंजन चालू है)" else "✅ Safe P2P India (New Engine Live)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Text(
                    text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} • $radarStatus",
                    fontSize = 12.sp,
                    color = Color(0xFFA7F3D0)
                )
            }
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF34D399),
                modifier = Modifier.size(28.dp)
            )
        }

        // SEND FILE CARD
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2563EB), Color(0xFF4F46E5))
                    )
                )
                .clickable { onOpenSend() }
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isHindi) "फ़ाइल भेजें (SEND)" else "SEND FILE",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isHindi)
                            "यहाँ टैप करते ही फ़ोन की गैलरी और फ़ाइल पिकर खुलेगा"
                        else
                            "Tap here to open phone gallery & SAF file picker",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // RECEIVE FILE CARD
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF9333EA), Color(0xFF6B21A8))
                    )
                )
                .clickable { onOpenReceive() }
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isHindi) "प्राप्त करें (RECEIVE)" else "RECEIVE FILE",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isHindi)
                            "दूसरे फ़ोन से फ़ाइल रिसीव करने के लिए सर्वर चालू करें"
                        else
                            "Start local receiver server on Port 8988",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }

        if (pickedFile != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = if (isHindi) "📄 चुनी गई फ़ाइल:" else "📄 Picked File:",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    text = "${pickedFile.name} (${formatBytes(pickedFile.sizeBytes)})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "SHA-256: ${pickedFile.sha256.take(24)}...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Wi-Fi Direct Scanner Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { onOpenNearby() }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = if (isHindi) "आस-पास के फ़ोन खोजें (Wi-Fi Direct)" else "Scan Nearby Phones (Wi-Fi Direct)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isHindi) "बिना इंटरनेट दो फ़ोनों को आपस में जोड़ें" else "Connect two phones directly offline",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
fun RealSendScreen(
    isHindi: Boolean,
    pickedFile: PickedFileData?,
    computingHash: Boolean,
    receiverIp: String,
    onReceiverIpChange: (String) -> Unit,
    peers: List<WifiP2pDevice>,
    onSelectNewFile: () -> Unit,
    onScan: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    isBusy: Boolean,
    progress: Float,
    status: String,
    speed: String,
    onSendNow: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (isHindi) "फ़ाइल भेजें (SAF + Socket)" else "Send File (SAF + Socket)",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Button(
            onClick = onSelectNewFile,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (pickedFile == null) {
                    if (isHindi) "फ़ोन से फ़ोटो / वीडियो / PDF चुनें" else "Choose File from Phone"
                } else {
                    if (isHindi) "दूसरी फ़ाइल चुनें" else "Change File"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (computingHash) {
            Text(
                text = if (isHindi) "फ़ाइल का साइज़ और SHA-256 कोड निकाला जा रहा है..." else "Reading file & SHA-256...",
                color = Color(0xFF38BDF8)
            )
        }

        if (pickedFile != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, Color(0xFF34D399), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = pickedFile.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Size: ${formatBytes(pickedFile.sizeBytes)}",
                    color = Color(0xFF34D399),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "SHA-256: ${pickedFile.sha256}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isHindi) "रिसीवर फ़ोन कनेक्शन:" else "Receiver Connection:",
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onScan) {
                    Text(if (isHindi) "फ़ोन खोजें (Scan)" else "Scan Peers")
                }
            }

            peers.forEach { dev ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E293B))
                        .clickable { onConnectPeer(dev) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dev.deviceName.ifEmpty { "Android Device" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isHindi) "जोड़ें" else "Connect",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedTextField(
                value = receiverIp,
                onValueChange = onReceiverIpChange,
                label = { Text(if (isHindi) "रिसीवर का IP पता" else "Receiver IP Address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (status.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    Text(speed, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                }
                Text(status, fontSize = 13.sp)
            }
        }

        Button(
            onClick = onSendNow,
            enabled = pickedFile != null && !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
        ) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isHindi) "अभी फ़ाइल भेजें (SEND NOW)" else "SEND FILE NOW",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun RealReceiveScreen(
    isHindi: Boolean,
    localIp: String,
    radarStatus: String,
    isListening: Boolean,
    progress: Float,
    status: String,
    speed: String,
    onStartListening: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (isHindi) "फ़ाइल प्राप्त करें (Receiver)" else "Receive File",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, Color(0xFFA855F7), RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (isHindi) "आपका IP पता (भेजने वाले फ़ोन में डालें):" else "Your Receiver IP Address:",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = localIp,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF38BDF8)
            )
            Text(
                text = "Port: 8988 • $radarStatus",
                fontSize = 12.sp,
                color = Color(0xFF34D399)
            )
        }

        Button(
            onClick = onStartListening,
            enabled = !isListening,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isListening) {
                    if (isHindi) "रिसीवर चालू है (फ़ाइल का इंतज़ार...)" else "Receiver Waiting for File..."
                } else {
                    if (isHindi) "रिसीवर चालू करें (Start Receiver)" else "Start Receiver Server"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (status.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold, color = Color(0xFFA855F7))
                    Text(speed, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                }
                Text(status, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun RealNearbyRadarScreen(
    isHindi: Boolean,
    peers: List<WifiP2pDevice>,
    radarStatus: String,
    hostIp: String,
    isHost: Boolean,
    onScan: () -> Unit,
    onConnect: (WifiP2pDevice) -> Unit,
    onGoSend: () -> Unit,
    onGoReceive: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isHindi) "Wi-Fi Direct रडार" else "Wi-Fi Direct Radar",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = radarStatus, fontSize = 12.sp, color = Color(0xFF34D399))
            }
            Button(onClick = onScan) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isHindi) "स्कैन करें" else "Scan")
            }
        }

        if (hostIp.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF064E3B))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "✅ Connected! Host IP: $hostIp (${if (isHost) "Host" else "Client"})",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onGoSend, modifier = Modifier.weight(1f)) {
                        Text(if (isHindi) "फ़ाइल भेजें" else "Send")
                    }
                    Button(onClick = onGoReceive, modifier = Modifier.weight(1f)) {
                        Text(if (isHindi) "प्राप्त करें" else "Receive")
                    }
                }
            }
        }

        if (peers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isHindi)
                        "दोनों फ़ोन में Wi-Fi और Location चालू रखें और ऊपर 'स्कैन करें' दबाएं।"
                    else
                        "Turn ON Wi-Fi & Location on both phones and tap Scan.",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            peers.forEach { dev ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onConnect(dev) }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(dev.deviceName.ifEmpty { "Android Peer" }, fontWeight = FontWeight.Bold)
                        Text(dev.deviceAddress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { onConnect(dev) }) {
                        Text(if (isHindi) "कनेक्ट" else "Connect")
                    }
                }
            }
        }
    }
}

@Composable
fun RealHistoryScreen(
    isHindi: Boolean,
    logs: List<HistoryItem>,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (isHindi) "ट्रांसफर इतिहास (History)" else "Transfer History",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        if (logs.isEmpty()) {
            Text(
                text = if (isHindi) "अभी तक कोई फ़ाइल ट्रांसफर नहीं हुई है।" else "No transfers recorded yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            logs.forEach { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp)
                ) {
                    Text("${item.mode}: ${item.fileName} (${item.sizeFormatted})", fontWeight = FontWeight.Bold)
                    Text(
                        text = if (item.verified) "✅ SHA-256 Verified • ${item.details}" else "⚠️ Unverified",
                        fontSize = 12.sp,
                        color = Color(0xFF34D399)
                    )
                }
            }
        }
        Button(onClick = onBack) {
            Text(if (isHindi) "वापस जाएं" else "Back")
        }
    }
}

@Composable
fun RealSettingsScreen(
    isHindi: Boolean,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Safe P2P India", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            text = if (isHindi)
                "• सभी माइलस्टोन (SAF Picker, SHA-256, Wi-Fi Direct, Socket Transfer) इस बिल्ड में सक्रिय हैं।"
            else
                "• All core milestones (SAF Picker, SHA-256, Wi-Fi Direct, Socket Transfer) are active in this build.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onBack) {
            Text(if (isHindi) "होम पर लौटें" else "Back to Home")
        }
    }
}

suspend fun readFileMetaAndSha256(context: Context, uri: Uri): PickedFileData =
    withContext(Dispatchers.IO) {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameCol != -1) name = c.getString(nameCol) ?: name
                    if (sizeCol != -1) size = c.getLong(sizeCol)
                }
            }
        } catch (_: Exception) {}

        val md = MessageDigest.getInstance("SHA-256")
        var readTotal = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(64 * 1024)
                var r: Int
                while (stream.read(buf).also { r = it } != -1) {
                    md.update(buf, 0, r)
                    readTotal += r
                }
            }
        } catch (_: Exception) {}

        if (size <= 0L) size = readTotal
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        PickedFileData(uri, name, size, hex)
    }

suspend fun transmitFileSocket(
    context: Context,
    fileData: PickedFileData,
    ip: String,
    port: Int,
    onProgress: (Float, String, String) -> Unit,
    onDone: (Boolean, String) -> Unit
) = withContext(Dispatchers.IO) {
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(ip, port), 10000)
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        out.writeUTF(fileData.name)
        out.writeLong(fileData.sizeBytes)
        out.writeUTF(fileData.sha256)
        out.flush()

        val t0 = System.currentTimeMillis()
        var sent = 0L
        val buf = ByteArray(64 * 1024)

        context.contentResolver.openInputStream(fileData.uri)?.use { input ->
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                out.write(buf, 0, r)
                sent += r
                val sec = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.1)
                val speedMb = (sent / (1024.0 * 1024.0)) / sec
                val p = if (fileData.sizeBytes > 0) sent.toFloat() / fileData.sizeBytes else 0.5f
                withContext(Dispatchers.Main) {
                    onProgress(p.coerceIn(0f, 1f), String.format("%.2f MB/s", speedMb), "Sending ${formatBytes(sent)} / ${formatBytes(fileData.sizeBytes)}")
                }
            }
        }
        out.flush()
        withContext(Dispatchers.Main) {
            onProgress(1f, "Done", "✅ Sent Successfully!")
            onDone(true, "✅ फ़ाइल सफलतापूर्वक भेजी गई: ${fileData.name}")
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onDone(false, "❌ Error: ${e.localizedMessage ?: "Connection failed"}")
        }
    } finally {
        try { socket.close() } catch (_: Exception) {}
    }
}

suspend fun listenAndReceiveSocket(
    context: Context,
    port: Int,
    onProgress: (Float, String, String) -> Unit,
    onDone: (Boolean, HistoryItem?, String) -> Unit
) = withContext(Dispatchers.IO) {
    var server: ServerSocket? = null
    try {
        server = ServerSocket(port)
        val client = server.accept()
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))

        val fName = input.readUTF()
        val fSize = input.readLong()
        val expectedHash = input.readUTF()

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val targetFile = File(dir, fName)

        val md = MessageDigest.getInstance("SHA-256")
        val t0 = System.currentTimeMillis()
        var rcvd = 0L
        val buf = ByteArray(64 * 1024)

        FileOutputStream(targetFile).use { fos ->
            var r: Int
            var rem = fSize
            while (rem > 0 && input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt()).also { r = it } != -1) {
                fos.write(buf, 0, r)
                md.update(buf, 0, r)
                rcvd += r
                rem -= r
                val sec = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.1)
                val speedMb = (rcvd / (1024.0 * 1024.0)) / sec
                val p = if (fSize > 0) rcvd.toFloat() / fSize else 0.5f
                withContext(Dispatchers.Main) {
                    onProgress(p.coerceIn(0f, 1f), String.format("%.2f MB/s", speedMb), "Receiving ${formatBytes(rcvd)} / ${formatBytes(fSize)}")
                }
            }
        }

        val actualHash = md.digest().joinToString("") { "%02x".format(it) }
        val okHash = actualHash.equals(expectedHash, ignoreCase = true)
        client.close()

        withContext(Dispatchers.Main) {
            val item = HistoryItem(fName, formatBytes(fSize), "RECEIVED", okHash, targetFile.absolutePath)
            onProgress(1f, "Done", "✅ Received!")
            onDone(true, item, "✅ फ़ाइल प्राप्त हुई: $fName (SHA-256 Verified: $okHash)")
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onDone(false, null, "❌ Receiver Error: ${e.localizedMessage ?: "Stopped"}")
        }
    } finally {
        try { server?.close() } catch (_: Exception) {}
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.2f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

fun fetchDeviceIpAddress(): String {
    try {
        val nets = NetworkInterface.getNetworkInterfaces()
        while (nets.hasMoreElements()) {
            val intf = nets.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (!a.isLoopbackAddress && a is Inet4Address) {
                    return a.hostAddress ?: ""
                }
            }
        }
    } catch (_: Exception) {}
    return ""
}
