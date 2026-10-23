package io.github.julssvanderhaack.sonata

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.theme.SonataTheme
import io.github.julssvanderhaack.sonata.ui.AppRoot
import io.github.julssvanderhaack.sonata.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a rotation recreates the activity with the same intent: open the file only once
        if (savedInstanceState == null) handle(intent)
        setContent {
            val palette by vm.palette.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            // status/navigation bar icons follow the theme, not the system's dark mode
            LaunchedEffect(palette.dark) {
                val style = if (palette.dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            SonataTheme(palette, settings.dynamicColor) {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** "Abrir con Sonata" from a file manager or another app. */
    private fun handle(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        vm.openExternal(uri)
    }
}
