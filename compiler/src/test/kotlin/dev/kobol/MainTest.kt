package dev.kobol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MainTest {

    // #1 — generated class name is derived from the PROGRAM name (sanitized to a
    // legal Java identifier), not the file name.
    @Test fun `sanitizeJavaClassName preserves PascalCase PROGRAM name`() {
        assertEquals("DataTypes", sanitizeJavaClassName("DataTypes"))
    }

    @Test fun `sanitizeJavaClassName PascalCases a kebab name`() {
        assertEquals("DataTypes", sanitizeJavaClassName("data-types"))
    }

    @Test fun `sanitizeJavaClassName prefixes a leading-digit name`() {
        // file stem like 01-data-types must not yield the unrunnable 01DataTypes
        assertEquals("_01DataTypes", sanitizeJavaClassName("01-data-types"))
    }

    @Test fun `sanitizeJavaClassName falls back to Program when empty`() {
        assertEquals("Program", sanitizeJavaClassName("---"))
    }
}
