package com.example.walksimulator

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId

/** Ghi số bước chân vào Health Connect — app khác (app sự kiện) đọc được. */
class HealthConnectManager(private val context: Context) {

    companion object {
        val PERMISSIONS = setOf(HealthPermission.createWritePermission(StepsRecord::class))

        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermission(): Boolean = try {
        client.permissionController.getGrantedPermissions(PERMISSIONS).containsAll(PERMISSIONS)
    } catch (_: Exception) {
        false
    }

    suspend fun writeSteps(start: Instant, end: Instant, count: Long) {
        if (count <= 0) return
        client.insertRecords(listOf(StepsRecord(count = count, startTime = start, endTime = end)))
    }

    suspend fun todaySteps(): Long = try {
        val zone = ZoneId.systemDefault()
        val startOfDay = Instant.now().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val result = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(startOfDay, Instant.now()))
        )
        result.records.sumOf { it.count }
    } catch (_: Exception) {
        0L
    }
}
