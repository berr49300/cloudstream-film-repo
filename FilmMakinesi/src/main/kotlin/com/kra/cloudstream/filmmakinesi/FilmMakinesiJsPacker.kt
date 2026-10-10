/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Small, non-evaluating decoder for the common Dean Edwards P.A.C.K.E.R.
 * wrapper. It reconstructs source text; it never executes the decoded JavaScript.
 */
package com.kra.cloudstream.filmmakinesi

internal object FilmMakinesiJsPacker {
    private val packedCall = Regex(
        """(?is)eval\s*\(\s*function\s*\(p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[rd]\s*\).*?\}\s*\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\s*\.split\(\s*'\|'\s*\)"""
    )
    private val wordToken = Regex("""\b\w+\b""")
    private val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun unpackAll(source: String, maxBlocks: Int = 8): List<String> {
        val output = mutableListOf<String>()
        for (match in packedCall.findAll(source).take(maxBlocks)) {
            val payload = decodeString(match.groupValues[1])
            val radix = match.groupValues[2].toIntOrNull() ?: continue
            val count = match.groupValues[3].toIntOrNull() ?: continue
            val words = decodeString(match.groupValues[4]).split('|')
            if (radix !in 2..62 || count !in 0..100_000 || words.size > 100_000) continue
            val limit = minOf(count, words.size)
            val dictionary = HashMap<String, String>(limit)
            for (index in 0 until limit) {
                val word = words[index]
                if (word.isNotEmpty()) dictionary[tokenFor(index, radix)] = word
            }
            val decoded = wordToken.replace(payload) { token ->
                dictionary[token.value] ?: token.value
            }
            if (decoded.isNotBlank() && decoded != payload) output.add(decoded)
        }
        return output
    }

    private fun tokenFor(value: Int, radix: Int): String {
        if (value == 0) return "0"
        var n = value
        val result = StringBuilder()
        while (n > 0) {
            result.append(digits[n % radix])
            n /= radix
        }
        return result.reverse().toString()
    }

    private fun decodeString(value: String): String = value
        .replace("\\\\'", "'")
        .replace("\\\\"", "\"")
        .replace("\\\\\\\\", "\\\\")
}
