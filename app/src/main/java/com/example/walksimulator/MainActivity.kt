package com.example.walksimulator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid cần user-agent riêng để tải tile OpenStreetMap
        Configuration.getInstance().userAgentValue = packageName
        setContent {
            MaterialTheme {
                WalkSimulatorScreen()
            }
        }
    }
}

@Composable
fun WalkSimulatorScreen() {
    val context = LocalContext.current

    // ----- Trạng thái UI -----
    val waypoints = remember { mutableStateListOf<LatLon>() }
    var speedKmh by remember { mutableStateOf(5f) }
    var durationMin by remember { mutableStateOf(30f) }
    var loop by remember { mutableStateOf(true) }
    var locationGranted by remember { mutableStateOf(false) }
    var healthGranted by remember { mutableStateOf(false) }
    var showMockDialog by remember { mutableStateOf(false) }
    var mapView by remember { mutableStateOf<MapView?>(null) }

    // ----- Quyền -----
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> locationGranted = result.values.all { it } }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> healthGranted = granted.isNotEmpty() }

    LaunchedEffect(Unit) {
        locationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) {
        if (HealthConnectManager.isAvailable(context)) {
            healthGranted = HealthConnectManager(context).hasPermission()
        }
    }

    // Vẽ lại lộ trình khi waypoint thay đổi
    LaunchedEffect(waypoints.size) {
        mapView?.let { redrawRoute(it, waypoints.toList()) }
    }

    fun startWalk() {
        if (waypoints.size < 2) {
            Toast.makeText(context, "Chạm bản đồ để chọn ít nhất 2 điểm", Toast.LENGTH_SHORT).show()
            return
        }
        if (!locationGranted) {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Đăng ký mock provider trước — nếu fail nghĩa là chưa chọn app trong Developer Options
        if (!MockLocationHelper.register(context)) {
            showMockDialog = true
            return
        }
        WalkSessionConfig.waypoints = waypoints.toList()
        WalkSessionConfig.speedKmh = speedKmh.toDouble()
        WalkSessionConfig.durationMinutes = durationMin.toInt()
        WalkSessionConfig.loop = loop
        ContextCompat.startForegroundService(context, Intent(context, MockLocationService::class.java))
    }

    Column(Modifier.fillMaxSize()) {

        // ----- Bản đồ: chạm để thêm điểm, giữ lâu để xóa điểm cuối -----
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                MapView(ctx).apply {
                    mapView = this
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(21.0285, 105.8542))
                    overlays.add(
                        MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                if (p != null) {
                                    waypoints.add(LatLon(p.latitude, p.longitude))
                                }
                                return true
                            }

                            override fun longPressHelper(p: GeoPoint?): Boolean {
                                if (waypoints.isNotEmpty()) waypoints.removeAt(waypoints.size - 1)
                                return true
                            }
                        })
                    )
                }
            }
        )

        // ----- Bảng điều khiển -----
        Card(Modifier.padding(12.dp)) {
            Column(Modifier.padding(14.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Điểm đến: ${waypoints.size}  (chạm = thêm, giữ lâu = xóa điểm cuối)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.weight(1f))
                    if (waypoints.isNotEmpty()) {
                        TextButton(onClick = { waypoints.clear() }) { Text("Xóa hết") }
                    }
                }

                Text("Tốc độ đi: %.1f km/h".format(speedKmh))
                Slider(
                    value = speedKmh,
                    onValueChange = { speedKmh = it },
                    valueRange = 1f..10f
                )

                Text("Thời gian: %d phút".format(durationMin.toInt()))
                Slider(
                    value = durationMin,
                    onValueChange = { durationMin = it },
                    valueRange = 5f..240f,
                    steps = 46 // bước nhảy 5 phút
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = loop, onCheckedChange = { loop = it })
                    Text("Lặp lộ trình khi đi hết")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startWalk() }, enabled = !WalkSessionState.isRunning) {
                        Text(if (WalkSessionState.isRunning) "Đang chạy…" else "Bắt đầu")
                    }
                    OutlinedButton(
                        onClick = { context.stopService(Intent(context, MockLocationService::class.java)) },
                        enabled = WalkSessionState.isRunning
                    ) { Text("Dừng") }
                }

                Spacer(Modifier.height(8.dp))

                // ----- Thống kê realtime (đọc từ service) -----
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatBox("Thời gian", "%d:%02d".format(WalkSessionState.elapsedSeconds / 60, WalkSessionState.elapsedSeconds % 60))
                    StatBox("Đã đi", "%.0f m".format(WalkSessionState.distanceMeters))
                    StatBox("Số bước", "%,d".format(WalkSessionState.steps))
                }

                Spacer(Modifier.height(8.dp))

                // ----- Quyền Health Connect -----
                if (!HealthConnectManager.isAvailable(context)) {
                    Text(
                        "Thiết bị chưa có Health Connect (Android 13 cần cài app Health Connect; Android 14+ có sẵn).",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (!healthGranted) {
                    Button(onClick = { healthLauncher.launch(HealthConnectManager.PERMISSIONS) }) {
                        Text("Cấp quyền ghi số bước (Health Connect)")
                    }
                }

                Text(
                    "Yêu cầu: bật Tuỳ chọn nhà phát triển → \"Chọn ứng dụng vị trí mô phỏng\" → chọn Walk Simulator. " +
                        "Lưu ý: app khác có thể phát hiện vị trí giả qua cờ isMock() của Android.",
                    style = MaterialTheme.typography.bodySmall
                )

                WalkSessionState.routeIsStreet?.let { isStreet ->
                    Text(
                        if (isStreet) "🛣 Lộ trình: đường bộ (OSRM foot) — bám theo phố, không cắt hồ/sông"
                        else "⚠ Lộ trình: nối thẳng (OSRM lỗi/không mạng) — có thể cắt qua hồ",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showMockDialog) {
        AlertDialog(
            onDismissRequest = { showMockDialog = false },
            title = { Text("Cần bật Mock Location") },
            text = {
                Text(
                    "1. Cài đặt → Giới thiệu điện thoại → bấm 7 lần vào \"Số bản dựng\" để bật Tuỳ chọn nhà phát triển\n" +
                        "2. Tuỳ chọn nhà phát triển → \"Chọn ứng dụng vị trí mô phỏng\" → chọn Walk Simulator\n" +
                        "3. Quay lại app và bấm Bắt đầu"
                )
            },
            confirmButton = {
                TextButton(onClick = { showMockDialog = false }) { Text("Đã hiểu") }
            }
        )
    }
}

@Composable
fun StatBox(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Vẽ polyline qua các waypoint + marker cho từng điểm. */
private fun redrawRoute(map: MapView, waypoints: List<LatLon>) {
    // Giữ lại overlay đầu tiên (MapEventsOverlay bắt chạm)
    while (map.overlays.size > 1) {
        map.overlays.removeAt(map.overlays.size - 1)
    }
    if (waypoints.isNotEmpty()) {
        val line = Polyline(map.context).apply {
            setPoints(waypoints.map { GeoPoint(it.latitude, it.longitude) })
        }
        map.overlays.add(line)
        waypoints.forEach { wp ->
            map.overlays.add(
                Marker(map).apply {
                    position = GeoPoint(wp.latitude, wp.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
            )
        }
    }
    map.invalidate()
}
