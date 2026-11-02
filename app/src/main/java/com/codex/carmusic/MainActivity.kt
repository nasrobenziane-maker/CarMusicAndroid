package com.codex.carmusic

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var deviceSpinner: Spinner
    private lateinit var songsText: TextView
    private lateinit var statusText: TextView
    private lateinit var pairedDevices: List<BluetoothDevice>
    private var enablePromptRequested = false
    private val prefs by lazy { getSharedPreferences(AppPrefs.FILE, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceSpinner = findViewById(R.id.deviceSpinner)
        songsText = findViewById(R.id.songsText)
        statusText = findViewById(R.id.statusText)
        pairedDevices = emptyList()

        findViewById<Button>(R.id.permissionsButton).setOnClickListener { requestRequiredPermissions() }
        findViewById<Button>(R.id.refreshDevicesButton).setOnClickListener { refreshPairedDevices() }
        findViewById<Button>(R.id.pickSongsButton).setOnClickListener { pickSongs() }
        findViewById<Button>(R.id.startButton).setOnClickListener { startMonitoring() }
        findViewById<Button>(R.id.playNowButton).setOnClickListener { playNow() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopMonitoring() }

        requestRequiredPermissions()
        showSelectedSongs()
        showSavedStatus()
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

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            refreshPairedDevices()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasBluetoothPermission()) {
                refreshPairedDevices()
                requestBluetoothEnableIfNeeded()
            }
            if (!hasBluetoothPermission()) {
                statusText.text = "الحالة: يلزم السماح بالوصول إلى الأجهزة القريبة لاختيار السيارة."
            }
        }
    }

    private fun requestBluetoothEnableIfNeeded() {
        if (enablePromptRequested || !hasBluetoothPermission()) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        try {
            if (!adapter.isEnabled) {
                enablePromptRequested = true
                startActivityForResult(
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    REQUEST_ENABLE_BLUETOOTH
                )
            }
        } catch (_: SecurityException) {
            statusText.text = "الحالة: اسمح بإذن الأجهزة القريبة لتشغيل Bluetooth."
        } catch (_: ActivityNotFoundException) {
            statusText.text = "الحالة: شغّل Bluetooth من إعدادات الهاتف."
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun refreshPairedDevices() {
        if (!hasBluetoothPermission()) {
            statusText.text = "الحالة: اضغط زر السماح لمنح إذن الأجهزة القريبة."
            return
        }

        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            if (adapter == null) {
                showDeviceList(emptyList(), "هذا الهاتف لا يحتوي على Bluetooth.")
                return
            }
            if (!adapter.isEnabled) {
                showDeviceList(emptyList(), "فعّل Bluetooth في الهاتف ثم حدّث الأجهزة.")
                return
            }

            pairedDevices = adapter.bondedDevices
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
            showDeviceList(pairedDevices, null)
        } catch (error: SecurityException) {
            showDeviceList(emptyList(), "تعذر قراءة الأجهزة. راجع إذن الأجهزة القريبة.")
        }
    }

    private fun showDeviceList(devices: List<BluetoothDevice>, emptyMessage: String?) {
        val labels = devices.map { device ->
            val name = try { device.name?.takeIf { it.isNotBlank() } ?: "جهاز Bluetooth" }
            catch (_: SecurityException) { "جهاز Bluetooth" }
            "$name  •  ${device.address.takeLast(5)}"
        }.ifEmpty { listOf(emptyMessage ?: "اقرن السيارة من إعدادات Bluetooth أولاً") }

        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val savedAddress = prefs.getString(AppPrefs.KEY_CAR_ADDRESS, null)
        val savedIndex = devices.indexOfFirst { it.address == savedAddress }
        if (savedIndex >= 0) deviceSpinner.setSelection(savedIndex)
        if (devices.isNotEmpty()) statusText.text = "الحالة: اختر السيارة المقترنة التي تريد تشغيل الموسيقى عند اتصالها."
        else if (emptyMessage != null) statusText.text = "الحالة: $emptyMessage"
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
                toast("تم تشغيل Bluetooth.")
            } else {
                statusText.text = "الحالة: وافق على طلب النظام لتشغيل Bluetooth."
            }
            return
        }
        if (requestCode != REQUEST_SONGS || resultCode != RESULT_OK || data == null) return

        val selected = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) selected += clip.getItemAt(index).uri
        }
        if (selected.isEmpty()) data.data?.let(selected::add)
        if (selected.isEmpty()) return

        val readGrant = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        selected.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, readGrant) }
            catch (_: SecurityException) { /* Some document providers offer only a temporary grant. */ }
        }
        prefs.edit().putString(AppPrefs.KEY_SONG_URIS, selected.joinToString("\n")).apply()
        showSelectedSongs()
    }

    private fun showSelectedSongs() {
        val uris = selectedUris()
        if (uris.isEmpty()) {
            songsText.text = "لم تختر أغاني بعد"
            return
        }
        val names = uris.map(::displayName)
        songsText.text = "الأغاني المختارة (${uris.size}):\n" + names.take(5).joinToString("\n") +
            if (names.size > 5) "\n… و${names.size - 5} أخرى" else ""
    }

    private fun selectedUris(): List<Uri> = prefs.getString(AppPrefs.KEY_SONG_URIS, "")
        .orEmpty().split('\n').filter { it.isNotBlank() }.map(Uri::parse)

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "ملف صوتي"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "ملف صوتي"
        }
    }

    private fun startMonitoring() {
        if (!hasBluetoothPermission()) {
            requestRequiredPermissions()
            return
        }
        val device = pairedDevices.getOrNull(deviceSpinner.selectedItemPosition)
        if (device == null) {
            toast("اقرن السيارة أولاً ثم حدّث الأجهزة واخترها.")
            return
        }
        if (selectedUris().isEmpty()) {
            toast("اختر أغنية واحدة على الأقل.")
            return
        }

        try {
            val name = device.name?.takeIf { it.isNotBlank() } ?: "سيارة Bluetooth"
            prefs.edit()
                .putString(AppPrefs.KEY_CAR_ADDRESS, device.address)
                .putString(AppPrefs.KEY_CAR_NAME, name)
                .putBoolean(AppPrefs.KEY_MONITORING, true)
                .apply()
            startCarService(ServiceActions.START)
            statusText.text = "الحالة: ننتظر اتصال «$name». أعد توصيل السيارة إذا كانت متصلة مسبقاً."
        } catch (_: SecurityException) {
            toast("تعذر الوصول إلى جهاز السيارة. اسمح بإذن الأجهزة القريبة.")
        }
    }

    private fun playNow() {
        if (selectedUris().isEmpty()) {
            toast("اختر أغاني أولاً.")
            return
        }
        if (!hasBluetoothPermission()) {
            requestRequiredPermissions()
            return
        }
        val device = pairedDevices.getOrNull(deviceSpinner.selectedItemPosition)
        if (device == null) {
            toast("اختر السيارة المقترنة أولاً.")
            return
        }
        val name = try { device.name?.takeIf { it.isNotBlank() } ?: "سيارة Bluetooth" }
        catch (_: SecurityException) { "سيارة Bluetooth" }
        prefs.edit()
            .putString(AppPrefs.KEY_CAR_ADDRESS, device.address)
            .putString(AppPrefs.KEY_CAR_NAME, name)
            .putBoolean(AppPrefs.KEY_MONITORING, true)
            .apply()
        startCarService(ServiceActions.PLAY_NOW)
        statusText.text = "الحالة: بدأ تشغيل قائمة الأغاني."
    }

    private fun startCarService(action: String) {
        val intent = Intent(this, CarMusicService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopMonitoring() {
        prefs.edit().putBoolean(AppPrefs.KEY_MONITORING, false).apply()
        stopService(Intent(this, CarMusicService::class.java))
        statusText.text = "الحالة: متوقف"
    }

    private fun showSavedStatus() {
        if (prefs.getBoolean(AppPrefs.KEY_MONITORING, false)) {
            val name = prefs.getString(AppPrefs.KEY_CAR_NAME, "السيارة")
            statusText.text = "الحالة: المراقبة مفعّلة لجهاز «$name»."
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_SONGS = 101
        private const val REQUEST_ENABLE_BLUETOOTH = 102
    }
}

