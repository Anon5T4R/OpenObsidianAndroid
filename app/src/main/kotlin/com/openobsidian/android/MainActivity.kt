package com.openobsidian.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.openobsidian.android.data.AppSettings
import com.openobsidian.android.data.AppTheme
import com.openobsidian.android.data.LocalAppSettings
import com.openobsidian.android.ui.screens.SettingsScreen
import com.openobsidian.android.ui.screens.VaultScreen
import com.openobsidian.android.ui.screens.WelcomeScreen
import com.openobsidian.android.ui.theme.OpenObsidianTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repo         by lazy { (application as OpenObsidianApp).vaultRepo }
    private val settingsRepo by lazy { (application as OpenObsidianApp).settingsRepo }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by settingsRepo.settings.collectAsState(initial = AppSettings())
            val scope    = rememberCoroutineScope()

            val isDark = when (settings.theme) {
                AppTheme.DARK   -> true
                AppTheme.LIGHT  -> false
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            var showSettings by remember { mutableStateOf(false) }

            CompositionLocalProvider(LocalAppSettings provides settings) {
                OpenObsidianTheme(darkTheme = isDark) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        if (showSettings) {
                            SettingsScreen(
                                settings         = settings,
                                onThemeChange    = { scope.launch { settingsRepo.setTheme(it) } },
                                onFontSizeChange = { scope.launch { settingsRepo.setPreviewFontSize(it) } },
                                onSortOrderChange = { scope.launch { settingsRepo.setSortOrder(it) } },
                                onClose          = { showSettings = false },
                            )
                        } else {
                            val current by repo.lastVault.collectAsState(initial = null)
                            when {
                                current.isNullOrBlank() ->
                                    WelcomeScreen(repo = repo)
                                else ->
                                    VaultScreen(
                                        vaultUri       = current!!,
                                        repo           = repo,
                                        onOpenSettings = { showSettings = true },
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}
