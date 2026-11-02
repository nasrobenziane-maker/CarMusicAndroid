package com.codex.carmusic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class CarMusicService : Service() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var receiverRegistered = false
    private var targetAddress: String = ""
    private var targetName: String = "السيارة"
    private var songUris: List<Uri> = emptyList()
    private var carConnected = false

    private val carReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = getBluetoothDevice(intent) ?: return
            val address = try { device.address } catch (_: SecurityException) { return }
            if (address != targetAddress || targetAddress.isBlank()) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (!carConnected) {
                        carConnected = true
                        playPlaylist()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    carConnected = false
                    player.pause()
                    promoteForeground(isPlaying = false)
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .build()
        configureMediaSession()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                promoteForeground(isPlaying)
                updatePlaybackState()
                updateNotification()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMediaMetadata(mediaItem)
                updatePlaybackState()
                updateNotification()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ServiceActions.STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        loadSavedSettings()
        registerCarReceiver()
        promoteForeground(isPlaying = player.isPlaying)

        when (action) {
            ServiceActions.PLAY_NOW -> playPlaylist()
            ServiceActions.PAUSE -> player.pause()
            ServiceActions.RESUME -> {
                if (player.mediaItemCount == 0) playPlaylist() else player.play()
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(AppPrefs.FILE, MODE_PRIVATE)
        targetAddress = prefs.getString(AppPrefs.KEY_CAR_ADDRESS, "").orEmpty()
        targetName = prefs.getString(AppPrefs.KEY_CAR_NAME, "السيارة").orEmpty()
        songUris = prefs.getString(AppPrefs.KEY_SONG_URIS, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }.map(Uri::parse)
    }

    private fun registerCarReceiver() {
        if (receiverRegistered || !hasBluetoothPermission()) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(carReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(carReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: SecurityException) {
            updateNotification("اسمح بإذن الأجهزة القريبة حتى يراقب التطبيق اتصال السيارة.")
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun getBluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun playPlaylist() {
        if (songUris.isEmpty()) {
            updateNotification("اختر أغاني من التطبيق أولاً.")
            return
        }
        if (targetAddress.isBlank()) {
            updateNotification("اختر اسم السيارة وابدأ المراقبة من التطبيق.")
            return
        }

        val items = songUris.mapIndexed { index, uri ->
            val title = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "أغنية ${index + 1}"
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        }
        promoteForeground(isPlaying = true)
        player.setMediaItems(items, true)
        player.prepare()
        player.play()
    }

    private fun configureMediaSession() {
        mediaSession = MediaSession(this, "CarMusic")
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (player.mediaItemCount == 0) playPlaylist() else player.play()
            }

            override fun onPause() = player.pause()
            override fun onSkipToNext() = player.seekToNextMediaItem()
            override fun onSkipToPrevious() = player.seekToPreviousMediaItem()
            override fun onStop() = player.pause()
        })
        mediaSession.isActive = true
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        if (!::mediaSession.isInitialized) return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        val state = when {
            player.isPlaying -> PlaybackState.STATE_PLAYING
            player.mediaItemCount > 0 -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, player.currentPosition, player.playbackParameters.speed)
                .build()
        )
    }

    private fun updateMediaMetadata(item: MediaItem?) {
        val title = item?.mediaMetadata?.title?.toString() ?: "موسيقى السيارة"
        mediaSession.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, targetName)
                .build()
        )
    }

    private fun promoteForeground(isPlaying: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (isPlaying) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String? = null) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String? = null): Notification {
        val title = if (playerOrNullIsPlaying()) "يُشغّل الآن" else "مراقبة اتصال السيارة"
        val text = message ?: if (playerOrNullIsPlaying()) {
            player.currentMediaItem?.mediaMetadata?.title?.toString() ?: targetName
        } else {
            "بانتظار اتصال ${targetName.ifBlank { "السيارة" }}"
        }
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playAction = if (playerOrNullIsPlaying()) ServiceActions.PAUSE else ServiceActions.RESUME
        val playIcon = if (playerOrNullIsPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playLabel = if (playerOrNullIsPlaying()) "إيقاف مؤقت" else "تشغيل"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(android.app.Notification.Action.Builder(playIcon, playLabel, servicePendingIntent(playAction)).build())
            .addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", servicePendingIntent(ServiceActions.STOP)).build())
            .build()
    }

    private fun playerOrNullIsPlaying(): Boolean = ::player.isInitialized && player.isPlaying

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, CarMusicService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "مراقبة موسيقى السيارة", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "إشعار دائم أثناء انتظار اتصال بلوتوث السيارة"
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(carReceiver) } catch (_: IllegalArgumentException) { }
            receiverRegistered = false
        }
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        if (::player.isInitialized) player.release()
        getSharedPreferences(AppPrefs.FILE, MODE_PRIVATE).edit()
            .putBoolean(AppPrefs.KEY_MONITORING, false).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "car_music_monitor"
        private const val NOTIFICATION_ID = 7041
    }
}
