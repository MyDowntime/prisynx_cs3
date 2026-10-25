package prisynx.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import prisynx.providers.core.DebridSettings
import prisynx.providers.core.DebridSettingsDialog

@CloudstreamPlugin
class CorsaroViolaPlugin : Plugin() {
    override fun load(context: Context) {
        DebridSettings.attach(context)
        openSettings = { ctx -> DebridSettingsDialog.show(ctx) }
        registerMainAPI(CorsaroViolaProvider())
    }
}
