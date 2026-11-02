package com.codex.carmusic

internal object AppPrefs {
    const val FILE = "car_music_settings"
    const val KEY_CAR_ADDRESS = "car_address"
    const val KEY_CAR_NAME = "car_name"
    const val KEY_SONG_URIS = "song_uris"
    const val KEY_MONITORING = "monitoring"
}

internal object ServiceActions {
    const val START = "com.codex.carmusic.action.START"
    const val PLAY_NOW = "com.codex.carmusic.action.PLAY_NOW"
    const val PAUSE = "com.codex.carmusic.action.PAUSE"
    const val RESUME = "com.codex.carmusic.action.RESUME"
    const val STOP = "com.codex.carmusic.action.STOP"
}
