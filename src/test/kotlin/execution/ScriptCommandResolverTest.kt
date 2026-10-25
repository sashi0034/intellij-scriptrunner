package dev.sashi0034.execution

import dev.sashi0034.config.ScriptConfigException
import dev.sashi0034.config.ScriptDefinition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ScriptCommandResolverTest {
    @get:Rule val temp = TemporaryFolder()
    private val resolver = ScriptCommandResolver()

    @Test fun `uses script directory and preserves all argument boundaries`() {
        val root = temp.newFolder("project").toPath()
        val file = Files.createDirectories(root.resolve("scripts/日本語 folder")).resolve("a file.py")
        Files.writeString(file, "print('test')")
        val definition = definition(root.relativize(file).toString()).copy(
            args = listOf("a b", "", "\"quotes\"", "${'$'}HOME", "*.txt", "--flag"),
            env = mapOf("APP_ENV" to "development"),
        )
        val launch = resolver.resolve(root, definition)
        assertEquals("python", launch.executable)
        assertEquals(listOf("-u", file.toString()) + definition.args, launch.arguments)
        assertEquals(file.parent, launch.workingDirectory)
        assertEquals(definition.env, launch.environment)
    }

    @Test fun `supports external absolute paths and cwd override rooted at project`() {
        val root = temp.newFolder("project").toPath()
        val external = temp.newFile("outside script.sh").toPath()
        val launch = resolver.resolve(root, definition(external.toString()).copy(command = listOf("bash"), workingDirectory = "."))
        assertEquals(root, launch.workingDirectory)
        assertEquals(listOf(external.toString()), launch.arguments)
        val other = temp.newFolder("other").toPath()
        assertEquals(other, resolver.resolve(root, definition(external.toString()).copy(workingDirectory = other.toString())).workingDirectory)
    }

    @Test fun `allows relative paths outside project and resolves explicit executable relative to project`() {
        val root = temp.newFolder("project").toPath()
        val script = temp.newFile("outside.py").toPath()
        val executable = Files.createDirectories(root.resolve("venv/bin")).resolve("python")
        Files.writeString(executable, "placeholder")
        val launch = resolver.resolve(root, definition("../outside.py").copy(command = listOf("venv/bin/python", "-u")))
        assertEquals(executable.toString(), launch.executable)
        assertEquals(listOf("-u", script.toString()), launch.arguments)
    }

    @Test fun `empty command runs script directly`() {
        val file = temp.newFile("direct").toPath()
        val launch = resolver.resolve(temp.root.toPath(), definition(file.toString()).copy(command = emptyList(), args = listOf("hello")))
        assertEquals(file.toString(), launch.executable)
        assertEquals(listOf("hello"), launch.arguments)
    }

    @Test fun `reports missing script directory and explicit executable`() {
        val root = temp.root.toPath()
        assertInvalid(root, definition("missing.py"), "script")
        val file = temp.newFile("exists.py")
        assertInvalid(root, definition(file.path).copy(workingDirectory = "missing"), "workingDirectory")
        assertInvalid(root, definition(file.path).copy(command = listOf("./missing-python")), "command[0]")
        assertInvalid(root, definition(root.toString()), "script")
    }

    private fun definition(path: String) = ScriptDefinition("test", "Test", path, listOf("python", "-u"))

    private fun assertInvalid(root: Path, script: ScriptDefinition, expected: String) {
        val error = assertThrows(ScriptConfigException::class.java) { resolver.resolve(root, script) }
        assertTrue(error.message.orEmpty().contains(expected))
    }
}
