package com.kra.cloudstream.hatyturkish123

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HatyTurkish123Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HatyTurkish123())
    }
}
