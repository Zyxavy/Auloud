package app.auloud.player.library

/**
 * IN8: user-readable import failure messages (Slice 9).
 *
 * The pipeline shapes every error as file-plus-rule (same convention as
 * the rendered-book import errors the library screen already shows
 * verbatim). This mapper adds a friendly headline per error class but
 * ALWAYS keeps the original string in the details list, so support keeps
 * the rule token. In particular the same-book race loser
 * (`book folder already exists`) surfaces verbatim, never rewritten.
 *
 * API 24 safe: string ops only, JVM-testable.
 */
object ImportErrors {

    /**
     * Friendly headline for one pipeline error. Never blank; the raw
     * string stays available separately as the verbatim detail.
     */
    fun headlineForOne(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "book folder already exists" in lower ->
                "Another copy of this book just finished importing."
            "encryption.xml" in lower || "drm" in lower ->
                "This book has DRM protection and cannot be imported."
            "exceeds per-entry limit" in lower ||
                "exceeds limit" in lower ||
                "too large" in lower ->
                "This book is too large to import on this device."
            "no readable chapters" in lower ->
                "No readable chapters were found in this book."
            "not a valid zip" in lower ||
                "invalid xml" in lower ||
                "cannot read" in lower ||
                "missing file" in lower ||
                "not readable" in lower ||
                "opf" in lower ||
                "container" in lower ||
                "corrupt" in lower ->
                "This file looks corrupt or is not a readable EPUB."
            else ->
                "This book could not be imported."
        }
    }

    /**
     * Headline for a whole failure: the first error's headline, with a
     * count when more follow. [details] must always carry the full
     * verbatim list alongside.
     */
    fun headlineForAll(errors: List<String>): String {
        if (errors.isEmpty()) return "This book could not be imported."
        val first = headlineForOne(errors[0])
        return if (errors.size == 1) {
            first
        } else {
            "$first (plus ${errors.size - 1} more)"
        }
    }
}
