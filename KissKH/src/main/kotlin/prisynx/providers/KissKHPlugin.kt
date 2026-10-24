package prisynx.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KissKHPlugin : Plugin() {
    override fun load(context: Context) {
        KissKHSettings.attach(context)
        openSettings = { ctx -> KissKHSettingsDialog.show(ctx) }
        registerMainAPI(KissKHProvider())
    }
}
