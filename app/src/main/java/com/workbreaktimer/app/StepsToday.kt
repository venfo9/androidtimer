package com.workbreaktimer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's step total from Health Connect, the same number Google Fit or another fitness app
 * shows. The app only reads; whichever fitness app is installed writes the steps there, so
 * the total can lag behind by the time that app takes to sync.
 */
object StepsToday {

    private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    val READ_STEPS: String = HealthPermission.getReadPermission(StepsRecord::class)

    enum class Availability { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

    fun availability(context: Context): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_INSTALL
        else -> Availability.UNAVAILABLE
    }

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** Play Store page of Health Connect, for phones before Android 14 where it is an app. */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun hasPermission(context: Context): Boolean = runCatching {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().contains(READ_STEPS)
    }.getOrDefault(false)

    /** Steps since local midnight, or null when Health Connect refused to answer. */
    suspend fun read(context: Context): Long? = runCatching {
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val result = HealthConnectClient.getOrCreate(context).aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(startOfDay, Instant.now()))
        )
        result[StepsRecord.COUNT_TOTAL] ?: 0L
    }.getOrNull()
}
