package site.anzz.childkiosk.util.filter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class FilterEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val jsonStr = intent?.getStringExtra("event_json") ?: return
        runCatching {
            val json = JSONObject(jsonStr)
            val event = FilterEvent.fromJson(json)
            FilterRepository.recordEvent(ctx, event)
        }
    }
}
