package garden.appl.mitch

import junit.framework.TestCase

class UtilsTest : TestCase() {
    fun testIsVersionNewer() {
        assertTrue(Utils.isVersionNewer("Version v2.0.1", "Версия 2.0.0"))
        assertTrue(Utils.isVersionNewer("2.0.1", "2.0.0"))
        assertTrue(Utils.isVersionNewer("2.0.0.1", "2.0.0"))
        assertTrue(Utils.isVersionNewer("2.1.0", "2.0.0"))
        assertTrue(Utils.isVersionNewer("2.0.0", "1.0.9"))
        assertTrue(Utils.isVersionNewer("1.0.010", "1.0.0"))
        assertTrue(Utils.isVersionNewer("2.0", "1.0.15"))

        assertFalse(Utils.isVersionNewer("2.0.0", "2.0.0"))
        assertFalse(Utils.isVersionNewer("Version v1.4.5", "1.4.5"))
        assertFalse(Utils.isVersionNewer("Версия 2.0.0", "Version v2.0.0"))
        assertFalse(Utils.isVersionNewer("2.0.0", "2.0.1"))
        assertFalse(Utils.isVersionNewer("2.0.0", "2.0.0.1"))
        assertFalse(Utils.isVersionNewer("2.0.0", "2.1.0"))
        assertFalse(Utils.isVersionNewer("1.0.9", "2.0.0"))
        assertFalse(Utils.isVersionNewer("2.0.0", "2.0.010"))
        assertFalse(Utils.isVersionNewer("1.0.15", "2.0"))

        assertNull(Utils.isVersionNewer("Nonsense version", "1.0"))
        assertNull(Utils.isVersionNewer("1.0", "Nonsense version"))
        assertNull(Utils.isVersionNewer("Nonsense version", "Other nonsense version"))

        // Versions that differ only in trailing zero components are numerically equal
        // and must not be reported as a newer version (would cause spurious updates).
        assertFalse(Utils.isVersionNewer("1.0.0", "1.0"))
        assertFalse(Utils.isVersionNewer("2.3.4.0", "2.3.4"))
        assertFalse(Utils.isVersionNewer("1.0.0", "1.0.0.0"))
    }

    fun testParseWebGameOrientation() {
        // itch.io encodes a declared orientation as a query param on the embed-upload URL.
        assertEquals("landscape", Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123?orientation=landscape&bg_color=000000"))
        assertEquals("landscape_left", Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123?orientation=landscape_left&bg_color=000000"))
        assertEquals("landscape_right", Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123?bg_color=000000&orientation=landscape_right"))
        assertEquals("portrait", Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123?orientation=portrait"))

        // The site may percent-encode the value (a space instead of an underscore).
        assertEquals("landscape_left", Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123?orientation=landscape%20left"))
        assertEquals("landscape_right", Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123?orientation=landscape+right"))

        // No declared orientation / unknown values / no query string.
        assertNull(Utils.parseWebGameOrientation(
            "https://html-classic.itch.zone/html/123/index.html"))
        assertNull(Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123"))
        assertNull(Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123?bg_color=000000"))
        assertNull(Utils.parseWebGameOrientation(
            "https://itch.io/embed-upload/123?orientation=sideways"))
        assertNull(Utils.parseWebGameOrientation(null))
    }
}