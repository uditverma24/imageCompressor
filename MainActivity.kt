package com.imagecompressor.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.imagecompressor.app.ui.HomeScreen
import com.imagecompressor.app.ui.ImageCompressorTheme
import com.imagecompressor.app.ui.PreviewDialog
import com.imagecompressor.app.ui.ResultsScreen
import com.imagecompressor.app.vm.MainViewModel
import com.imagecompressor.app.vm.Screen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImageCompressorTheme {
                App(vm)
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    BackHandler(enabled = state.screen == Screen.RESULTS) { vm.backToHome() }

    // Android 8-9 needs WRITE_EXTERNAL_STORAGE to write into Pictures/ImageCompressor.
    // On Android 10+ MediaStore needs no permission at all.
    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingSave
        pendingSave = null
        if (granted) action?.invoke()
        else vm.post("Storage permission is needed to save images on this Android version.")
    }
    val guarded: (() -> Unit) -> Unit = { action ->
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSave = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else action()
    }

    Crossfade(
        targetState = state.screen,
        animationSpec = tween(250),
        label = "screen"
    ) { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(vm, state, snackbar)
            Screen.RESULTS -> ResultsScreen(
                vm = vm,
                state = state,
                snackbar = snackbar,
                onSaveOne = { id -> guarded { vm.saveOne(id) } },
                onSaveAll = { guarded { vm.saveAll() } }
            )
        }
    }

    state.previewFile?.let { PreviewDialog(it, onDismiss = vm::closePreview) }
}
