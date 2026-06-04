package dev.kobol.stdlib

import java.math.BigDecimal
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource

/**
 * KobolXml — lightweight XML serialiser/deserialiser for Kobol programs.
 *
 * Supports: null, Boolean, Long, BigDecimal, String, UUID, List, Map,
 * and any JVM object (serialised via reflection over declared fields).
 *
 * Backed by the JDK built-in XML APIs (javax.xml, org.w3c.dom).
 * No external dependencies required.
 */
object KobolXml {

    @JvmStatic fun toXml(value: Any?): String       = serialize(value, indent = false)
    @JvmStatic fun toPrettyXml(value: Any?): String = serialize(value, indent = true)

    @JvmStatic fun writeToFile(value: Any?, path: String) {
        java.io.File(path).also { it.parentFile?.mkdirs() }.writeText(toXml(value))
    }

    @JvmStatic fun writePrettyToFile(value: Any?, path: String) {
        java.io.File(path).also { it.parentFile?.mkdirs() }.writeText(toPrettyXml(value))
    }

    /**
     * Populate the fields of [target] by parsing the XML document in [xml].
     * The root element's child elements are mapped to fields by name.
     */
    @JvmStatic fun parseInto(target: Any, xml: String) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val root = doc.documentElement
        val cls  = target::class.java
        for (field in cls.declaredFields) {
            if (field.isSynthetic) continue
            val elements = root.getElementsByTagName(field.name)
            if (elements.length == 0) continue
            val text = elements.item(0).textContent ?: continue
            field.isAccessible = true
            try {
                field.set(target, coerceXmlText(text, field.type))
            } catch (_: Exception) { /* skip incompatible */ }
        }
    }

    /** Read XML from a file and populate [target]'s fields. */
    @JvmStatic fun parseFileInto(target: Any, path: String) =
        parseInto(target, java.io.File(path).readText())

    /**
     * Parse an XML document whose root contains child elements,
     * returning each child's text content as an ArrayList of Strings.
     */
    @JvmStatic fun parseXmlElements(xml: String): java.util.ArrayList<Any?> {
        val result = java.util.ArrayList<Any?>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                result.add(node.textContent)
            }
        }
        return result
    }

    /** Read XML elements from a file. */
    @JvmStatic fun parseFileElements(path: String): java.util.ArrayList<Any?> =
        parseXmlElements(java.io.File(path).readText())

    // ── Serialisation ────────────────────────────────────────────────────

    private fun serialize(value: Any?, indent: Boolean): String {
        val factory = DocumentBuilderFactory.newInstance()
        val doc     = factory.newDocumentBuilder().newDocument()
        val root    = buildElement(doc, rootTagName(value), value)
        doc.appendChild(root)

        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        if (indent) {
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    private fun rootTagName(value: Any?): String = when (value) {
        null   -> "null"
        is List<*> -> "list"
        is Map<*,*> -> "map"
        else   -> xmlTag(value::class.java.simpleName)
    }

    private fun buildElement(doc: Document, tag: String, value: Any?): Element {
        val el = doc.createElement(tag)
        when (value) {
            null -> el.textContent = ""
            is Boolean, is Long, is Int, is Short, is Byte,
            is BigDecimal, is Number, is String, is UUID -> el.textContent = value.toString()
            is List<*> -> value.forEachIndexed { i, item ->
                el.appendChild(buildElement(doc, "item", item))
            }
            is Map<*,*> -> value.entries.forEach { (k, v) ->
                el.appendChild(buildElement(doc, xmlTag(k.toString()), v))
            }
            else -> {
                val cls = value::class.java
                for (field in cls.declaredFields) {
                    if (field.isSynthetic) continue
                    field.isAccessible = true
                    el.appendChild(buildElement(doc, xmlTag(field.name), field.get(value)))
                }
            }
        }
        return el
    }

    private fun xmlTag(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_\\-.]"), "-").let { if (it[0].isDigit()) "_$it" else it }

    private fun coerceXmlText(text: String, targetType: Class<*>): Any? = when {
        targetType == String::class.java -> text
        targetType == Long::class.java || targetType == java.lang.Long.TYPE ->
            text.toLongOrNull() ?: 0L
        targetType == java.math.BigDecimal::class.java ->
            runCatching { java.math.BigDecimal(text) }.getOrDefault(java.math.BigDecimal.ZERO)
        targetType == Boolean::class.java || targetType == java.lang.Boolean.TYPE ->
            text.equals("true", ignoreCase = true)
        else -> text
    }
}
