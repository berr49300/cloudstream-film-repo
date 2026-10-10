/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.kra.cloudstream.filmmakinesi

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FilmMakinesiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FilmMakinesi())
        registerExtractorAPI(FilmMakinesiCloseLoadExtractor())
    }
}
