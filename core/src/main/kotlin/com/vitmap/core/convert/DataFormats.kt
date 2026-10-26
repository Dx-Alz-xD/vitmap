package com.vitmap.core.convert

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

enum class DataFormat(val ext: String, val label: String, val canRead: Boolean = true) {
    JSON("json", "JSON"),
    YAML("yaml", "YAML"),
    XML("xml", "XML"),
    CSV("csv", "CSV"),
    TSV("tsv", "TSV"),
    TOML("toml", "TOML", canRead = false),
    PROPERTIES("properties", "Java properties"),
    ;

    companion object {
        fun of(ext: String): DataFormat? = when (ext.lowercase()) {
            "yml" -> YAML
            else -> entries.firstOrNull { it.ext == ext.lowercase() }
        }
    }
}

/** Converts structured data between formats through a JSON tree. */
object DataFormats {
    private val pretty = Json { prettyPrint = true }

    fun read(text: String, format: DataFormat): JsonElement = when (format) {
        DataFormat.JSON -> Json.parseToJsonElement(text)
        DataFormat.YAML -> fromAny(Load(LoadSettings.builder().build()).loadFromString(text))
        DataFormat.XML -> readXml(text)
        DataFormat.CSV -> readTable(text, ',')
        DataFormat.TSV -> readTable(text, '\t')
        DataFormat.PROPERTIES -> Properties().apply { load(StringReader(text)) }.let { p -> unflatten(p.stringPropertyNames().associateWith { p.getProperty(it) }) }
        DataFormat.TOML -> throw IllegalArgumentException("Reading TOML isn't supported")
    }

