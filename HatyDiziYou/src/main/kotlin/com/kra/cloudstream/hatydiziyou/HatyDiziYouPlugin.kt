package com.kra.cloudstream.hatydiziyou

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HatyDiziYouPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HatyDiziYou())
    }
}