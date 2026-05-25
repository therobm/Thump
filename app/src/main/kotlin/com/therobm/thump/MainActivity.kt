package com.therobm.thump

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.therobm.thump.detail.AlbumDetailScreen
import com.therobm.thump.detail.ArtistDetailScreen
import com.therobm.thump.detail.PlaylistDetailScreen
import com.therobm.thump.home.HomeCarouselItem
import com.therobm.thump.home.HomeItemKind
import com.therobm.thump.home.HomeScreen
import com.therobm.thump.library.LibraryScreen
import com.therobm.thump.playback.MiniPlayer
import com.therobm.thump.playback.NowPlaying
import com.therobm.thump.playback.PlaybackController
import com.therobm.thump.playback.PlaybackQueueItem
import com.therobm.thump.search.SearchScreen
import com.therobm.thump.settings.SettingsScreen
import com.therobm.thump.subsonic.SubsonicAuthMode
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicCredentials
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThumpApp()
        }
    }
}

private const val PREFS_NAME = "thump_settings"
private const val PREFS_KEY_SERVER_URL = "server_url"
private const val PREFS_KEY_USERNAME = "username"
// TODO: encrypt the stored password (Android Keystore / EncryptedSharedPreferences) once the
// settings UI grows beyond a dev-only test harness.
private const val PREFS_KEY_PASSWORD = "password"
private const val PREFS_KEY_USE_TOKEN_AUTH = "use_token_auth"
private const val PREFS_KEY_PULSE_DETECTED_FOR_URL = "pulse_detected_for_url"
private const val PREFS_KEY_PULSE_DETECTED_VALUE = "pulse_detected_value"

private const val ROUTE_HOME = "home"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_SETTINGS = "settings"

private const val NAV_ARG_ALBUM_ID = "albumId"
private const val NAV_ARG_PLAYLIST_ID = "playlistId"
private const val NAV_ARG_ARTIST_ID = "artistId"
private const val ROUTE_ALBUM_PATTERN = "album/{$NAV_ARG_ALBUM_ID}"
private const val ROUTE_PLAYLIST_PATTERN = "playlist/{$NAV_ARG_PLAYLIST_ID}"
private const val ROUTE_ARTIST_PATTERN = "artist/{$NAV_ARG_ARTIST_ID}"

private const val MINI_PLAYER_ART_REQUEST_SIZE: Int = 150

private fun buildAlbumRoute(albumId: String): String {
    return "album/" + albumId
}

private fun buildPlaylistRoute(playlistId: String): String {
    return "playlist/" + playlistId
}

private fun buildArtistRoute(artistId: String): String {
    return "artist/" + artistId
}

