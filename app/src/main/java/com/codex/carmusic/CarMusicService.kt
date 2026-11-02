package com.codex.carmusic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class CarMusicService : Service() {
    private lateinit var player: ExoPlayer
    private var secondaryPlayer: ExoPlayer? = null
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var receiverRegistered = false
    private var targetAddress = ""
    private var targetName = "Car"
    private var songUris: List<Uri> = emptyList()
    private var songTitles: List<String> = emptyList()
    private var currentIndex = -1
    private var carConnected = false
    private var shuffleEnabled = false
    private var repeatMode = 0 // 0 off, 1 all, 2 one
    private var disconnectTimeoutPassed = false
    private var disconnectTimerPending = false
    private var isCrossfading = false
    private var pendingCrossfadeIndex = -1
    private var crossfadeStartedAt = 0L
    private var crossfadeDurationMs = CROSSFADE_MS
    private val handler = Handler(Looper.getMainLooper())

    private val disconnectTimeoutRunnable = Runnable {
        disconnectTimerPending = false
        if (!carConnected) {
            disconnectTimeoutPassed = true
            updateNotification()
        }
    }

    private val crossfadeRunnable = object : Runnable {
        override fun run() {
            val incoming = secondaryPlayer ?: return
            if (!isCrossfading) return
            val fraction = ((SystemClock.elapsedRealtime() - crossfadeStartedAt).toFloat() /
                crossfadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            player.volume = 1f - fraction
            incoming.volume = fraction
            if (fraction >= 1f) completeCrossfade() else handler.postDelayed(this, FADE_TICK_MS)
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            sendPlaybackState()
            handler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private val crossfadeWatchRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying && !isCrossfading && songUris.size > 1 &&
                player.duration > 0L && player.duration != C.TIME_UNSET
            ) {
                val remaining = player.duration - player.currentPosition
                if (remaining in 1..CROSSFADE_MS) {
                    val next = nextIndex(direction = 1, automatic = true)
                    if (next >= 0) beginCrossfade(next)
                }
            }
            handler.postDelayed(this, CROSSFADE_CHECK_MS)
        }
    }

    private val carReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) {
                    handler.removeCallbacks(disconnectTimeoutRunnable)
                    disconnectTimerPending = false
                    disconnectTimeoutPassed = false
                    updateNotification("Bluetooth was turned off manually.")
                }
                return
            }
            val device = getBluetoothDevice(intent) ?: return
            val address = try { device.address } catch (_: SecurityException) { return }
            if (address != targetAddress || targetAddress.isBlank()) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    handler.removeCallbacks(disconnectTimeoutRunnable)
                    disconnectTimerPending = false
                    disconnectTimeoutPassed = false
                    if (!carConnected) {
                        carConnected = true
                        startPlaylist()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (carConnected) {
                    carConnected = false
                    disconnectTimeoutPassed = false
                    pausePlayback()
                    promoteForeground(isPlaying = false)
                    handler.removeCallbacks(disconnectTimeoutRunnable)
                    disconnectTimerPending = true
                    handler.postDelayed(disconnectTimeoutRunnable, BLUETOOTH_OFF_DELAY_MS)
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        player = createPlayer()
        attachPlayerListeners(player)
        configureMediaSession()
        handler.post(progressRunnable)
        handler.post(crossfadeWatchRunnable)
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
            ServiceActions.PLAY_NOW, ServiceActions.START -> if (action == ServiceActions.PLAY_NOW) startPlaylist()
            ServiceActions.UPDATE_QUEUE -> refreshQueue()
            ServiceActions.PAUSE -> pausePlayback()
            ServiceActions.RESUME -> resumePlayback()
            ServiceActions.NEXT -> navigate(direction = 1)
            ServiceActions.PREVIOUS -> navigate(direction = -1)
            ServiceActions.SEEK -> {
                cancelCrossfade()
                player.seekTo(intent.getLongExtra(ServiceActions.EXTRA_POSITION, 0L).coerceAtLeast(0L))
                sendPlaybackState()
            }
            ServiceActions.SELECT_TRACK -> {
                val index = intent.getIntExtra(ServiceActions.EXTRA_INDEX, -1)
                if (index in songUris.indices) transitionTo(index)
            }
            ServiceActions.SET_SHUFFLE, ServiceActions.SET_REPEAT -> sendPlaybackState()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(this)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
        )
        .build()

    private fun attachPlayerListeners(target: ExoPlayer) {
        target.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (target === player) {
                    promoteForeground(isPlaying)
                    updatePlaybackState()
                    updateNotification()
                    sendPlaybackState()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMediaMetadata(mediaItem)
                sendPlaybackState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (target === secondaryPlayer && playbackState == Player.STATE_READY) startCrossfadeWhenReady(target)
                if (target === player && playbackState == Player.STATE_ENDED && !isCrossfading) advanceAutomatically()
                if (target === player) sendPlaybackState()
            }
        })
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(AppPrefs.FILE, MODE_PRIVATE)
        targetAddress = prefs.getString(AppPrefs.KEY_CAR_ADDRESS, "").orEmpty()
        targetName = prefs.getString(AppPrefs.KEY_CAR_NAME, "Car").orEmpty()
        songUris = prefs.getString(AppPrefs.KEY_SONG_URIS, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }.map(Uri::parse)
        songTitles = prefs.getString(AppPrefs.KEY_SONG_TITLES, "").orEmpty()
            .split('\n').let { stored ->
                songUris.indices.map { index -> stored.getOrNull(index).orEmpty().ifBlank { "Track ${index + 1}" } }
            }
        shuffleEnabled = prefs.getBoolean(AppPrefs.KEY_SHUFFLE, false)
        repeatMode = prefs.getInt(AppPrefs.KEY_REPEAT_MODE, 0).coerceIn(0, 2)
    }

    private fun refreshQueue() {
        val currentUri = songUris.getOrNull(currentIndex)
        loadSavedSettings()
        val newIndex = currentUri?.let { uri -> songUris.indexOf(uri) } ?: -1
        if (newIndex >= 0) currentIndex = newIndex
        else if (currentUri != null && songUris.isNotEmpty()) {
            currentIndex = currentIndex.coerceIn(0, songUris.lastIndex)
            loadTrack(currentIndex, play = player.isPlaying)
            return
        } else if (currentIndex !in songUris.indices) currentIndex = if (songUris.isEmpty()) -1 else 0
        if (songUris.isEmpty()) {
            cancelCrossfade()
            player.stop()
            player.clearMediaItems()
            currentIndex = -1
        } else if (player.mediaItemCount == 0 && currentIndex >= 0) {
            loadTrack(currentIndex, play = false)
        }
        sendPlaybackState()
    }

    private fun registerCarReceiver() {
        if (receiverRegistered || !hasBluetoothPermission()) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(carReceiver, filter, Context.RECEIVER_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(carReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: SecurityException) { updateNotification("Allow Nearby devices permission to monitor your car.") }
    }

    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun getBluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun startPlaylist() {
        if (songUris.isEmpty()) { updateNotification("Add songs to the playlist first."); return }
        if (targetAddress.isBlank()) { updateNotification("Choose your paired car in the app first."); return }
        if (currentIndex !in songUris.indices) currentIndex = 0
        loadTrack(currentIndex, play = true)
    }

    private fun loadTrack(index: Int, play: Boolean) {
        if (index !in songUris.indices) return
        cancelCrossfade()
        currentIndex = index
        player.volume = 1f
        player.setMediaItem(mediaItem(index))
        player.prepare()
        player.playWhenReady = play
        if (play) player.play()
        updateMediaMetadata(player.currentMediaItem)
        updatePlaybackState()
        sendPlaybackState()
    }

    private fun mediaItem(index: Int): MediaItem = MediaItem.Builder()
        .setUri(songUris[index])
        .setMediaMetadata(MediaMetadata.Builder().setTitle(songTitles.getOrNull(index) ?: "Track ${index + 1}").build())
        .build()

    private fun navigate(direction: Int) {
        if (songUris.isEmpty()) return
        if (currentIndex !in songUris.indices) { startPlaylist(); return }
        val index = nextIndex(direction, automatic = false)
        if (index >= 0) transitionTo(index)
    }

    private fun nextIndex(direction: Int, automatic: Boolean): Int {
        if (songUris.isEmpty()) return -1
        if (repeatMode == 2 && automatic) return currentIndex
        if (shuffleEnabled && songUris.size > 1) {
            val choices = songUris.indices.filter { it != currentIndex }
            return choices.random()
        }
        val target = currentIndex + direction
        if (target in songUris.indices) return target
        if (!automatic || repeatMode == 1) return (target + songUris.size) % songUris.size
        return -1
    }

    private fun advanceAutomatically() {
        val index = nextIndex(direction = 1, automatic = true)
        if (index < 0) {
            player.pause()
            updatePlaybackState()
            sendPlaybackState()
        } else transitionTo(index)
    }

    private fun transitionTo(index: Int) {
        if (index !in songUris.indices) return
        if (isCrossfading) cancelCrossfade()
        if (player.mediaItemCount == 0) {
            loadTrack(index, play = true)
        } else if (!player.isPlaying) {
            loadTrack(index, play = false)
        } else beginCrossfade(index)
    }

    private fun beginCrossfade(index: Int) {
        if (isCrossfading || index !in songUris.indices) return
        val incoming = createPlayer()
        secondaryPlayer = incoming
        pendingCrossfadeIndex = index
        incoming.volume = 0f
        attachPlayerListeners(incoming)
        incoming.setMediaItem(mediaItem(index))
        incoming.prepare()
    }

    private fun startCrossfadeWhenReady(incoming: ExoPlayer) {
        if (incoming !== secondaryPlayer || isCrossfading) return
        isCrossfading = true
        incoming.volume = 0f
        incoming.play()
        crossfadeDurationMs = CROSSFADE_MS
        crossfadeStartedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(crossfadeRunnable)
        handler.post(crossfadeRunnable)
    }

    private fun completeCrossfade() {
        val incoming = secondaryPlayer ?: return
        val outgoing = player
        val nextIndex = pendingCrossfadeIndex
        incoming.volume = 1f
        player = incoming
        secondaryPlayer = null
        currentIndex = nextIndex
        pendingCrossfadeIndex = -1
        isCrossfading = false
        outgoing.release()
        updateMediaMetadata(player.currentMediaItem)
        updatePlaybackState()
        updateNotification()
        sendPlaybackState()
    }

    private fun cancelCrossfade() {
        handler.removeCallbacks(crossfadeRunnable)
        secondaryPlayer?.release()
        secondaryPlayer = null
        pendingCrossfadeIndex = -1
        isCrossfading = false
        if (::player.isInitialized) player.volume = 1f
    }

    private fun pausePlayback() {
        cancelCrossfade()
        if (::player.isInitialized) player.pause()
        updatePlaybackState()
        sendPlaybackState()
    }

    private fun resumePlayback() {
        if (player.mediaItemCount == 0) startPlaylist() else player.play()
    }

    private fun configureMediaSession() {
        mediaSession = MediaSession(this, "CarMusic")
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resumePlayback()
            override fun onPause() = pausePlayback()
            override fun onSkipToNext() = navigate(1)
            override fun onSkipToPrevious() = navigate(-1)
            override fun onStop() = pausePlayback()
        })
        mediaSession.isActive = true
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        if (!::mediaSession.isInitialized || !::player.isInitialized) return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO
        val state = when {
            player.isPlaying -> PlaybackState.STATE_PLAYING
            player.mediaItemCount > 0 -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(state, player.currentPosition, player.playbackParameters.speed)
                .build()
        )
    }

    private fun updateMediaMetadata(item: MediaItem?) {
        if (!::mediaSession.isInitialized) return
        val title = item?.mediaMetadata?.title?.toString() ?: "Peugeot Car Music"
        mediaSession.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, targetName)
                .build()
        )
    }

    private fun sendPlaybackState() {
        if (!::player.isInitialized) return
        val intent = Intent(ServiceActions.PLAYBACK_STATE).setPackage(packageName)
            .putExtra(ServiceActions.EXTRA_IS_PLAYING, player.isPlaying)
            .putExtra(ServiceActions.EXTRA_INDEX, currentIndex)
            .putExtra(ServiceActions.EXTRA_TITLE, songTitles.getOrNull(currentIndex).orEmpty())
            .putExtra(ServiceActions.EXTRA_POSITION, player.currentPosition.coerceAtLeast(0L))
            .putExtra(ServiceActions.EXTRA_DURATION, if (player.duration > 0L && player.duration != C.TIME_UNSET) player.duration else 0L)
            .putExtra(ServiceActions.EXTRA_SHUFFLE, shuffleEnabled)
            .putExtra(ServiceActions.EXTRA_REPEAT, repeatMode)
        sendBroadcast(intent)
    }

    private fun promoteForeground(isPlaying: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (isPlaying) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(message: String? = null) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String? = null): Notification {
        val isPlaying = playerOrNullIsPlaying()
        val title = if (isPlaying) "Playing now" else "Car Music"
        val text = message ?: when {
            isPlaying -> player.currentMediaItem?.mediaMetadata?.title?.toString() ?: targetName
            disconnectTimeoutPassed -> "Car disconnected. Open Bluetooth settings to switch it off."
            disconnectTimerPending -> "Car disconnected. Reminder in 60 seconds."
            else -> "Waiting for $targetName"
        }
        val openApp = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playAction = if (isPlaying) ServiceActions.PAUSE else ServiceActions.RESUME
        val playIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playLabel = if (isPlaying) "Pause" else "Play"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(text)
            .setContentIntent(openApp).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true)
            .addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", servicePendingIntent(ServiceActions.PREVIOUS)).build())
            .addAction(android.app.Notification.Action.Builder(playIcon, playLabel, servicePendingIntent(playAction)).build())
            .addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", servicePendingIntent(ServiceActions.NEXT)).build())
        if (disconnectTimeoutPassed && !carConnected) {
            builder.addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_menu_preferences,
                "Bluetooth settings", PendingIntent.getActivity(this, 12, Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        return builder.build()
    }

    private fun playerOrNullIsPlaying(): Boolean = ::player.isInitialized && player.isPlaying

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, CarMusicService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Peugeot Car Music", NotificationManager.IMPORTANCE_LOW))
        }
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try { unregisterReceiver(carReceiver) } catch (_: IllegalArgumentException) { }
            receiverRegistered = false
        }
        secondaryPlayer?.release()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        if (::player.isInitialized) player.release()
        getSharedPreferences(AppPrefs.FILE, MODE_PRIVATE).edit().putBoolean(AppPrefs.KEY_MONITORING, false).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "car_music_monitor"
        private const val NOTIFICATION_ID = 7041
        private const val BLUETOOTH_OFF_DELAY_MS = 60_000L
        private const val CROSSFADE_MS = 2_000L
        private const val FADE_TICK_MS = 40L
        private const val CROSSFADE_CHECK_MS = 100L
        private const val PROGRESS_TICK_MS = 500L
    }
}
