package dev.altayeb.shoebill.music

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import dev.altayeb.shoebill.MainActivity

/** Quick Settings tile: tap to play or pause the music, even when the app is closed. */
class MusicTile : TileService() {
    override fun onStartListening() { qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() } }

    override fun onClick() {
        val key = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(this, androidx.media3.session.MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        sendBroadcast(key)
    }
}

class BluetoothResume : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: Intent) {
        if (!dev.altayeb.shoebill.settings.AppSettings.get(c).settings.value.resumeOnBluetooth) return
        c.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(c, androidx.media3.session.MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
    }
}
