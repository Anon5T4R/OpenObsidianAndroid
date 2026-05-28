package com.openobsidian.android

import android.app.Application
import com.openobsidian.android.data.SettingsRepository
import com.openobsidian.android.data.VaultRepository

class OpenObsidianApp : Application() {
    lateinit var vaultRepo: VaultRepository
        private set
    lateinit var settingsRepo: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        vaultRepo    = VaultRepository(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
    }
}
