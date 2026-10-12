package com.kra.cloudstream.hatypapaz

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HatyPapazPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HatyPapaz())
    }
}