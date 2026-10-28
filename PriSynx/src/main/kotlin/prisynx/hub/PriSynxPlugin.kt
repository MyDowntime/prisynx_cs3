package prisynx.hub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import prisynx.providers.KissKHSettings

@CloudstreamPlugin
class PriSynxPlugin : Plugin() {
    override fun load(context: Context) {
        HubSettings.init(context)
        KissKHSettings.attach(context)
        registerMainAPI(PriSynxProvider())
        openSettings = { ctx -> HubSettingsDialog.show(ctx) }
    }
}
