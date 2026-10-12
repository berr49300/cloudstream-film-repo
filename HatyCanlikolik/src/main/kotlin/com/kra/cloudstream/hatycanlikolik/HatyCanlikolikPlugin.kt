package com.kra.cloudstream.hatycanlikolik
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
@CloudstreamPlugin
class HatyCanlikolikPlugin : BasePlugin() {
    override fun load() { registerMainAPI(HatyCanlikolik()) }
}