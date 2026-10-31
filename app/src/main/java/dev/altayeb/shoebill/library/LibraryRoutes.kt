package dev.altayeb.shoebill.library

import android.net.Uri
import androidx.compose.runtime.Composable

// Entry points MainActivity calls. Bodies: HomeScreen.kt, FolderPage.kt (FolderBody), SearchPage.kt (SearchBody).

@Composable
fun LibraryHome(
    onPlay: (Uri, String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onVault: () -> Unit,
    onPlayQueue: (List<Pair<Uri, String>>, Int) -> Unit,
) = HomeScreen(onPlay, onOpenFolder, onSearch, onSettings, onVault, onPlayQueue)

@Composable
fun FolderScreen(folder: String, onBack: () -> Unit, onPlay: (Uri, String) -> Unit) =
    FolderBody(folder, onBack, onPlay)

@Composable
fun SearchScreen(onBack: () -> Unit, onPlay: (Uri, String) -> Unit) =
    SearchBody(onBack, onPlay)
