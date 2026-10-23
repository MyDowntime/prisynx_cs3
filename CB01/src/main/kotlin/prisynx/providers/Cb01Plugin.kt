package prisynx.providers

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Cb01Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Cb01Provider())
    }
}