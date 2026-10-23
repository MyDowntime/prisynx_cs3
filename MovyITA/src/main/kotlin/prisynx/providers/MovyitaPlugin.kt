package prisynx.providers

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovyitaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MovyitaProvider())
    }
}