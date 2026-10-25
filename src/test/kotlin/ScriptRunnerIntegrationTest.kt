package dev.sashi0034

import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.Executor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import dev.sashi0034.config.ScriptConfigService
import dev.sashi0034.config.ScriptConfigState
import dev.sashi0034.execution.ScriptExecutionService
import java.awt.Container
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path

/** Exercises project services, Swing views, document saves, and Run descriptors inside the IDE test platform. */
class ScriptRunnerIntegrationTest : HeavyPlatformTestCase() {
    override fun isCreateDirectoryBasedProject() = true

    fun testPanelReflectsReloadSearchAndInvalidConfiguration() {
        val config = project.service<ScriptConfigService>()
        writeConfiguration("""
            version: 1
            scripts:
              - id: first
                name: First
                script: scripts/first.py
                command: [python]
              - id: second
                name: Second
                script: scripts/second.py
                command: [python]
        """.trimIndent())
        val panel = disposeOnTearDown(ScriptRunnerPanel(project))
        await { panel.scriptList.model.size == 2 && panel.scriptList.isEnabled }
        assertEquals("First", panel.scriptList.model.getElementAt(0).name)
        val search = descendants(panel).filterIsInstance<SearchTextField>().single()
        search.text = "SECOND.PY"
        assertEquals(1, panel.scriptList.model.size)
        assertEquals("second", panel.scriptList.selectedValue.id)

        Files.writeString(config.configPath!!, "version: 99\nscripts: []")
        LocalFileSystem.getInstance().findFileByNioFile(config.configPath)!!.refresh(false, false)
        await { config.state is ScriptConfigState.Invalid && !panel.scriptList.isEnabled }
        assertEquals("Retain the previous list while invalid", 1, panel.scriptList.model.size)
        writeConfiguration("version: 1\nscripts: []")
        await { config.state is ScriptConfigState.Ready && panel.scriptList.model.size == 0 && panel.scriptList.isEnabled }
    }

    fun testRunIconTogglesFromStartToStopButClickingRowTextOnlySelectsIt() {
        writeConfiguration(fixtureConfiguration())
        val actualManager = RunContentManager.getInstance(project)
        val tabs = mutableListOf<RunContentDescriptor>()
        val manager = object : RunContentManager by actualManager {
            override fun showRunContent(executor: Executor, descriptor: RunContentDescriptor) {
                tabs.add(descriptor)
                Disposer.register(testRootDisposable, descriptor)
            }
        }
        project.replaceService(RunContentManager::class.java, manager, testRootDisposable)
        val panel = disposeOnTearDown(ScriptRunnerPanel(project))
        await { panel.scriptList.model.size == 1 && panel.scriptList.isEnabled }
        panel.scriptList.setSize(500, 100)
        val bounds = panel.scriptList.getCellBounds(0, 0)!!
        fun click(x: Int) = panel.scriptList.dispatchEvent(
            MouseEvent(
                panel.scriptList,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                x,
                bounds.y + bounds.height / 2,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )

        click(bounds.x + 100)
        assertTrue("Clicking the label should only select the row", tabs.isEmpty())
        click(bounds.x + 2)
        await { tabs.size == 1 }
        val execution = project.service<ScriptExecutionService>()
        assertTrue(execution.isActive("fixture"))
        val value = panel.scriptList.model.getElementAt(0)
        val runningCell = panel.scriptList.cellRenderer.getListCellRendererComponent(
            panel.scriptList,
            value,
            0,
            true,
            false,
        ) as ColoredListCellRenderer<*>
        assertSame(com.intellij.icons.AllIcons.Actions.Suspend, runningCell.icon)

        click(bounds.x + 2)
        await { tabs.single().processHandler!!.isProcessTerminated && !execution.isActive("fixture") }
        assertEquals("Stopping from the row must not create another Run tab", 1, tabs.size)
    }

    fun testConfigurationCreationAndExistingFilePreservation() {
        val config = project.service<ScriptConfigService>()
        var error: String? = null
        config.openConfiguration(true) { error = it }
        await { error != null || (config.state is ScriptConfigState.Ready && FileEditorManager.getInstance(project).openFiles.isNotEmpty()) }
        assertNull(error)
        val path = config.configPath!!
        assertEquals(ScriptConfigService.SAMPLE, Files.readString(path))
        val original = "# Keep my comments\nversion: 1\nscripts: []\n"
        writeConfiguration(original)
        FileEditorManager.getInstance(project).let { manager -> manager.openFiles.forEach(manager::closeFile) }
        config.openConfiguration(true) { error = it }
        await { error != null || FileEditorManager.getInstance(project).openFiles.isNotEmpty() }
        assertNull(error)
        assertEquals("Creating again must not overwrite comments or data", original, Files.readString(path))
    }

    fun testUnsavedInvalidConfigurationIsSavedAndBlocksLaunch() {
        val config = project.service<ScriptConfigService>()
        writeConfiguration(fixtureConfiguration())
        await { config.state is ScriptConfigState.Ready }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(config.configPath!!)!!
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("version: 99\nscripts: []") }
        assertTrue(documents.isDocumentUnsaved(document))
        var error: String? = null
        project.service<ScriptExecutionService>().run("fixture") { error = it }
        await { error != null }
        assertTrue(error.orEmpty().contains("version"))
        assertFalse(documents.isDocumentUnsaved(document))
        assertEquals("version: 99\nscripts: []", Files.readString(config.configPath))
        assertTrue(RunContentManager.getInstance(project).allDescriptors.isEmpty())
    }

