package com.kra.cloudstream.fullhdfilmizlesene

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * CloudStream plugin entry point. The provider itself is registered here,
 * following the official CloudStream plugin-template architecture.
 */
@CloudstreamPlugin
class FullHDFilmizlesenePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FullHDFilmizlesene())
    }
}
