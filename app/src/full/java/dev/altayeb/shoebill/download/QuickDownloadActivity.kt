package dev.altayeb.shoebill.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.download.logic.DownloadLogic
import dev.altayeb.shoebill.ui.theme.BrandTheme

/**
 * "Download with Shoebill" in the share menu: the same options sheet as the Downloads tab, drawn over the app the link
 * came from (YouTube stays behind it). Start queues it and closes; the notification shows progress. Shoebill itself never opens.
 */
class QuickDownloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = DownloadLogic.firstUrl(intent?.getStringExtra(Intent.EXTRA_TEXT))
        if (url == null) { Toast.makeText(this, R.string.dl_bad_url, Toast.LENGTH_SHORT).show(); finish(); return }
        enableEdgeToEdge()
        setContent {
            BrandTheme {
                val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@QuickDownloadActivity, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                DownloadFeature.ConfigureSheet(url, close = { finish() }) {
                    Toast.makeText(this, R.string.dl_quick_started, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        /** Shown in or hidden from the share sheet. */
        fun setEnabled(c: Context, on: Boolean) = c.packageManager.setComponentEnabledSetting(ComponentName(c, QuickDownloadActivity::class.java),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        fun isEnabled(c: Context) = c.packageManager.getComponentEnabledSetting(ComponentName(c, QuickDownloadActivity::class.java)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}
