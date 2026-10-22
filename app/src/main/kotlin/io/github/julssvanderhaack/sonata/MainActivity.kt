package io.github.julssvanderhaack.sonata

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
}
