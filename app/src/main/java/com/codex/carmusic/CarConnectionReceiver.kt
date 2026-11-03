package com.codex.carmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Declared in the manifest so Android can wake the app when the selected car connects,
 * even if the playback service was stopped or the process was killed.
 */
class CarConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AppPrefs.KEY_MONITORING, false)) return
        val target = prefs.getString(AppPrefs.KEY_CAR_ADDRESS, "").orEmpty()
        if (target.isBlank()) return
        val address = try { intent.bluetoothDevice()?.address } catch (_: SecurityException) { null }
        if (address != target) return

        val serviceAction = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> ServiceActions.CAR_ACL_CONNECTED
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                if (state == BluetoothProfile.STATE_CONNECTED) ServiceActions.CAR_A2DP_CONNECTED else return
            }
            else -> return
        }
        EventLog.add(context, "Receiver: ${intent.action?.substringAfterLast('.')}, starting service")

        val serviceIntent = Intent(context, CarMusicService::class.java).setAction(serviceAction)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (e: RuntimeException) {
            EventLog.add(context, "Service start blocked: ${e.javaClass.simpleName}")
            if (serviceAction == ServiceActions.CAR_A2DP_CONNECTED) showTapToStart(context)
        }
    }

    /** Fallback when Android refuses to start the service from the background. */
    private fun showTapToStart(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "Car connected", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).putExtra(ServiceActions.EXTRA_AUTOSTART, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, ALERT_CHANNEL_ID)
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Car connected")
            .setContentText("Tap to start your music.").setContentIntent(open).setAutoCancel(true)
        manager.notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    private companion object {
        const val ALERT_CHANNEL_ID = "car_music_alert"
        const val ALERT_NOTIFICATION_ID = 7042
    }
}
