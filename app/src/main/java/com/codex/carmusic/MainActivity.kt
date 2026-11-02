package com.codex.carmusic

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var deviceSpinner: Spinner
    private lateinit var playlistContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var currentSongText: TextView
    private lateinit var currentArtistText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var durationText: TextView
    private lateinit var songSeekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var repeatButton: Button
    private lateinit var pairedDevices: List<BluetoothDevice>
    private var enablePromptRequested = false
    private var receiverRegistered = false
    private var seekInProgress = false
    private var durationMs = 0L
    private var currentIndex = -1
    private var isPlaying = false
    private var shuffleEnabled = false
    private var repeatMode = 0
    private var renderedCurrentIndex = Int.MIN_VALUE
    private val prefs by lazy { getSharedPreferences(AppPrefs.FILE, MODE_PRIVATE) }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ServiceActions.PLAYBACK_STATE) return
            isPlaying = intent.getBooleanExtra(ServiceActions.EXTRA_IS_PLAYING, false)
            currentIndex = intent.getIntExtra(ServiceActions.EXTRA_INDEX, currentIndex)
            durationMs = intent.getLongExtra(ServiceActions.EXTRA_DURATION, 0L)
            val position = intent.getLongExtra(ServiceActions.EXTRA_POSITION, 0L)
            val title = intent.getStringExtra(ServiceActions.EXTRA_TITLE).orEmpty()
            shuffleEnabled = intent.getBooleanExtra(ServiceActions.EXTRA_SHUFFLE, shuffleEnabled)
            repeatMode = intent.getIntExtra(ServiceActions.EXTRA_REPEAT, repeatMode)
            updatePlayerUi(title, position, durationMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceSpinner = findViewById(R.id.deviceSpinner)
        playlistContainer = findViewById(R.id.playlistContainer)
        statusText = findViewById(R.id.statusText)
        currentSongText = findViewById(R.id.currentSongText)
        currentArtistText = findViewById(R.id.currentArtistText)
        elapsedText = findViewById(R.id.elapsedText)
        durationText = findViewById(R.id.durationText)
        songSeekBar = findViewById(R.id.songSeekBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        repeatButton = findViewById(R.id.repeatButton)
        pairedDevices = emptyList()
        shuffleEnabled = prefs.getBoolean(AppPrefs.KEY_SHUFFLE, false)
        repeatMode = prefs.getInt(AppPrefs.KEY_REPEAT_MODE, 0)

        findViewById<Button>(R.id.permissionsButton).setOnClickListener { requestRequiredPermissions() }
        findViewById<Button>(R.id.refreshDevicesButton).setOnClickListener { refreshPairedDevices() }
        findViewById<Button>(R.id.pickSongsButton).setOnClickListener { pickSongs() }
        findViewById<Button>(R.id.clearPlaylistButton).setOnClickListener { clearPlaylist() }
        findViewById<Button>(R.id.startButton).setOnClickListener { startMonitoring() }
        findViewById<Button>(R.id.playNowButton).setOnClickListener { playNow() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopMonitoring() }
        findViewById<Button>(R.id.previousButton).setOnClickListener { sendPlayerAction(ServiceActions.PREVIOUS) }
        findViewById<Button>(R.id.nextButton).setOnClickListener { sendPlayerAction(ServiceActions.NEXT) }
        playPauseButton.setOnClickListener {
            sendPlayerAction(if (isPlaying) ServiceActions.PAUSE else ServiceActions.RESUME)
        }
        shuffleButton.setOnClickListener {
            shuffleEnabled = !shuffleEnabled
            prefs.edit().putBoolean(AppPrefs.KEY_SHUFFLE, shuffleEnabled).apply()
            sendPlayerAction(ServiceActions.SET_SHUFFLE)
            updateModeButtons()
        }
        repeatButton.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3
            prefs.edit().putInt(AppPrefs.KEY_REPEAT_MODE, repeatMode).apply()
            sendPlayerAction(ServiceActions.SET_REPEAT)
            updateModeButtons()
        }
        songSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { seekInProgress = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && durationMs > 0) elapsedText.text = formatTime(durationMs * progress / seekBar.max)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekInProgress = false
                if (durationMs > 0) {
                    val position = durationMs * seekBar.progress / seekBar.max
                    startCarService(ServiceActions.SEEK, mapOf(ServiceActions.EXTRA_POSITION to position))
                }
            }
        })

        requestRequiredPermissions()
        renderPlaylist()
        updateModeButtons()
        showSavedStatus()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ServiceActions.PLAYBACK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playbackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(playbackReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(playbackReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (hasBluetoothPermission()) {
            refreshPairedDevices()
            requestBluetoothEnableIfNeeded()
        }
        showSavedStatus()
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.BLUETOOTH_CONNECT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        else refreshPairedDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasBluetoothPermission()) {
                refreshPairedDevices()
                requestBluetoothEnableIfNeeded()
            } else {
                statusText.text = "Status: Allow Nearby devices permission to select your car."
            }
        }
    }

    private fun requestBluetoothEnableIfNeeded() {
        if (enablePromptRequested || !hasBluetoothPermission()) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        try {
            if (!adapter.isEnabled) {
                enablePromptRequested = true
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
            }
        } catch (_: SecurityException) {
            statusText.text = "Status: Allow Nearby devices permission to use Bluetooth."
        } catch (_: ActivityNotFoundException) {
            statusText.text = "Status: Turn on Bluetooth in Android Settings."
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun refreshPairedDevices() {
        if (!hasBluetoothPermission()) {
            statusText.text = "Status: Grant Nearby devices permission."
            return
        }
        try {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null) {
                showDeviceList(emptyList(), "This phone does not support Bluetooth.")
                return
            }
            if (!adapter.isEnabled) {
                showDeviceList(emptyList(), "Bluetooth is off. Approve the system request to turn it on.")
                return
            }
            pairedDevices = adapter.bondedDevices.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
            showDeviceList(pairedDevices, null)
        } catch (_: SecurityException) {
            showDeviceList(emptyList(), "Could not read paired devices. Check Nearby devices permission.")
        }
    }

    private fun showDeviceList(devices: List<BluetoothDevice>, emptyMessage: String?) {
        val labels = devices.map { device ->
            val name = try { device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth device" }
            catch (_: SecurityException) { "Bluetooth device" }
            "$name  •  ${device.address.takeLast(5)}"
        }.ifEmpty { listOf(emptyMessage ?: "Pair your car in Android Settings first") }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedAddress = prefs.getString(AppPrefs.KEY_CAR_ADDRESS, null)
        val savedIndex = devices.indexOfFirst { it.address == savedAddress }
        if (savedIndex >= 0) deviceSpinner.setSelection(savedIndex)
        if (devices.isNotEmpty()) statusText.text = "Status: Choose the paired car that should start playback."
        else if (emptyMessage != null) statusText.text = "Status: $emptyMessage"
    }

    private fun pickSongs() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(picker, REQUEST_SONGS)
    }

    @Deprecated("Uses the system document picker for Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                refreshPairedDevices()
                toast("Bluetooth is on.")
            } else statusText.text = "Status: Approve the Android request to turn on Bluetooth."
            return
        }
        if (requestCode != REQUEST_SONGS || resultCode != RESULT_OK || data == null) return

        val selected = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (index in 0 until clip.itemCount) selected += clip.getItemAt(index).uri }
        if (selected.isEmpty()) data.data?.let(selected::add)
        if (selected.isEmpty()) return

        val readGrant = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        selected.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, readGrant) } catch (_: SecurityException) { }
        }
        val updated = (selectedUris() + selected).distinctBy(Uri::toString)
        prefs.edit().putString(AppPrefs.KEY_SONG_URIS, updated.joinToString("\n"))
            .putString(AppPrefs.KEY_SONG_TITLES, updated.map(::displayName).joinToString("\n")).apply()
        renderPlaylist()
        if (prefs.getBoolean(AppPrefs.KEY_MONITORING, false)) sendPlayerAction(ServiceActions.UPDATE_QUEUE)
    }

    private fun selectedUris(): List<Uri> = prefs.getString(AppPrefs.KEY_SONG_URIS, "")
        .orEmpty().split('\n').filter { it.isNotBlank() }.map(Uri::parse)

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "Audio file"
    } catch (_: Exception) { uri.lastPathSegment ?: "Audio file" }

    private fun renderPlaylist() {
        renderedCurrentIndex = currentIndex
        val uris = selectedUris()
        playlistContainer.removeAllViews()
        if (uris.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No songs in the playlist yet."
                setTextColor(0xFFA9B7C9.toInt())
                setPadding(8, 18, 8, 18)
            }
            playlistContainer.addView(empty)
            return
        }
        uris.forEachIndexed { index, uri ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(6, 4, 0, 4)
            }
            val title = TextView(this).apply {
                text = "${index + 1}.  ${displayName(uri)}"
                setTextColor(if (index == currentIndex) 0xFF54B9FF.toInt() else 0xFFF2F5FA.toInt())
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val up = Button(this).apply {
                text = "↑"
                minWidth = 40
                isEnabled = index > 0
                setOnClickListener { moveSong(index, index - 1) }
            }
            val down = Button(this).apply {
                text = "↓"
                minWidth = 40
                isEnabled = index < uris.lastIndex
                setOnClickListener { moveSong(index, index + 1) }
            }
            val remove = Button(this).apply {
                text = "×"
                minWidth = 40
                setOnClickListener { removeSong(index) }
            }
            row.addView(title)
            row.addView(up)
            row.addView(down)
            row.addView(remove)
            row.setOnClickListener { sendPlayerAction(ServiceActions.SELECT_TRACK, mapOf(ServiceActions.EXTRA_INDEX to index)) }
            playlistContainer.addView(row)
            if (index < uris.lastIndex) {
                val divider = View(this).apply { setBackgroundColor(0xFF2A3544.toInt()) }
                playlistContainer.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
    }

    private fun removeSong(index: Int) {
        val updated = selectedUris().toMutableList().also { if (index in it.indices) it.removeAt(index) }
        prefs.edit().putString(AppPrefs.KEY_SONG_URIS, updated.joinToString("\n"))
            .putString(AppPrefs.KEY_SONG_TITLES, updated.map(::displayName).joinToString("\n")).apply()
        renderPlaylist()
        if (prefs.getBoolean(AppPrefs.KEY_MONITORING, false)) sendPlayerAction(ServiceActions.UPDATE_QUEUE)
    }

    private fun moveSong(from: Int, to: Int) {
        val updated = selectedUris().toMutableList()
        if (from !in updated.indices || to !in updated.indices) return
        val item = updated.removeAt(from)
        updated.add(to, item)
        prefs.edit().putString(AppPrefs.KEY_SONG_URIS, updated.joinToString("\n"))
            .putString(AppPrefs.KEY_SONG_TITLES, updated.map(::displayName).joinToString("\n")).apply()
        renderPlaylist()
        if (prefs.getBoolean(AppPrefs.KEY_MONITORING, false)) sendPlayerAction(ServiceActions.UPDATE_QUEUE)
    }

    private fun clearPlaylist() {
        prefs.edit().putString(AppPrefs.KEY_SONG_URIS, "").putString(AppPrefs.KEY_SONG_TITLES, "").apply()
        renderPlaylist()
        currentSongText.text = "Choose songs to build your playlist"
        if (prefs.getBoolean(AppPrefs.KEY_MONITORING, false)) sendPlayerAction(ServiceActions.UPDATE_QUEUE)
    }

    private fun startMonitoring() {
        if (!hasBluetoothPermission()) { requestRequiredPermissions(); return }
        val device = pairedDevices.getOrNull(deviceSpinner.selectedItemPosition)
        if (device == null) { toast("Pair your car first, then refresh and select it."); return }
        if (selectedUris().isEmpty()) { toast("Add at least one song to the playlist."); return }
        try {
            val name = device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth car"
            prefs.edit().putString(AppPrefs.KEY_CAR_ADDRESS, device.address)
                .putString(AppPrefs.KEY_CAR_NAME, name).putBoolean(AppPrefs.KEY_MONITORING, true).apply()
            startCarService(ServiceActions.START)
            statusText.text = "Status: Waiting for $name. Reconnect the car if it is already connected."
        } catch (_: SecurityException) { toast("Allow Nearby devices permission to access the car.") }
    }

    private fun playNow() {
        if (selectedUris().isEmpty()) { toast("Add at least one song first."); return }
        if (!hasBluetoothPermission()) { requestRequiredPermissions(); return }
        val device = pairedDevices.getOrNull(deviceSpinner.selectedItemPosition)
        if (device == null) { toast("Choose your paired car first."); return }
        val name = try { device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth car" }
        catch (_: SecurityException) { "Bluetooth car" }
        prefs.edit().putString(AppPrefs.KEY_CAR_ADDRESS, device.address)
            .putString(AppPrefs.KEY_CAR_NAME, name).putBoolean(AppPrefs.KEY_MONITORING, true).apply()
        startCarService(ServiceActions.PLAY_NOW)
        statusText.text = "Status: Playing playlist."
    }

    private fun stopMonitoring() {
        prefs.edit().putBoolean(AppPrefs.KEY_MONITORING, false).apply()
        stopService(Intent(this, CarMusicService::class.java))
        statusText.text = "Status: Stopped"
        isPlaying = false
        updateModeButtons()
    }

    private fun sendPlayerAction(action: String, extras: Map<String, Any> = emptyMap()) {
        startCarService(action, extras)
    }

    private fun startCarService(action: String, extras: Map<String, Any> = emptyMap()) {
        val intent = Intent(this, CarMusicService::class.java).setAction(action)
        extras.forEach { (key, value) ->
            when (value) {
                is Long -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun updatePlayerUi(title: String, position: Long, duration: Long) {
        if (title.isNotBlank()) currentSongText.text = title
        currentArtistText.text = prefs.getString(AppPrefs.KEY_CAR_NAME, "Peugeot Car Music") ?: "Peugeot Car Music"
        elapsedText.text = formatTime(position)
        durationText.text = formatTime(duration)
        if (!seekInProgress) songSeekBar.progress = if (duration > 0) ((position * songSeekBar.max / duration).toInt()).coerceIn(0, songSeekBar.max) else 0
        playPauseButton.text = if (isPlaying) "Pause" else "Play"
        updateModeButtons()
        if (currentIndex != renderedCurrentIndex) renderPlaylist()
    }

    private fun updateModeButtons() {
        if (::shuffleButton.isInitialized) shuffleButton.text = if (shuffleEnabled) "Shuffle: On" else "Shuffle: Off"
        if (::repeatButton.isInitialized) repeatButton.text = "Repeat: " + when (repeatMode) {
            1 -> "All"
            2 -> "One"
            else -> "Off"
        }
        if (::playPauseButton.isInitialized) playPauseButton.text = if (isPlaying) "Pause" else "Play"
    }

    private fun formatTime(milliseconds: Long): String {
        if (milliseconds <= 0L) return "0:00"
        val totalSeconds = milliseconds / 1000
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    private fun showSavedStatus() {
        if (prefs.getBoolean(AppPrefs.KEY_MONITORING, false)) {
            statusText.text = "Status: Monitoring ${prefs.getString(AppPrefs.KEY_CAR_NAME, "selected car")}."
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_SONGS = 101
        private const val REQUEST_ENABLE_BLUETOOTH = 102
    }
}
