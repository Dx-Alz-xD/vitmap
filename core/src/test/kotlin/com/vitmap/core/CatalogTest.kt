package com.vitmap.core

import com.vitmap.core.code.Esolangs
import com.vitmap.core.code.LuaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs the offline templates from the Code Writer catalog so a typo can't ship. */
class CatalogTest {
    private val file = listOfNotNull(System.getProperty("vitmap.root"), "..")
        .map { File(it, "app/src/main/assets/code/languages.json") }
        .firstOrNull { it.exists() }

    @Test
    fun offlineTemplatesRun() {
        val f = file ?: return
        val langs = Json.parseToJsonElement(f.readText()).jsonArray.map { it.jsonObject }
        assertTrue(langs.size > 50)
        for (l in langs) {
            val local = l["local"]?.jsonPrimitive?.content ?: continue
            val template = l["template"]!!.jsonPrimitive.content
            val out = StringBuilder()
            when {
                local.startsWith("esolang:") -> Esolangs.run(local.removePrefix("esolang:"), template, Esolangs.Io("", { out.append(it) }))
                local == "lua" -> LuaRunner.run(template, "", { out.append(it) }, { out.append(it) }, { false })
                else -> continue
            }
            val id = l["id"]!!.jsonPrimitive.content
            val ok = if (id == "deadfish") out.isNotEmpty() else "Hello" in out.toString()
            assertTrue(ok, "$id printed: $out")
        }
    }
}
