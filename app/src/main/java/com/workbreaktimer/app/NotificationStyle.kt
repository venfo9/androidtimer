package com.workbreaktimer.app

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Every notification's small icon sits in a circle tinted by NotificationCompat's accent
 * color, which otherwise defaults to the system's gray. Call this last so it is not
 * overwritten by an earlier field.
 */
fun NotificationCompat.Builder.withAccentColor(context: Context): NotificationCompat.Builder =
    setColor(ContextCompat.getColor(context, R.color.notification_icon_background))
