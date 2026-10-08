package com.example.walksimulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.min

/** Cấu hình phiên — set từ UI trước khi start service. */
object WalkSessionConfig {
    var waypoints: List<LatLon> = emptyList()
    var speedKmh: Double = 5.0
    var durationMinutes: Int = 30
    var loop: Boolean = true
}

/** Trạng thái phiên — UI đọc trực tiếp (Compose state, cập nhật realtime). */
object WalkSessionState {
    var isRunning by mutableStateOf(false)
    var elapsedSeconds by mutableStateOf(0)
    var distanceMeters by mutableStateOf(0.0)
    var steps by mutableStateOf(0L)
    /** true nếu lộ trình là đường bộ (OSRM), false nếu nối thẳng (fallback). */
    var routeIsStreet by mutableStateOf<Boolean?>(null)
}

/**
 * Foreground service đi bộ giả lập: mỗi giây đẩy 1 vị trí giả vào hệ thống
 * theo lộ trình + tốc độ đã chọn, ghi số bước vào Health Connect theo lô mỗi 60s.
 * Chạy được cả khi tắt màn hình (foreground service loại location).
 */
class MockLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var health: HealthConnectManager

    override fun onCreate() {
        super.onCreate()
        health = HealthConnectManager(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        WalkSessionState.isRunning = true
        startWalkLoop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun startWalkLoop() {
        // Một coroutine duy nhất: gọi OSRM (IO) → fallback nối thẳng nếu lỗi → đi bộ mỗi giây.
        // Thất bại OSRM không làm chết phiên — chỉ mất tính bám đường phố.
        job = scope.launch {
            val route = RouteSimulator()
            val ok = route.setWaypointsFromOsrm(WalkSessionConfig.waypoints)
            if (!ok) {
                route.setWaypoints(WalkSessionConfig.waypoints)
            }
            WalkSessionState.routeIsStreet = ok
            runWalkLoop(route)
        }
    }

    private suspend fun runWalkLoop(route: RouteSimulator) {
        val speedMps = WalkSessionConfig.speedKmh / 3.6
        val stride = 0.72 // mét/bước
        val durationSec = WalkSessionConfig.durationMinutes * 60
        val loop = WalkSessionConfig.loop

        WalkSessionState.elapsedSeconds = 0
        WalkSessionState.distanceMeters = 0.0
        WalkSessionState.steps = 0

        var flushedSteps = 0L
        var flushStart = System.currentTimeMillis()

        MockLocationHelper.register(this@MockLocationService)
        try {
            while (isActive) {
                val elapsed = WalkSessionState.elapsedSeconds
                if (elapsed >= durationSec) break

                val distance = speedMps * elapsed
                val total = route.totalDistanceMeters
                if (total <= 0) break
                if (!loop && distance >= total) break

                val posDistance = if (loop) distance % total else min(distance, total)
                route.positionAt(posDistance)?.let {
                    MockLocationHelper.push(
                        this@MockLocationService,
                        it.latitude, it.longitude,
                        speedMps.toFloat()
                    )
                }

                WalkSessionState.distanceMeters = distance
                WalkSessionState.steps = (distance / stride).toLong()

                // Ghi Health Connect theo lô mỗi 60 giây
                if (elapsed > 0 && elapsed % 60 == 0) {
                    val delta = WalkSessionState.steps - flushedSteps
                    if (delta > 0) {
                        val now = System.currentTimeMillis()
                        runCatching {
                            health.writeSteps(
                                Instant.ofEpochMilli(flushStart),
                                Instant.ofEpochMilli(now),
                                delta
                            )
                        }
                        flushedSteps = WalkSessionState.steps
                        flushStart = now
                    }
                }

                delay(1000)
                WalkSessionState.elapsedSeconds += 1
            }

            // Flush lô cuối khi kết thúc phiên
            val delta = WalkSessionState.steps - flushedSteps
            if (delta > 0) {
                runCatching {
                    health.writeSteps(Instant.ofEpochMilli(flushStart), Instant.now(), delta)
                }
            }
        } finally {
            MockLocationHelper.unregister(this@MockLocationService)
            WalkSessionState.isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Giả lập đi bộ",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Đang giả lập đi bộ")
            .setContentText("Đang cập nhật vị trí & số bước…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        // minSdk 29 nên luôn dùng bản có foregroundServiceType
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    companion object {
        private const val CHANNEL_ID = "walk_simulator"
        private const val NOTIFICATION_ID = 1
    }
}
