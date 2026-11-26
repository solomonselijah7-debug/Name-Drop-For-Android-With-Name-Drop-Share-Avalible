package com.example

import android.app.Application
import com.example.ai.DailyUpdateEngine
import com.example.audio.ShareSoundPlayer
import com.example.ble.BluetoothLeManager
import com.example.ble.IncomingAlertManager
import com.example.data.AppPreferencesRepository
import com.example.data.DebugLogger

class NameDropApp : Application() {

    companion object {
        lateinit var instance: NameDropApp
            private set
        lateinit var prefsRepository: AppPreferencesRepository
            private set
        lateinit var bleManager: BluetoothLeManager
            private set
        lateinit var soundPlayer: ShareSoundPlayer
            private set
        lateinit var dailyUpdateEngine: DailyUpdateEngine
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefsRepository = AppPreferencesRepository(this)
        bleManager = BluetoothLeManager(this)
        soundPlayer = ShareSoundPlayer(this)
        dailyUpdateEngine = DailyUpdateEngine()
        IncomingAlertManager.ensureChannels(this)
        DebugLogger.log("App", "Name Drop initialized (API ${android.os.Build.VERSION.SDK_INT})")
    }
}
