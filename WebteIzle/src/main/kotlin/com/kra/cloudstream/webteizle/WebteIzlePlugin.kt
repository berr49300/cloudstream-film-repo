package com.kra.cloudstream.webteizle

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class WebteIzlePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WebteIzle())
    }
}
