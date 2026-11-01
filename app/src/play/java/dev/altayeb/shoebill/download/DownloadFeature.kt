package dev.altayeb.shoebill.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow

/** The Play build has no downloader: Google Play does not allow one. See the full flavor. */
object DownloadFeature {
    const val ENABLED = false
    @Composable fun Tab(modifier: Modifier) = Unit
}

object SharedLink { val value = MutableStateFlow<String?>(null) }
