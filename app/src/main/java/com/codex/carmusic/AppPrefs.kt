package com.codex.carmusic

internal object AppPrefs {
    const val FILE = "car_music_settings"
    const val KEY_CAR_ADDRESS = "car_address"
    const val KEY_CAR_NAME = "car_name"
    const val KEY_SONG_URIS = "song_uris"
    const val KEY_SONG_TITLES = "song_titles"
    const val KEY_MONITORING = "monitoring"
    const val KEY_SHUFFLE = "shuffle"
    const val KEY_REPEAT_MODE = "repeat_mode"
    const val KEY_LAST_TRACK_URI = "last_track_uri"
    const val KEY_LAST_POSITION_MS = "last_position_ms"
}

internal object ServiceActions {
    const val START = "com.codex.carmusic.action.START"
    const val PLAY_NOW = "com.codex.carmusic.action.PLAY_NOW"
    const val PAUSE = "com.codex.carmusic.action.PAUSE"
    const val RESUME = "com.codex.carmusic.action.RESUME"
    const val NEXT = "com.codex.carmusic.action.NEXT"
    const val PREVIOUS = "com.codex.carmusic.action.PREVIOUS"
    const val SEEK = "com.codex.carmusic.action.SEEK"
    const val SELECT_TRACK = "com.codex.carmusic.action.SELECT_TRACK"
    const val SET_SHUFFLE = "com.codex.carmusic.action.SET_SHUFFLE"
    const val SET_REPEAT = "com.codex.carmusic.action.SET_REPEAT"
    const val UPDATE_QUEUE = "com.codex.carmusic.action.UPDATE_QUEUE"
    const val STOP = "com.codex.carmusic.action.STOP"

    const val PLAYBACK_STATE = "com.codex.carmusic.action.PLAYBACK_STATE"
    const val EXTRA_IS_PLAYING = "is_playing"
    const val EXTRA_INDEX = "track_index"
    const val EXTRA_TITLE = "track_title"
    const val EXTRA_POSITION = "position_ms"
    const val EXTRA_DURATION = "duration_ms"
    const val EXTRA_SHUFFLE = "shuffle_enabled"
    const val EXTRA_REPEAT = "repeat_mode"
}
