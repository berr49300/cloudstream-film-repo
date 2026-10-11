/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.kra.cloudstream.filmmakinesi

internal object FilmMakinesiSourceRules {
    fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
}
