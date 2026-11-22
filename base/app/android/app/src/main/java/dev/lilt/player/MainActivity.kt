package dev.lilt.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.lilt.player.ui.AlbumDetailScreen
import dev.lilt.player.ui.AboutScreen
import dev.lilt.player.ui.ArtistDetailScreen
import dev.lilt.player.ui.HomeScreen
import dev.lilt.player.ui.LiltTheme
import dev.lilt.player.ui.PlayerScreen
import dev.lilt.player.ui.PlaylistScreen
import dev.lilt.player.ui.QueuesScreen
import dev.lilt.player.ui.RulesScreen
import dev.lilt.player.ui.SearchScreen
import dev.lilt.player.ui.SettingsScreen
import dev.lilt.player.ui.t

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: LiltViewModel = viewModel()
            val theme by vm.theme.collectAsStateWithLifecycle()
            val accent by vm.accent.collectAsStateWithLifecycle()
            val language by vm.language.collectAsStateWithLifecycle()
            LiltTheme(theme = theme, accentId = accent, language = language) {
                LiltRoot(vm)
            }
        }
    }
}

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun LiltRoot(vm: LiltViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasPermission(context, audioPermission())) }
    val nav = rememberNavController()
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) vm.scan()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasPermission(context, audioPermission())
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(granted) {
        if (granted) {
            vm.scan()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (!granted) {
        PermissionScreen(
            onRequest = { audioLauncher.launch(audioPermission()) },
            onSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            },
        )
        return
    }

    LiltNavHost(vm = vm, nav = nav)
}

@Composable
private fun LiltNavHost(vm: LiltViewModel, nav: NavHostController) {
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onOpenPlayer = { nav.navigate("player") },
                onOpenQueues = { nav.navigate("queues") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenSearch = { nav.navigate("search") },
                onOpenPlaylist = { nav.navigate("playlist/$it") },
                onOpenAlbum = { name, albumId -> nav.navigate("album/${Uri.encode(name)}/$albumId") },
                onOpenArtist = { nav.navigate("artist/${Uri.encode(it)}") },
            )
        }
        composable("player") {
            PlayerScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenQueues = { nav.navigate("queues") },
                onOpenEqualizer = { nav.navigate("equalizer") },
            )
        }
        composable("queues") {
            QueuesScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("playlist/{id}") { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
            PlaylistScreen(
                vm = vm,
                playlistId = id,
                onBack = { nav.popBackStack() },
                onOpenPlayer = { nav.navigate("player") },
            )
        }
        composable("album/{name}/{albumId}") { entry ->
            val name = entry.arguments?.getString("name")?.let(Uri::decode) ?: return@composable
            val albumId = entry.arguments?.getString("albumId")?.toLongOrNull() ?: -1L
            AlbumDetailScreen(
                vm = vm,
                albumName = name,
                albumId = albumId,
                onBack = { nav.popBackStack() },
                onOpenPlayer = { nav.navigate("player") },
            )
        }
        composable("artist/{name}") { entry ->
            val name = entry.arguments?.getString("name")?.let(Uri::decode) ?: return@composable
            ArtistDetailScreen(
                vm = vm,
                artistName = name,
                onBack = { nav.popBackStack() },
                onOpenPlayer = { nav.navigate("player") },
            )
        }
        composable("settings") {
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenRules = { nav.navigate("rules") },
                onOpenAbout = { nav.navigate("about") },
                onOpenEqualizer = { nav.navigate("equalizer") },
            )
        }
        composable("equalizer") {
            dev.lilt.player.ui.EqualizerScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("rules") {
            RulesScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("about") {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable("search") {
            SearchScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenPlayer = { nav.navigate("player") },
                onOpenPlaylist = { nav.navigate("playlist/$it") },
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(t("Your music stays here", "你的音乐留在这里"), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            t(
                "Lilt needs permission to read audio files on this phone. It scans only local files, keeps everything on the device and never uses the network.",
                "Lilt 需要读取手机上的音频文件。它只扫描本地文件，所有数据留在设备上，从不联网。",
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest, modifier = Modifier.clip(RoundedCornerShape(22.dp))) {
            Text(t("Allow audio access", "允许访问音频"))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(t("Open app settings", "打开应用设置"))
        }
    }
}
