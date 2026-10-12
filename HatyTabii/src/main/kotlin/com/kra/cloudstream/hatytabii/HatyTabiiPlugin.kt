package com.kra.cloudstream.hatytabii

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HatyTabiiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HatyTabii())
    }
}