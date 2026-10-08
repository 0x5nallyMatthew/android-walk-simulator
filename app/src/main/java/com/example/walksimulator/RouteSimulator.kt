package com.example.walksimulator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Điểm tọa độ đơn giản (tránh trùng tên GeoPoint của osmdroid). */
data class LatLon(val latitude: Double, val longitude: Double)

/**
 * Sinh lộ trình dày (polyline mỗi ~5m) từ danh sách waypoint do người dùng chọn,
 * và tính vị trí tại một quãng đường bất kỳ trên lộ trình.
 */
class RouteSimulator {

    private val points = mutableListOf<LatLon>()
    private val cumulative = mutableListOf<Double>() // quãng đường tích lũy tại từng điểm

    val totalDistanceMeters: Double get() = cumulative.lastOrNull() ?: 0.0
    val polyline: List<LatLon> get() = points.toList()

    /**
     * Tìm đường đi bộ theo đường phố qua OSRM (profile `foot`) — giống bản demo web.
     * Lấy geometry của tuyến trả về làm lộ trình; @return true nếu thành công,
     * false nếu lỗi mạng/không tìm được đường (caller nên fallback về [setWaypoints] nối thẳng).
     */
    suspend fun setWaypointsFromOsrm(waypoints: List<LatLon>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (waypoints.size < 2) return@withContext false
                val coords = waypoints.joinToString(";") {
                    String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude)
                }
                val url = URL("https://router.project-osrm.org/route/v1/foot/$coords?overview=full&geometries=geojson")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                }
                try {
                    if (conn.responseCode != 200) return@withContext false
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    if (json.optString("code") != "Ok") return@withContext false
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
                    if (geometry.length() < 2) return@withContext false

                    points.clear()
                    cumulative.clear()
                    var acc = 0.0
                    val first = geometry.getJSONArray(0)
                    points.add(LatLon(first.getDouble(1), first.getDouble(0)))
                    cumulative.add(0.0)
                    for (i in 1 until geometry.length()) {
                        val c = geometry.getJSONArray(i)
                        val p = LatLon(c.getDouble(1), c.getDouble(0))
                        acc += haversine(points.last(), p)
                        points.add(p)
                        cumulative.add(acc)
                    }
                    true
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                false
            }
        }

    /** Nối thẳng các waypoint (đường chim bay) — fallback khi OSRM không khả dụng. */
    fun setWaypoints(waypoints: List<LatLon>) {
        points.clear()
        cumulative.clear()
        if (waypoints.isEmpty()) return

        points.add(waypoints.first())
        cumulative.add(0.0)

        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]
            val b = waypoints[i]
            val seg = haversine(a, b)
            val n = max(1, ceil(seg / SEGMENT_METERS).toInt())
            repeat(n) { j ->
                val t = (j + 1).toDouble() / n
                points.add(
                    LatLon(
                        a.latitude + (b.latitude - a.latitude) * t,
                        a.longitude + (b.longitude - a.longitude) * t
                    )
                )
                cumulative.add(cumulative.last() + seg / n)
            }
        }
    }

    /** Vị trí tại quãng đường [distance] tính từ điểm xuất phát. */
    fun positionAt(distance: Double): LatLon? {
        if (points.isEmpty()) return null
        val d = distance.coerceIn(0.0, totalDistanceMeters)

        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < d) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return points[0]

        val d0 = cumulative[lo - 1]
        val d1 = cumulative[lo]
        val t = if (d1 > d0) (d - d0) / (d1 - d0) else 0.0
        val a = points[lo - 1]
        val b = points[lo]
        return LatLon(
            a.latitude + (b.latitude - a.latitude) * t,
            a.longitude + (b.longitude - a.longitude) * t
        )
    }

    fun haversine(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    companion object {
        private const val SEGMENT_METERS = 5.0
    }
}
