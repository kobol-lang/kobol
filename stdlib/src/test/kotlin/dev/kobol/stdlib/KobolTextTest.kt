package dev.kobol.stdlib

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KobolTextTest {

    // ── case ─────────────────────────────────────────────────────────────

    @Test fun `uppercase converts to upper`()     = assertEquals("HELLO", KobolText.uppercase("hello"))
    @Test fun `lowercase converts to lower`()     = assertEquals("hello", KobolText.lowercase("HELLO"))

    // ── trim ─────────────────────────────────────────────────────────────

    @Test fun `trim removes both sides`()         = assertEquals("hi", KobolText.trim("  hi  "))
    @Test fun `trimStart removes leading`()       = assertEquals("hi  ", KobolText.trimStart("  hi  "))
    @Test fun `trimEnd removes trailing`()        = assertEquals("  hi", KobolText.trimEnd("  hi  "))

    // ── substring ────────────────────────────────────────────────────────

    @Test fun `substring 1-based`()               = assertEquals("ello", KobolText.substring("hello", 2L, 4L))
    @Test fun `substring clamps to end`()         = assertEquals("lo",   KobolText.substring("hello", 4L, 99L))

    // ── find ─────────────────────────────────────────────────────────────

    @Test fun `find returns 1-based position`()   = assertEquals(2L, KobolText.find("hello", "ell"))
    @Test fun `find returns 0 when absent`()      = assertEquals(0L, KobolText.find("hello", "xyz"))

    // ── contains / starts / ends ─────────────────────────────────────────

    @Test fun `contains true`()                   = assertTrue(KobolText.contains("foobar", "oob"))
    @Test fun `contains false`()                  = assertFalse(KobolText.contains("foobar", "xyz"))
    @Test fun `startsWith true`()                 = assertTrue(KobolText.startsWith("foobar", "foo"))
    @Test fun `startsWith false`()                = assertFalse(KobolText.startsWith("foobar", "bar"))
    @Test fun `endsWith true`()                   = assertTrue(KobolText.endsWith("foobar", "bar"))
    @Test fun `endsWith false`()                  = assertFalse(KobolText.endsWith("foobar", "foo"))

    // ── replace ──────────────────────────────────────────────────────────

    @Test fun `replace all occurrences`()         = assertEquals("bbb", KobolText.replace("aaa", "a", "b"))

    // ── split / combine ──────────────────────────────────────────────────

    @Test fun `split on delimiter`()              = assertEquals(listOf("a", "b", "c"), KobolText.split("a,b,c", ","))
    @Test fun `combine two strings`()             = assertEquals("Hello World", KobolText.combine("Hello", " World"))
    @Test fun `combine list with delimiter`()     = assertEquals("a,b,c", KobolText.combine(listOf("a", "b", "c"), ","))

    // ── length ───────────────────────────────────────────────────────────

    @Test fun `length of string`()                = assertEquals(5L, KobolText.length("hello"))
    @Test fun `length of empty string`()          = assertEquals(0L, KobolText.length(""))

    // ── padding ──────────────────────────────────────────────────────────

    @Test fun `padLeft pads with spaces`()        = assertEquals("  hi", KobolText.padLeft("hi", 4L))
    @Test fun `padRight pads with spaces`()       = assertEquals("hi  ", KobolText.padRight("hi", 4L))
    @Test fun `padLeft already wide enough`()     = assertEquals("hello", KobolText.padLeft("hello", 3L))

    // ── repeat ───────────────────────────────────────────────────────────

    @Test fun `repeat string`()                   = assertEquals("abab", KobolText.repeat("ab", 2L))
    @Test fun `repeat zero times`()               = assertEquals("", KobolText.repeat("ab", 0L))

    // ── blank / empty ─────────────────────────────────────────────────────

    @Test fun `isBlank on whitespace`()           = assertTrue(KobolText.isBlank("   "))
    @Test fun `isBlank false on content`()        = assertFalse(KobolText.isBlank("  a  "))
    @Test fun `isEmpty on empty string`()         = assertTrue(KobolText.isEmpty(""))
    @Test fun `isEmpty false on whitespace`()     = assertFalse(KobolText.isEmpty("  "))

    // ── hash ──────────────────────────────────────────────────────────────

    @Test fun `hash SHA-256 of empty string`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            KobolText.hash("", "SHA-256")
        )
    }

    @Test fun `hash SHA-256 of hello`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            KobolText.hash("hello", "SHA-256")
        )
    }

    @Test fun `hash MD5 of hello`() {
        assertEquals(
            "5d41402abc4b2a76b9719d911017c592",
            KobolText.hash("hello", "MD5")
        )
    }

    @Test fun `hash SHA-512 produces 128-char hex`() {
        val result = KobolText.hash("test", "SHA-512")
        assertEquals(128, result.length)
        assertTrue(result.all { it.isDigit() || it in 'a'..'f' })
    }
}
