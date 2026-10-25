package dev.sashi0034.execution

import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sashi0034.ProcessFixture
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScriptProcessTest : BasePlatformTestCase() {
    fun testOutputInputArgumentsEnvironmentAndExitCode() {
        val directory = Files.createTempDirectory("script runner 日本語 ")
        val process = ScriptProcessFactory.create(launch(directory, listOf("a b", "", "日本語", "\"quote\"", "${'$'}HOME")))
        val out = StringBuffer()
        val err = StringBuffer()
        val ready = CountDownLatch(1)
        process.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.STDERR) err.append(event.text) else out.append(event.text)
                if (out.contains("READY")) ready.countDown()
            }
        })
        try {
            process.startNotify()
            assertTrue("Process did not become ready: $out $err", ready.await(20, TimeUnit.SECONDS))
            process.processInput.apply { write("入力テスト\n".toByteArray(StandardCharsets.UTF_8)); flush() }
            assertTrue(process.waitFor(20_000))
            assertEquals(7, process.exitCode)
            assertTrue(out.toString(), out.contains("OUT=こんにちは"))
            assertTrue(out.contains("COLOR"))
            assertFalse(out.contains("\u001b[31m"))
            assertTrue(err.contains("ERR=problem"))
            assertTrue(out.contains("CWD=$directory"))
            assertTrue(out.contains("ENV=override"))
            assertTrue(out.contains("PARENT=true"))
            assertTrue(out.contains("ARG0=a b"))
            assertTrue(out.contains("ARG1=\r\n") || out.contains("ARG1=\n"))
            assertTrue(out.contains("ARG2=日本語"))
            assertTrue(out.contains("ARG3=\"quote\""))
            assertTrue(out.contains("ARG4=${'$'}HOME"))
            assertTrue(out.contains("INPUT=入力テスト"))
        } finally {
            terminate(process)
            Files.deleteIfExists(directory)
        }
    }

    fun testSeparateProcessesRunConcurrentlyAndStopIndependently() {
        val first = ScriptProcessFactory.create(launch(Path.of(System.getProperty("java.io.tmpdir")), listOf("sleep")))
        val second = ScriptProcessFactory.create(launch(Path.of(System.getProperty("java.io.tmpdir")), listOf("sleep")))
        try {
            val firstReady = ready(first)
            val secondReady = ready(second)
            assertTrue(firstReady.await(20, TimeUnit.SECONDS))
            assertTrue(secondReady.await(20, TimeUnit.SECONDS))
            assertTrue(first.process.isAlive)
            assertTrue(second.process.isAlive)
            terminate(first)
            assertTrue("Stopping one run must not stop the other", second.process.isAlive)
        } finally {
            terminate(first)
            terminate(second)
        }
    }

    fun testStopTerminatesChildProcess() {
        val process = ScriptProcessFactory.create(launch(Path.of(System.getProperty("java.io.tmpdir")), listOf("tree")))
        val output = StringBuffer()
        val childReady = CountDownLatch(1)
        process.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
                if (Regex("CHILD=\\d+[\\r\\n]").containsMatchIn(output)) childReady.countDown()
            }
        })
        var child: ProcessHandle? = null
        try {
            process.startNotify()
            assertTrue(childReady.await(20, TimeUnit.SECONDS))
            val pid = Regex("CHILD=(\\d+)").find(output)!!.groupValues[1].toLong()
            child = ProcessHandle.of(pid).orElseThrow()
            terminate(process)
            child.onExit().get(20, TimeUnit.SECONDS)
            assertFalse("Child process survived stop", child.isAlive)
        } finally {
            terminate(process)
            child?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    private fun launch(directory: Path, args: List<String>): ScriptLaunch {
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classpath = System.getProperty("scriptRunner.fixtureClasspath")
        return ScriptLaunch("Fixture", executable.toString(), listOf("-Dfile.encoding=UTF-8", "-cp", classpath, ProcessFixture::class.java.name) + args,
            directory, mapOf("SCRIPT_RUNNER_TEST" to "override"))
    }

    private fun ready(process: ColoredProcessHandler): CountDownLatch {
        val latch = CountDownLatch(1)
        process.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (event.text.contains("READY")) latch.countDown()
            }
        })
        process.startNotify()
        return latch
    }

    private fun terminate(process: ColoredProcessHandler) {
        if (!process.isProcessTerminated) {
            process.destroyProcess()
            assertTrue("Process did not stop", process.waitFor(20_000))
        }
    }
}
