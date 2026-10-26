package com.kra.cloudstream.selcukflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SelcukFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SelcukFlix())
        // SelcukFlixContentXExtractor is invoked explicitly for the observed
        // iframe.php?v= flow. Do not register it globally: its fixed mainUrl
        // would shadow CloudStream's built-in FourPichive/Pichive extractors.
    }
}
