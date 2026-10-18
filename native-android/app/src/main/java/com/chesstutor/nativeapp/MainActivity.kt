package com.chesstutor.nativeapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal fun hideSystemBars(window: Window) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

class MainActivity : ComponentActivity() {
    private var sounds: SoundPool? = null
    private var moveSound = 0
    private var captureSound = 0
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        hideSystemBars(window)
        val model = (application as TutorApplication).model
        sounds = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        moveSound = sounds!!.load(this, R.raw.move, 1)
        captureSound = sounds!!.load(this, R.raw.capture, 1)
        model.onMoveSound = { capture -> sounds?.play(if (capture) captureSound else moveSound, .45f, .45f, 1, 0, 1f) }
        setContent { ChessTutorApp(model) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars(window)
    }
    override fun onDestroy() {
        (application as TutorApplication).model.onMoveSound = null
        sounds?.release()
        super.onDestroy()
    }
}
