package com.jijilabsdigital.swell

/** Invite links: https://jijilabsdigital.com/swell/i#<code>. The code stays in the fragment, so a web server never sees it. */
object Links {
    const val HOST = "jijilabsdigital.com"
    const val SITE = "https://$HOST/swell"

    fun invite(code: String): String = "$SITE/i#" + code.trim().removePrefix("swell:").let { "swell:$it" }

    /** The invite code in an https link, with or without the "swell:" prefix; null if the link has none. */
    fun codeFrom(url: String): String? {
        val frag = url.substringAfter('#', "").trim()
        if (frag.isEmpty() || !Regex("(swell:)?[A-Za-z0-9_-]{20,}").matches(frag)) return null
        return "swell:" + frag.removePrefix("swell:")
    }
}
