package com.kra.cloudstream.selcukflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SelcukFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SelcukFlix())
        registerExtractorAPI(SelcukFlixContentXExtractor())
    }
}
