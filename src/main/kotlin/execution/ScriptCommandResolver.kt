package dev.sashi0034.execution

import dev.sashi0034.config.ScriptConfigException
import dev.sashi0034.config.ScriptDefinition
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A complete launch snapshot. Rerunning never reads a subsequently edited configuration. */
data class ScriptLaunch(
    val name: String,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String>,
)

class ScriptCommandResolver {
    fun resolve(projectDirectory: Path, script: ScriptDefinition): ScriptLaunch {
        try {
            val root = projectDirectory.toAbsolutePath().normalize()
            fun resolvePath(value: String): Path = root.resolve(value).normalize().toAbsolutePath()
            val file = resolvePath(script.script)
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw ScriptConfigException("${script.id}.script: file does not exist or is not readable: $file")
            }
            val directory = script.workingDirectory?.let(::resolvePath) ?: file.parent
            if (!Files.isDirectory(directory)) {
                throw ScriptConfigException("${script.id}.workingDirectory: directory does not exist: $directory")
            }
            val command = script.command.toMutableList()
            if (command.isNotEmpty()) {
                val program = command[0]
                if (Path.of(program).isAbsolute || '/' in program || '\\' in program) {
                    val executable = resolvePath(program)
                    if (!Files.isRegularFile(executable)) {
                        throw ScriptConfigException("${script.id}.command[0]: executable does not exist: $executable")
                    }
                    command[0] = executable.toString()
                }
            }
            command.add(file.toString())
            command.addAll(script.args)
            return ScriptLaunch(script.name, command.first(), command.drop(1), directory, script.env.toMap())
        } catch (error: InvalidPathException) {
            throw ScriptConfigException("${script.id}: invalid path: ${error.input}")
        }
    }
}
