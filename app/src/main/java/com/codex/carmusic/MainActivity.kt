package com.codex.carmusic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.view.DragEvent
import android.view.MotionEvent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
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
        findViewById<Button>(R.id.backgroundSetupButton).setOnClickListener { openBackgroundSetup() }
        findViewById<Button>(R.id.logButton).setOnClickListener { showEventLog() }
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
        if (intent.getBooleanExtra(ServiceActions.EXTRA_AUTOSTART, false) && prefs.getBoolean(AppPrefs.KEY_MONITORING, false)) {
            startCarService(ServiceActions.START)
        }
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
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58))
                setPadding(0, dp(2), 0, dp(2))
            }
            val deleteButton = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
                contentDescription = "Delete ${displayName(uri)}"
                visibility = View.INVISIBLE
                layoutParams = FrameLayout.LayoutParams(dp(58), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
                setOnClickListener { removeSong(index) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                tag = index
                isClickable = true
                isLongClickable = true
                elevation = dp(2).toFloat()
                setBackgroundResource(R.drawable.playlist_row_background)
            }
            val title = TextView(this).apply {
                text = "${index + 1}.  ${displayName(uri)}"
                setTextColor(if (index == currentIndex) 0xFF72C7FF.toInt() else 0xFFF2F5FA.toInt())
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(title)
            row.setOnClickListener { sendPlayerAction(ServiceActions.SELECT_TRACK, mapOf(ServiceActions.EXTRA_INDEX to index)) }
            row.setOnLongClickListener {
                val clip = ClipData.newPlainText("playlist-index", index.toString())
                row.startDragAndDrop(clip, View.DragShadowBuilder(row), index, 0)
            }
            row.setOnDragListener { view, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DROP -> {
                        val from = event.localState as? Int ?: -1
                        val to = view.tag as? Int ?: -1
                        if (from >= 0 && to >= 0 && from != to) moveSong(from, to)
                        true
                    }
                    else -> true
                }
            }
            var downX = 0f
            var initialTranslation = 0f
            var swiping = false
            row.setOnTouchListener { _, event ->
                val revealWidth = dp(58).toFloat()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        initialTranslation = row.translationX
                        swiping = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        if (kotlin.math.abs(dx) > dp(10)) swiping = true
                        if (swiping) {
                            row.translationX = (initialTranslation + dx).coerceIn(-revealWidth, 0f)
                            deleteButton.visibility = if (row.translationX < 0f) View.VISIBLE else View.INVISIBLE
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!swiping) false else {
                            val dx = event.rawX - downX
                            val reveal = if (dx < -dp(24)) -revealWidth else 0f
                            row.animate().translationX(reveal).setDuration(140).start()
                            deleteButton.visibility = if (reveal < 0f) View.VISIBLE else View.INVISIBLE
                            true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (swiping) {
                            val reveal = if (row.translationX < -revealWidth / 2) -revealWidth else 0f
                            row.animate().translationX(reveal).setDuration(140).start()
                            deleteButton.visibility = if (reveal < 0f) View.VISIBLE else View.INVISIBLE
                            true
                        } else false
                    }
                    else -> false
                }
            }
            frame.addView(deleteButton)
            frame.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            playlistContainer.addView(frame)
            if (index < uris.lastIndex) {
                val divider = View(this).apply { setBackgroundColor(0xFF2A3544.toInt()) }
                playlistContainer.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
            EventLog.add(this, "Monitoring started for $name")
            statusText.text = "Status: Monitoring $name. Music starts as soon as the car connects."
            if (!isBatteryUnrestricted() && !prefs.getBoolean(AppPrefs.KEY_BATTERY_PROMPTED, false)) {
                prefs.edit().putBoolean(AppPrefs.KEY_BATTERY_PROMPTED, true).apply()
                requestBatteryExemption()
            }
        } catch (_: SecurityException) { toast("Allow Nearby devices permission to access the car.") }
    }

    private fun isBatteryUnrestricted(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        if (isBatteryUnrestricted()) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            toast("Open Android Settings > Apps > Peugeot Car Music.")
        }
    }

    private fun openAutoLaunchSettings() {
        val candidates = listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.battery", "com.oplus.startupapp.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        )
        for (component in candidates) {
            try {
                startActivity(Intent().setComponent(component))
                return
            } catch (_: Exception) { }
        }
        toast("Enable Auto-launch for this app in the settings page that opens.")
        openAppDetails()
    }

    private fun openBackgroundSetup() {
        val batteryLine = if (isBatteryUnrestricted()) "Battery: unrestricted (OK)" else "Battery: still restricted"
        AlertDialog.Builder(this)
            .setTitle("Keep the app alive")
            .setMessage(
                "$batteryLine\n\nOn OPPO / ColorOS also:\n" +
                    "1. Allow Auto-launch (Auto startup) for this app.\n" +
                    "2. Set battery usage to Allow background activity.\n" +
                    "3. Lock the app in the recent apps list."
            )
            .setPositiveButton("Battery") { _, _ -> requestBatteryExemption() }
            .setNeutralButton("Auto-launch") { _, _ -> openAutoLaunchSettings() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEventLog() {
        val text = EventLog.read(this).ifBlank { "No events yet." }
        AlertDialog.Builder(this)
            .setTitle("Event log (newest first)")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear") { _, _ -> EventLog.clear(this) }
            .show()
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
