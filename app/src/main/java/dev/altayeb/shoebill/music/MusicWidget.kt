package dev.altayeb.shoebill.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.widget.RemoteViews
import dev.altayeb.shoebill.MainActivity
import dev.altayeb.shoebill.R

/** Home-screen widget: the song, previous / play-pause / next. The buttons are media keys, so a closed app starts again. */
class MusicWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = push(c, last)

    companion object {
        private var last: Pair<String, Boolean> = "" to false

        private fun key(c: Context, code: Int) = PendingIntent.getBroadcast(c, code,
            Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(c, androidx.media3.session.MediaButtonReceiver::class.java)).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Called by the music service whenever the song or play state changes. */
        fun update(c: Context, title: String, playing: Boolean) { last = title to playing; push(c, last) }

        private fun push(c: Context, s: Pair<String, Boolean>) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, MusicWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(c.packageName, R.layout.widget_music)
            v.setTextViewText(R.id.w_title, s.first.ifEmpty { c.getString(R.string.lib_tab_music) })
            v.setImageViewResource(R.id.w_play, if (s.second) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            v.setOnClickPendingIntent(R.id.w_prev, key(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            v.setOnClickPendingIntent(R.id.w_play, key(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            v.setOnClickPendingIntent(R.id.w_next, key(c, KeyEvent.KEYCODE_MEDIA_NEXT))
            v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(c, 0, Intent(Intent.ACTION_VIEW, Uri.parse("shoebill://music"), c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            m.updateAppWidget(ids, v)
        }
    }
}