    fun testSeparateRunTabsCloseAndServiceDisposalStopProcesses() {
        val config = project.service<ScriptConfigService>()
        writeConfiguration(fixtureConfiguration(includeSecond = true))
        await { config.state is ScriptConfigState.Ready }
        var error: String? = null
        val execution = project.service<ScriptExecutionService>()
        val actualManager = RunContentManager.getInstance(project)
        val tabs = mutableListOf<RunContentDescriptor>()
        // The platform's showRunContent deliberately does nothing in unit test mode.
        // Capture the real console descriptors without requiring a native IDE frame.
        val manager = object : RunContentManager by actualManager {
            override fun showRunContent(executor: Executor, descriptor: RunContentDescriptor) {
                tabs.add(descriptor)
                Disposer.register(testRootDisposable, descriptor)
            }
            override fun getAllDescriptors(): List<RunContentDescriptor> = tabs
        }
        project.replaceService(RunContentManager::class.java, manager, testRootDisposable)
        assertTrue(execution.run("fixture") { error = it })
        assertFalse("The same script ID must not start twice", execution.run("fixture") { error = it })
        assertTrue(execution.run("second-fixture") { error = it })
        await { error != null || manager.allDescriptors.size == 2 }
        assertNull(error)
        assertEquals(2, tabs.map { it.displayName }.distinct().size)
        assertTrue(tabs.all { it.isContentReuseProhibited })
        val firstTab = tabs.single { it.displayName.startsWith("Test fixture #") }
        val secondTab = tabs.single { it.displayName.startsWith("Second fixture #") }
        val first = firstTab.processHandler!!
        val second = secondTab.processHandler!!
        assertFalse(first.isProcessTerminated)
        assertFalse(second.isProcessTerminated)

        execution.stop("fixture")
        await { first.isProcessTerminated && !execution.isActive("fixture") }
        assertFalse("Stopping one script must not stop a different script", second.isProcessTerminated)

        // Rerun must keep the original launch snapshot even after valid YAML edits.
        writeConfiguration(fixtureConfiguration(includeSecond = true).replace("args: [sleep]", "args: [changed]"))
        await { config.state is ScriptConfigState.Ready }
        val toolbar = descendants(firstTab.component).filterIsInstance<ActionToolbar>().first()
        val rerun = toolbar.actionGroup.getChildren(null).first()
        rerun.actionPerformed(TestActionEvent.createTestEvent(rerun))
        await { error != null || tabs.size == 3 }
        assertNull(error)
        val third = tabs[2].processHandler as com.intellij.execution.process.ColoredProcessHandler
        assertEquals((first as com.intellij.execution.process.ColoredProcessHandler).commandLine, third.commandLine)

        Disposer.dispose(tabs[2])
        await { third.isProcessTerminated }
        assertFalse(second.isProcessTerminated)
        Disposer.dispose(execution)
        await { second.isProcessTerminated }
    }

    private fun fixtureConfiguration(includeSecond: Boolean = false): String {
        val root = Path.of(project.basePath!!)
        Files.createDirectories(root)
        val script = root.resolve("test.script-runner-fixture")
        Files.writeString(script, "fixture")
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        fun quote(value: String) = "'${value.replace("'", "''")}'"
        val first = """
            version: 1
            scripts:
              - id: fixture
                name: Test fixture
                script: test.script-runner-fixture
                command: [${quote(java.toString())}, '-cp', ${quote(System.getProperty("scriptRunner.fixtureClasspath"))}, 'dev.sashi0034.ProcessFixture']
                args: [sleep]
        """.trimIndent()
        if (!includeSecond) return first
        val second = """
            - id: second-fixture
              name: Second fixture
              script: test.script-runner-fixture
              command: [${quote(java.toString())}, '-cp', ${quote(System.getProperty("scriptRunner.fixtureClasspath"))}, 'dev.sashi0034.ProcessFixture']
              args: [sleep]
        """.trimIndent().prependIndent("  ")
        return "$first\n$second"
    }

    private fun writeConfiguration(text: String) {
        val config = project.service<ScriptConfigService>()
        val path = config.configPath!!
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        config.reload()
    }

    private fun await(condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("Script Runner operation did not complete", condition, 20)

    private fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