    fun write(e: JsonElement, format: DataFormat): String = when (format) {
        DataFormat.JSON -> pretty.encodeToString(JsonElement.serializer(), e)
        DataFormat.YAML -> Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2).build()).dumpToString(toAny(e))
        DataFormat.XML -> writeXml(e)
        DataFormat.CSV -> writeTable(e, ',')
        DataFormat.TSV -> writeTable(e, '\t')
        DataFormat.TOML -> writeToml(e)
        DataFormat.PROPERTIES -> flatten(e).entries.joinToString("\n") { (k, v) -> "${escapeProp(k, true)}=${escapeProp(v, false)}" } + "\n"
    }

    fun convert(text: String, from: DataFormat, to: DataFormat): String = write(read(text, from), to)

    // ---------------------------------------------------------------- plain values

    @Suppress("UNCHECKED_CAST")
    fun fromAny(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to fromAny(x) })
        is Iterable<*> -> JsonArray(v.map { fromAny(it) })
        is Array<*> -> JsonArray(v.map { fromAny(it) })
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    fun toAny(e: JsonElement): Any? = when (e) {
        JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().apply { e.forEach { (k, v) -> put(k, toAny(v)) } }
        is JsonArray -> e.map { toAny(it) }
        is JsonPrimitive -> scalar(e)
    }

    private fun scalar(p: JsonPrimitive): Any? = when {
        p.isString -> p.content
        p.booleanOrNull != null -> p.booleanOrNull
        p.longOrNull != null -> p.longOrNull
        p.doubleOrNull != null -> p.doubleOrNull
        else -> p.content
    }

    private fun primitiveText(e: JsonElement): String = when (e) {
        JsonNull -> ""
        is JsonPrimitive -> e.content
        else -> Json.encodeToString(JsonElement.serializer(), e)
    }

    /** Guesses a typed value for text coming from CSV or XML. */
    private fun typed(s: String): JsonElement = when {
        s.isEmpty() -> JsonPrimitive("")
        s == "true" || s == "false" -> JsonPrimitive(s == "true")
        s.matches(Regex("-?(0|[1-9]\\d{0,17})")) -> JsonPrimitive(s.toLong())
        s.matches(Regex("-?\\d+\\.\\d+([eE][-+]?\\d+)?")) -> JsonPrimitive(s.toDouble())
        else -> JsonPrimitive(s)
    }

    // ---------------------------------------------------------------- CSV / TSV

    fun parseCsv(text: String, sep: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                sep -> { row += cell.toString(); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row += cell.toString(); cell.setLength(0); rows += row; row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows.filter { r -> r.any { it.isNotEmpty() } }
    }

    private fun readTable(text: String, sep: Char): JsonElement {
        val rows = parseCsv(text, sep)
        if (rows.isEmpty()) return JsonArray(emptyList())
        val header = rows.first().mapIndexed { i, h -> h.ifBlank { "column${i + 1}" } }
        return JsonArray(rows.drop(1).map { r -> JsonObject(header.mapIndexed { i, h -> h to typed(r.getOrElse(i) { "" }) }.toMap()) })
    }

    private fun writeTable(e: JsonElement, sep: Char): String {
        val records: List<JsonElement> = when (e) {
            is JsonArray -> e
            is JsonObject -> e.values.firstOrNull { it is JsonArray }?.let { it as JsonArray } ?: listOf(e)
            else -> listOf(e)
        }
        val flat = records.map { flatten(it) }
        val header = LinkedHashSet<String>().apply { flat.forEach { addAll(it.keys) } }.toList()
        fun cell(v: String): String = if (v.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        return buildString {
            append(header.joinToString(sep.toString()) { cell(it) }).append("\r\n")
            for (r in flat) append(header.joinToString(sep.toString()) { cell(r[it] ?: "") }).append("\r\n")
        }
    }

    /** Nested objects become dotted keys; arrays get [index] suffixes. */
    fun flatten(e: JsonElement, prefix: String = "", out: LinkedHashMap<String, String> = LinkedHashMap()): Map<String, String> {
        when (e) {
            is JsonObject -> if (e.isEmpty() && prefix.isNotEmpty()) out[prefix] = "" else e.forEach { (k, v) -> flatten(v, if (prefix.isEmpty()) k else "$prefix.$k", out) }
            is JsonArray -> if (e.all { it is JsonPrimitive || it == JsonNull }) out[prefix.ifEmpty { "value" }] = e.joinToString(";") { primitiveText(it) }
            else e.forEachIndexed { i, v -> flatten(v, "$prefix[$i]", out) }
            else -> out[prefix.ifEmpty { "value" }] = primitiveText(e)
        }
        return out
    }

    private fun unflatten(map: Map<String, String>): JsonElement {
        val root = LinkedHashMap<String, Any>()
        for ((k, v) in map.toSortedMap()) {
            val parts = k.split('.')
            var node: MutableMap<String, Any> = root
            for (p in parts.dropLast(1)) {
                @Suppress("UNCHECKED_CAST")
                node = node.getOrPut(p) { LinkedHashMap<String, Any>() } as? MutableMap<String, Any> ?: break
            }
            node[parts.last()] = v
        }
        fun conv(x: Any): JsonElement = if (x is Map<*, *>) JsonObject(x.entries.associate { (k, v) -> k.toString() to conv(v!!) }) else typed(x.toString())
        return conv(root)
    }

    private fun escapeProp(s: String, key: Boolean): String = buildString {
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            key && (c == '=' || c == ':' || c == ' ') -> append('\\').append(c)
            c.code > 126 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    // ---------------------------------------------------------------- XML

    private fun readXml(text: String): JsonElement {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(text)))
        val root = doc.documentElement
        return JsonObject(mapOf(root.tagName to element(root)))
    }

    private fun element(el: Element): JsonElement {
        val map = LinkedHashMap<String, MutableList<JsonElement>>()
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            map.getOrPut("@" + a.nodeName) { ArrayList() } += typed(a.nodeValue)
        }
        val text = StringBuilder()
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            when (n.nodeType) {
                Node.ELEMENT_NODE -> map.getOrPut((n as Element).tagName) { ArrayList() } += element(n)
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(n.nodeValue)
            }
        }
        val t = text.toString().trim()
        if (map.isEmpty()) return typed(t)
        if (t.isNotEmpty()) map.getOrPut("#text") { ArrayList() } += JsonPrimitive(t)
        return JsonObject(map.mapValues { (_, v) -> if (v.size == 1) v[0] else JsonArray(v) })
    }

    private fun writeXml(e: JsonElement): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val (rootName, rootValue) = if (e is JsonObject && e.size == 1) e.entries.first().toPair() else "root" to e
        val root = doc.createElement(xmlName(rootName))
        doc.appendChild(root)
        fun fill(parent: Element, v: JsonElement) {
            when (v) {
                is JsonObject -> for ((k, x) in v) {
                    when {
                        k.startsWith("@") -> parent.setAttribute(xmlName(k.drop(1)), primitiveText(x))
                        k == "#text" -> parent.appendChild(doc.createTextNode(primitiveText(x)))
                        x is JsonArray -> for (item in x) parent.appendChild(doc.createElement(xmlName(k)).also { fill(it, item) })
                        else -> parent.appendChild(doc.createElement(xmlName(k)).also { fill(it, x) })
                    }
                }
                is JsonArray -> for (item in v) parent.appendChild(doc.createElement("item").also { fill(it, item) })
                else -> parent.appendChild(doc.createTextNode(primitiveText(v)))
            }
        }
        fill(root, rootValue)
        val t = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        val w = StringWriter()
        t.transform(DOMSource(doc), StreamResult(w))
        return w.toString()
    }

    private fun xmlName(s: String): String {
        val cleaned = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return if (cleaned.isEmpty() || !(cleaned[0].isLetter() || cleaned[0] == '_')) "_$cleaned" else cleaned
    }

    // ---------------------------------------------------------------- TOML (write only)

    private fun writeToml(e: JsonElement): String {
        val obj = e as? JsonObject ?: JsonObject(mapOf("items" to e))
        val sb = StringBuilder()
        fun key(k: String) = if (k.matches(Regex("[A-Za-z0-9_-]+"))) k else "\"" + k.replace("\"", "\\\"") + "\""
        fun value(v: JsonElement): String = when (v) {
            JsonNull -> "\"\""
            is JsonPrimitive -> if (v.isString) "\"" + v.content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" else v.content
            is JsonArray -> v.joinToString(", ", "[", "]") { value(it) }
            is JsonObject -> v.entries.joinToString(", ", "{ ", " }") { (k, x) -> "${key(k)} = ${value(x)}" }
        }
        fun table(path: String, o: JsonObject) {
            val simple = o.filter { (_, v) -> v !is JsonObject && !(v is JsonArray && v.isNotEmpty() && v.all { it is JsonObject }) }
            if (path.isNotEmpty() && (simple.isNotEmpty() || o.isEmpty())) sb.append("\n[").append(path).append("]\n")
            for ((k, v) in simple) sb.append(key(k)).append(" = ").append(value(v)).append('\n')
            for ((k, v) in o) {
                val p = if (path.isEmpty()) key(k) else "$path.${key(k)}"
                when {
                    v is JsonObject -> table(p, v)
                    v is JsonArray && v.isNotEmpty() && v.all { it is JsonObject } -> for (item in v) {
                        sb.append("\n[[").append(p).append("]]\n")
                        for ((ik, iv) in item as JsonObject) sb.append(key(ik)).append(" = ").append(value(iv)).append('\n')
                    }
                }
            }
        }
        table("", obj)
        return sb.toString().trimStart('\n')
    }
}