@Composable
private fun ThumpApp() {
    ThumpTheme {
        val context = LocalContext.current
        val sharedPreferences: SharedPreferences = remember(context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        var serverUrl by remember { mutableStateOf(readStringOrBlank(sharedPreferences, PREFS_KEY_SERVER_URL)) }
        var username by remember { mutableStateOf(readStringOrBlank(sharedPreferences, PREFS_KEY_USERNAME)) }
        var password by remember { mutableStateOf(readStringOrBlank(sharedPreferences, PREFS_KEY_PASSWORD)) }
        var useTokenAuth by remember {
            mutableStateOf(sharedPreferences.getBoolean(PREFS_KEY_USE_TOKEN_AUTH, true))
        }
        var isPulseServer by remember {
            mutableStateOf(readCachedPulseDetection(sharedPreferences, serverUrl))
        }

        val httpClient: OkHttpClient = remember { buildHttpClient() }
        val jsonDecoder: Json = remember { buildJsonDecoder() }

        val subsonicClient: SubsonicClient? = remember(serverUrl, username, password, useTokenAuth) {
            buildSubsonicClient(httpClient, jsonDecoder, serverUrl, username, password, useTokenAuth)
        }

        val applicationContext = context.applicationContext
        val playbackController: PlaybackController = remember(applicationContext) {
            PlaybackController(applicationContext)
        }
        DisposableEffect(playbackController) {
            onDispose {
                playbackController.release()
            }
        }
        val nowPlaying: NowPlaying? by playbackController.nowPlaying.collectAsState()

        val navController = rememberNavController()

        val onPlayQueue: (List<PlaybackQueueItem>, Int) -> Unit = { items: List<PlaybackQueueItem>, startIndex: Int ->
            playbackController.playQueue(items, startIndex)
        }

        val onHomeItemTapped: (HomeCarouselItem) -> Unit = { tappedItem: HomeCarouselItem ->
            if (subsonicClient != null) {
                when (tappedItem.kind) {
                    HomeItemKind.Track -> {
                        val tappedCoverArtUrl: String?
                        val tappedCoverArtId = tappedItem.coverArtId
                        if (tappedCoverArtId == null) {
                            tappedCoverArtUrl = null
                        } else {
                            tappedCoverArtUrl = subsonicClient.buildCoverArtUrl(tappedCoverArtId, MINI_PLAYER_ART_REQUEST_SIZE)
                        }
                        val singleItem = PlaybackQueueItem(
                            trackId = tappedItem.id,
                            streamUrl = subsonicClient.buildStreamUrl(tappedItem.id),
                            title = tappedItem.title,
                            artist = tappedItem.subtitle,
                            coverArtUrl = tappedCoverArtUrl,
                        )
                        playbackController.playQueue(listOf(singleItem), 0)
                    }
                    HomeItemKind.Album -> {
                        navController.navigate(buildAlbumRoute(tappedItem.id))
                    }
                    HomeItemKind.Playlist -> {
                        navController.navigate(buildPlaylistRoute(tappedItem.id))
                    }
                    HomeItemKind.Artist -> {
                        navController.navigate(buildArtistRoute(tappedItem.id))
                    }
                }
            }
        }

        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute: String?
        val backStackSnapshot = currentBackStackEntry
        if (backStackSnapshot == null) {
            currentRoute = null
        } else {
            currentRoute = backStackSnapshot.destination.route
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(ThumpColors.Background),
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val nowPlayingSnapshot = nowPlaying
                    if (nowPlayingSnapshot != null) {
                        MiniPlayer(
                            nowPlaying = nowPlayingSnapshot,
                            onPlayPauseClicked = {
                                if (nowPlayingSnapshot.isPlaying) {
                                    playbackController.pause()
                                } else {
                                    playbackController.resume()
                                }
                            },
                        )
                    }
                    ThumpBottomBar(
                        currentRoute = currentRoute,
                        onTabSelected = { destinationRoute: String ->
                            // If the destination's already in the back stack (typical when the
                            // user has drilled into a detail screen from that tab), pop back to
                            // it rather than pushing a duplicate. Only navigate fresh when the
                            // destination has not been visited yet on this session.
                            val poppedExisting = navController.popBackStack(destinationRoute, inclusive = false)
                            if (!poppedExisting) {
                                navController.navigate(destinationRoute) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            },
            containerColor = ThumpColors.Background,
        ) { innerPadding: PaddingValues ->
            NavHost(
                navController = navController,
                startDestination = ROUTE_HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(ROUTE_HOME) {
                    if (subsonicClient == null) {
                        ConfigurePrompt(innerPadding)
                    } else {
                        HomeScreen(
                            subsonicClient = subsonicClient,
                            isPulseServer = isPulseServer,
                            contentPadding = innerPadding,
                            onItemTapped = onHomeItemTapped,
                            modifier = Modifier,
                        )
                    }
                }
                composable(ROUTE_LIBRARY) {
                    LibraryScreen(contentPadding = innerPadding, modifier = Modifier)
                }
                composable(ROUTE_SEARCH) {
                    SearchScreen(contentPadding = innerPadding, modifier = Modifier)
                }
                composable(
                    route = ROUTE_ALBUM_PATTERN,
                    arguments = listOf(navArgument(NAV_ARG_ALBUM_ID) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val argumentBundle = backStackEntry.arguments
                    val albumIdArgument: String?
                    if (argumentBundle == null) {
                        albumIdArgument = null
                    } else {
                        albumIdArgument = argumentBundle.getString(NAV_ARG_ALBUM_ID)
                    }
                    if (subsonicClient != null && albumIdArgument != null) {
                        AlbumDetailScreen(
                            albumId = albumIdArgument,
                            subsonicClient = subsonicClient,
                            onBackPressed = { navController.popBackStack() },
                            onPlayQueue = onPlayQueue,
                            contentPadding = innerPadding,
                            modifier = Modifier,
                        )
                    }
                }
                composable(
                    route = ROUTE_PLAYLIST_PATTERN,
                    arguments = listOf(navArgument(NAV_ARG_PLAYLIST_ID) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val argumentBundle = backStackEntry.arguments
                    val playlistIdArgument: String?
                    if (argumentBundle == null) {
                        playlistIdArgument = null
                    } else {
                        playlistIdArgument = argumentBundle.getString(NAV_ARG_PLAYLIST_ID)
                    }
                    if (subsonicClient != null && playlistIdArgument != null) {
                        PlaylistDetailScreen(
                            playlistId = playlistIdArgument,
                            subsonicClient = subsonicClient,
                            onBackPressed = { navController.popBackStack() },
                            onPlayQueue = onPlayQueue,
                            contentPadding = innerPadding,
                            modifier = Modifier,
                        )
                    }
                }
                composable(
                    route = ROUTE_ARTIST_PATTERN,
                    arguments = listOf(navArgument(NAV_ARG_ARTIST_ID) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val argumentBundle = backStackEntry.arguments
                    val artistIdArgument: String?
                    if (argumentBundle == null) {
                        artistIdArgument = null
                    } else {
                        artistIdArgument = argumentBundle.getString(NAV_ARG_ARTIST_ID)
                    }
                    if (subsonicClient != null && artistIdArgument != null) {
                        ArtistDetailScreen(
                            artistId = artistIdArgument,
                            subsonicClient = subsonicClient,
                            onBackPressed = { navController.popBackStack() },
                            onAlbumSelected = { selectedAlbumId: String ->
                                navController.navigate(buildAlbumRoute(selectedAlbumId))
                            },
                            contentPadding = innerPadding,
                            modifier = Modifier,
                        )
                    }
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        initialServerUrl = serverUrl,
                        initialUsername = username,
                        initialPassword = password,
                        initialUseTokenAuth = useTokenAuth,
                        httpClient = httpClient,
                        jsonDecoder = jsonDecoder,
                        contentPadding = innerPadding,
                        onCredentialsUpdated = { newServerUrl: String, newUsername: String, newPassword: String, newUseTokenAuth: Boolean, detectedIsPulse: Boolean ->
                            serverUrl = newServerUrl
                            username = newUsername
                            password = newPassword
                            useTokenAuth = newUseTokenAuth
                            isPulseServer = detectedIsPulse

                            val editor = sharedPreferences.edit()
                            editor.putString(PREFS_KEY_SERVER_URL, newServerUrl)
                            editor.putString(PREFS_KEY_USERNAME, newUsername)
                            editor.putString(PREFS_KEY_PASSWORD, newPassword)
                            editor.putBoolean(PREFS_KEY_USE_TOKEN_AUTH, newUseTokenAuth)
                            editor.putString(PREFS_KEY_PULSE_DETECTED_FOR_URL, newServerUrl)
                            editor.putBoolean(PREFS_KEY_PULSE_DETECTED_VALUE, detectedIsPulse)
                            editor.apply()
                        },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigurePrompt(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ThumpColors.Background)
            .padding(contentPadding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Open Settings to connect to your music server.",
            color = ThumpColors.TextSecondary,
        )
    }
}

@Composable
private fun ThumpBottomBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
) {
    NavigationBar(
        containerColor = ThumpColors.Surface,
        contentColor = ThumpColors.OnSurface,
    ) {
        val tabs = listOf(
            BottomNavTab(ROUTE_HOME, "Home", Icons.Filled.Home),
            BottomNavTab(ROUTE_LIBRARY, "Library", Icons.Filled.LibraryMusic),
            BottomNavTab(ROUTE_SEARCH, "Search", Icons.Filled.Search),
            BottomNavTab(ROUTE_SETTINGS, "Settings", Icons.Filled.Settings),
        )
        val tabCount = tabs.size
        for (tabIndex in 0 until tabCount) {
            val tab = tabs[tabIndex]
            val isSelected: Boolean = isTabSelected(currentRoute, tab.route)
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (!isSelected) {
                        onTabSelected(tab.route)
                    }
                },
                icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                label = { Text(text = tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ThumpColors.Accent,
                    selectedTextColor = ThumpColors.Accent,
                    unselectedIconColor = ThumpColors.TextSecondary,
                    unselectedTextColor = ThumpColors.TextSecondary,
                    indicatorColor = ThumpColors.SurfaceElevated,
                ),
            )
        }
    }
}

private data class BottomNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private fun isTabSelected(currentRoute: String?, tabRoute: String): Boolean {
    if (currentRoute == null) {
        return false
    }
    return currentRoute == tabRoute
}

private fun readStringOrBlank(sharedPreferences: SharedPreferences, key: String): String {
    val storedValue: String? = sharedPreferences.getString(key, null)
    if (storedValue == null) {
        return ""
    }
    return storedValue
}

private fun readCachedPulseDetection(sharedPreferences: SharedPreferences, currentServerUrl: String): Boolean {
    val cachedFor = sharedPreferences.getString(PREFS_KEY_PULSE_DETECTED_FOR_URL, null)
    if (cachedFor == null) {
        return false
    }
    if (cachedFor != currentServerUrl.trim()) {
        return false
    }
    return sharedPreferences.getBoolean(PREFS_KEY_PULSE_DETECTED_VALUE, false)
}

private fun buildHttpClient(): OkHttpClient {
    val loggingInterceptor = HttpLoggingInterceptor()
    loggingInterceptor.level = HttpLoggingInterceptor.Level.BASIC
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()
}

private fun buildJsonDecoder(): Json {
    return Json {
        ignoreUnknownKeys = true
    }
}

private fun buildSubsonicClient(
    httpClient: OkHttpClient,
    jsonDecoder: Json,
    serverUrl: String,
    username: String,
    password: String,
    useTokenAuth: Boolean,
): SubsonicClient? {
    if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
        return null
    }
    val authMode: SubsonicAuthMode
    if (useTokenAuth) {
        authMode = SubsonicAuthMode.Token
    } else {
        authMode = SubsonicAuthMode.Legacy
    }
    return SubsonicClient(
        okHttpClient = httpClient,
        jsonDecoder = jsonDecoder,
        credentials = SubsonicCredentials(
            serverUrl = serverUrl.trim(),
            username = username.trim(),
            password = password,
        ),
        authMode = authMode,
    )
}
