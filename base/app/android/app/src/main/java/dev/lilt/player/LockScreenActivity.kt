package dev.lilt.player

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lilt.player.ui.LiltTheme
import dev.lilt.player.ui.LockScreenScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen player shown over the system keyguard.
 *
 * Swiping up only leaves this activity, revealing the system PIN/pattern
 * prompt underneath. It never dismisses or bypasses the secure keyguard.
 */
class LockScreenActivity : ComponentActivity() {
    private var preview = false
    private var sawLocked = false
    private var userPresentRegistered = false
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        val prefs = getSharedPreferences("lilt_settings", MODE_PRIVATE)
        val enabled = prefs.getBoolean("lock_screen_player", true)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!preview && (!enabled || keyguard?.isKeyguardLocked != true)) {
            finish()
            return
        }
        sawLocked = keyguard?.isKeyguardLocked == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val vm: LockScreenViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val lyrics by vm.lyrics.collectAsStateWithLifecycle()
            val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
            val accent by vm.accent.collectAsStateWithLifecycle()
            val language by vm.language.collectAsStateWithLifecycle()
            LiltTheme(theme = "dark", accentId = accent, language = language) {
                LockScreenScreen(
                    state = state,
                    lyrics = lyrics,
                    isFavorite = state.mediaId in favoriteIds,
                    onPlayPause = vm::togglePlayPause,
                    onPrevious = vm::previous,
                    onNext = vm::next,
                    onSeek = vm::seekTo,
                    onCycleRepeat = vm::cycleRepeat,
                    onToggleFavorite = vm::toggleFavorite,
                    onUnlock = { finish() },
                )
            }
        }
        ContextCompat.registerReceiver(
            this,
            userPresentReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        userPresentRegistered = true
        lifecycleScope.launch {
            // Some unlock paths (fingerprint, face, trusted device) do not
            // always resume this activity. Poll the keyguard as a safety net.
            while (isActive) {
                delay(1_000L)
                val locked = keyguard?.isKeyguardLocked == true
                if (locked) sawLocked = true
                if (sawLocked && !locked) {
                    finish()
                    break
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!preview && getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
    }

    override fun onDestroy() {
        if (userPresentRegistered) {
            runCatching { unregisterReceiver(userPresentReceiver) }
            userPresentRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PREVIEW = "dev.lilt.player.extra.PREVIEW"
    }
}
