package io.github.ccoodduu.aivoice

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.github.ccoodduu.aivoice.overlay.OverlayService
import io.github.ccoodduu.aivoice.ui.VoiceAssistantScreen
import io.github.ccoodduu.aivoice.ui.theme.AIVoiceAssistantTheme
import io.github.ccoodduu.aivoice.viewmodel.VoiceAssistantViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceAssistantViewModel by viewModels()
    private val app by lazy { application as AIVoiceApplication }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Permission denied - could show a message to the user
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Stop overlay if running - only one view at a time
        OverlayService.stop(this)

        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val isDeviceLocked = keyguardManager.isDeviceLocked

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        checkAudioPermission()

        setContent {
            AIVoiceAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VoiceAssistantScreen(
                        viewModel = viewModel,
                        isHalfScreen = false,
                        isDeviceLocked = isDeviceLocked,
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.setCurrentActivity(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        app.setCurrentActivity(null)
    }

    private fun checkAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
