package dev.kobol.project

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TomlParserTest {

    @Test fun `minimal project section`() {
        val d = TomlParser.parse("""
            [project]
            name    = "my-app"
            version = "1.2.3"
        """.trimIndent())
        assertEquals("my-app", d.name)
        assertEquals("1.2.3", d.version)
    }

    @Test fun `build section overrides defaults`() {
        val d = TomlParser.parse("""
            [project]
            name = "app"

            [build]
            source-dir  = "src"
            output-dir  = "out"
            java-target = "17"
            fat-jar     = false
        """.trimIndent())
        assertEquals("src", d.sourceDir)
        assertEquals("out", d.outputDir)
        assertEquals("17", d.javaTarget)
        assertFalse(d.fatJar)
    }

    @Test fun `dependencies section`() {
        val d = TomlParser.parse("""
            [project]
            name = "app"

            [dependencies]
            "org.postgresql:postgresql" = "42.7.3"
            "com.google.guava:guava"   = "33.0.0-jre"
        """.trimIndent())
        assertEquals(2, d.dependencies.size)
        assertEquals("42.7.3",       d.dependencies["org.postgresql:postgresql"])
        assertEquals("33.0.0-jre",   d.dependencies["com.google.guava:guava"])
    }

    @Test fun `server port is parsed as integer`() {
        val d = TomlParser.parse("""
            [project]
            name = "api"

            [server]
            port = 9090
        """.trimIndent())
        assertEquals(9090, d.serverPort)
    }

    @Test fun `comments are stripped`() {
        val d = TomlParser.parse("""
            # Project descriptor
            [project]
            name = "app" # trailing comment
            version = "0.1.0"
        """.trimIndent())
        assertEquals("app",   d.name)
        assertEquals("0.1.0", d.version)
    }

    @Test fun `defaults are applied when sections missing`() {
        val d = TomlParser.parse("""
            [project]
            name = "simple"
        """.trimIndent())
        assertEquals("0.1.0",    d.version)
        assertEquals("Main",     d.main)
        assertEquals("src/main", d.sourceDir)
        assertEquals("build",    d.outputDir)
        assertTrue(d.fatJar)
        assertEquals(8080, d.serverPort)
    }
}
