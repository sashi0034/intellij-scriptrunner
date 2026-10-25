package dev.sashi0034.config

import org.junit.Assert.*
import org.junit.Test

class ScriptConfigParserTest {
    private val parser = ScriptConfigParser()
    private val entry = """
        - id: generate
          name: データ生成
          script: scripts/日本語 file.py
          command: [python, "-u"]
    """.trimIndent()

    private fun document(items: String = entry) = "version: 1\nscripts:\n" + items.prependIndent("  ")

    @Test fun `reads entries in YAML order and defaults optional fields`() {
        val scripts = parser.parse(document(entry + "\n" + entry.replace("generate", "second")))
        assertEquals(listOf("generate", "second"), scripts.map { it.id })
        assertEquals("データ生成", scripts.first().name)
        assertEquals(listOf("python", "-u"), scripts.first().command)
        assertEquals(emptyList<String>(), scripts.first().args)
        assertEquals(emptyMap<String, String>(), scripts.first().env)
        assertNull(scripts.first().workingDirectory)
    }

    @Test fun `reads explicit cwd arguments and environment without shell expansion`() {
        val result = parser.parse(document(entry + """

              args: ["--name", "a b", "", "${'$'}HOME", "*.txt"]
              workingDirectory: .
              env:
                APP_ENV: development
                EMPTY: ""
                NUMBER: "123"
        """.trimIndent().prependIndent("  ").removePrefix("  "))).single()
        assertEquals(listOf("--name", "a b", "", "${'$'}HOME", "*.txt"), result.args)
        assertEquals(".", result.workingDirectory)
        assertEquals(mapOf("APP_ENV" to "development", "EMPTY" to "", "NUMBER" to "123"), result.env)
    }

    @Test fun `accepts empty scripts and direct execution`() {
        assertTrue(parser.parse("version: 1\nscripts: []").isEmpty())
        assertTrue(parser.parse(document(entry.replace("[python, \"-u\"]", "[]"))).single().command.isEmpty())
    }

    @Test fun `rejects empty document and malformed YAML`() {
        invalid("", "configuration")
        invalid("version: 1\nscripts: [", "Invalid YAML")
    }

    @Test fun `requires supported integer version`() {
        for (version in listOf("2", "\"1\"", "1.0", "true", "null")) {
            invalid("version: $version\nscripts: []", "version")
        }
        invalid("scripts: []", "version")
    }

    @Test fun `requires list and mapping structure`() {
        invalid("version: 1\nscripts: {}", "scripts")
        invalid("version: 1\nscripts: [hello]", "scripts[0]")
        invalid("version: 1\nscripts: null", "scripts")
    }

    @Test fun `rejects missing fields blank strings and wrong scalar types`() {
        for (key in listOf("id", "name", "script", "command")) {
            val withoutField = if (key == "id") entry.replace(Regex("^- id: [^\\n]+"), "-")
                else entry.lines().filterNot { it.trimStart().startsWith("$key:") }.joinToString("\n")
            invalid(document(withoutField), "scripts[0].$key")
        }
        for (key in listOf("id", "name", "script")) {
            invalid(document(entry.replace(Regex("$key: [^\n]+"), "$key: \" \"")), key)
            invalid(document(entry.replace(Regex("$key: [^\n]+"), "$key: 123")), key)
        }
    }

    @Test fun `rejects duplicate IDs keys and unknown fields`() {
        invalid(document(entry + "\n" + entry), "duplicate ID")
        invalid(document() + "\n    name: duplicate", "Invalid YAML")
        invalid(document() + "\n    typo: hello", "unknown field 'typo'")
        invalid("version: 1\nscripts: []\nextra: true", "unknown field 'extra'")
        invalid("version: 1\nscripts: []\nnull: true", "field names")
    }

    @Test fun `validates command args cwd and environment`() {
        invalid(document(entry.replace("[python, \"-u\"]", "python")), "command")
        invalid(document(entry.replace("[python, \"-u\"]", "[\"\"]")), "command[0]")
        invalid(document(entry.replace("[python, \"-u\"]", "[python, 1]")), "command[1]")
        for ((field, value) in listOf("args" to "[true]", "workingDirectory" to "null", "env" to "[]")) {
            invalid(document() + "\n    $field: $value", field)
        }
        invalid(document() + "\n    env: {PORT: 8080}", "env.PORT")
        invalid(document() + "\n    env: {'BAD=KEY': x}", "cannot contain")
        invalid(document() + "\n    args: [\"\\0\"]", "NUL")
    }

    @Test fun `rejects arbitrary object tags`() {
        invalid("!!java/object:java.lang.Runtime {}", "Invalid YAML")
    }

    @Test fun `sample configuration parses as empty list`() {
        assertTrue(parser.parse(ScriptConfigService.SAMPLE).isEmpty())
    }

    private fun invalid(text: String, expected: String) {
        val error = assertThrows(ScriptConfigException::class.java) { parser.parse(text) }
        assertTrue("Expected '$expected', got '${error.message}'", error.message.orEmpty().contains(expected))
    }
}
