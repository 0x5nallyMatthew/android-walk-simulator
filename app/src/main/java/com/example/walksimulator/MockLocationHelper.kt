package com.example.walksimulator

import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/**
 * Đăng ký GPS test provider và đẩy vị trí giả vào hệ thống.
 * Yêu cầu: người dùng đã chọn app này trong
 * "Tuỳ chọn nhà phát triển → Chọn ứng dụng vị trí mô phỏng".
 * Vị trí giả sẽ được MỌI app đọc được (khác iOS).
 */
object MockLocationHelper {

    private const val PROVIDER = LocationManager.GPS_PROVIDER

    /** @return true nếu đăng ký thành công, false nếu chưa được chọn làm mock location app. */
    fun register(context: Context): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try { lm.removeTestProvider(PROVIDER) } catch (_: Exception) {}
        lm.addTestProvider(
            PROVIDER,
            false, false, false, false,
            true, true, true,
            Criteria.POWER_HIGH,
            Criteria.ACCURACY_FINE
        )
        lm.setTestProviderEnabled(PROVIDER, true)
        true
    } catch (_: Exception) {
        false
    }

    fun push(context: Context, lat: Double, lon: Double, speedMps: Float, accuracy: Float = 5f) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = Location(PROVIDER).apply {
            latitude = lat
            longitude = lon
            altitude = 10.0
            this.accuracy = accuracy
            this.speed = speedMps
            bearing = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        try {
            lm.setTestProviderLocation(PROVIDER, loc)
        } catch (_: Exception) {}
    }

    fun unregister(context: Context) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeTestProvider(PROVIDER)
        } catch (_: Exception) {}
    }
}
