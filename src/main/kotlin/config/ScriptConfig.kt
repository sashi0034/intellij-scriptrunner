package dev.sashi0034.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

data class ScriptDefinition(
    val id: String,
    val name: String,
    val script: String,
    val command: List<String>,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val env: Map<String, String> = emptyMap(),
)

class ScriptConfigException(message: String) : IllegalArgumentException(message)

/** YAML is read as data only; it never constructs arbitrary JVM objects. */
class ScriptConfigParser {
    fun parse(text: String): List<ScriptDefinition> {
        val settings = LoadSettings.builder()
            .setLabel("scripts.yml")
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(50)
            .setCodePointLimit(1_000_000)
            .build()
        val document = try {
            Load(settings).loadFromString(text)
        } catch (error: YamlEngineException) {
            throw ScriptConfigException("Invalid YAML: ${error.message}")
        }
        val root = mapping(document, "configuration")
        checkKeys(root, setOf("version", "scripts"), "configuration")
        if (root["version"] != 1) fail("version", "must be the integer 1")
        val entries = root["scripts"] as? List<*> ?: fail("scripts", "must be a list (use [] for an empty list)")
        val ids = mutableSetOf<String>()
        return entries.mapIndexed { index, entry ->
            val location = "scripts[$index]"
            val item = mapping(entry, location)
            checkKeys(item, setOf("id", "name", "script", "command", "args", "workingDirectory", "env"), location)
            val id = nonBlank(item["id"], "$location.id")
            if (!ids.add(id)) fail("$location.id", "duplicate ID '$id'")
            val command = strings(item["command"], "$location.command")
            if (command.isNotEmpty() && command.first().isBlank()) fail("$location.command[0]", "must not be blank")
            val env = if (item.containsKey("env")) {
                mapping(item["env"], "$location.env").entries.associate { (key, value) ->
                    val name = nonBlank(key, "$location.env key")
                    if ('=' in name) fail("$location.env.$name", "environment variable names cannot contain '='")
                    name to string(value, "$location.env.$name")
                }
            } else emptyMap()
            ScriptDefinition(
                id = id,
                name = nonBlank(item["name"], "$location.name"),
                script = nonBlank(item["script"], "$location.script"),
                command = command,
                args = if (item.containsKey("args")) strings(item["args"], "$location.args") else emptyList(),
                workingDirectory = if (item.containsKey("workingDirectory")) {
                    nonBlank(item["workingDirectory"], "$location.workingDirectory")
                } else null,
                env = env,
            )
        }
    }

    private fun mapping(value: Any?, location: String): Map<*, *> =
        value as? Map<*, *> ?: fail(location, "must be a mapping")

    private fun checkKeys(value: Map<*, *>, allowed: Set<String>, location: String) {
        value.keys.firstOrNull { it !is String || it !in allowed }?.let {
            fail(location, "unknown field '$it'")
        }
        if (value.containsKey(null)) fail(location, "field names must be strings")
    }

    private fun strings(value: Any?, location: String): List<String> =
        (value as? List<*> ?: fail(location, "must be a list of strings"))
            .mapIndexed { index, element -> string(element, "$location[$index]") }

    private fun string(value: Any?, location: String): String {
        if (value !is String) fail(location, "must be a string; quote numbers and booleans")
        if ('\u0000' in value) fail(location, "must not contain a NUL character")
        return value
    }

    private fun nonBlank(value: Any?, location: String): String =
        string(value, location).also { if (it.isBlank()) fail(location, "must not be blank") }

    private fun fail(location: String, reason: String): Nothing = throw ScriptConfigException("$location: $reason")
}
