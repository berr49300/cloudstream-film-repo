package com.kra.cloudstream.hatydizipal

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HatyDiziPalPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HatyDiziPal())
    }
}