package com.codex.carmusic

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build

@Suppress("DEPRECATION")
internal fun Intent.bluetoothDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
