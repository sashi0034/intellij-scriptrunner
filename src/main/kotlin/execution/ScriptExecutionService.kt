package dev.sashi0034.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.actions.CloseAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.sashi0034.config.ScriptConfigException
import dev.sashi0034.config.ScriptConfigService
import dev.sashi0034.config.ScriptConfigState
import java.awt.BorderLayout
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel

object ScriptProcessFactory {
    fun create(launch: ScriptLaunch): ColoredProcessHandler {
        val command = GeneralCommandLine(launch.executable)
            .withParameters(launch.arguments)
            .withWorkDirectory(launch.workingDirectory.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(launch.environment)
        return ColoredProcessHandler(command).apply {
            setShouldDestroyProcessRecursively(true)
            ProcessTerminatedListener.attach(this)
        }
    }
}

@Service(Service.Level.PROJECT)
class ScriptExecutionService(private val project: Project) : Disposable {
    private class ActiveRun(val scriptId: String) {
        @Volatile var handler: ColoredProcessHandler? = null
    }

    private val config get() = project.service<ScriptConfigService>()
    private val sequence = AtomicLong()
    private val lock = Any()
    private val processes = mutableSetOf<ColoredProcessHandler>()
    private val activeRuns = mutableMapOf<String, ActiveRun>()
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var disposed = false

    fun run(scriptId: String, onError: (String) -> Unit): Boolean = prepare(scriptId, onError) {
        val current = readValidConfiguration()
        val definition = current.scripts.firstOrNull { it.id == scriptId }
            ?: throw ScriptConfigException("This script is no longer configured. Select a script from the updated list.")
        val root = config.projectDirectory ?: throw ScriptConfigException("The project has no directory.")
        ScriptCommandResolver().resolve(root, definition)
    }

    fun toggle(scriptId: String, onError: (String) -> Unit) {
        if (isActive(scriptId)) stop(scriptId) else run(scriptId, onError)
    }

    fun isActive(scriptId: String): Boolean = synchronized(lock) { scriptId in activeRuns }

    fun addStateListener(parentDisposable: Disposable, listener: () -> Unit) {
        stateListeners.add(listener)
        Disposer.register(parentDisposable, Disposable { stateListeners.remove(listener) })
    }

    fun stop(scriptId: String) {
        val active = synchronized(lock) {
            val found = activeRuns[scriptId] ?: return
            if (found.handler == null) activeRuns.remove(scriptId)
            found
        }
        if (active.handler == null) fireStateChanged()
        active.handler?.let(::stop)
    }

    private fun rerun(scriptId: String, snapshot: ScriptLaunch, onError: (String) -> Unit) = prepare(scriptId, onError) {
        // A malformed current configuration disables all new runs, including reruns.
        readValidConfiguration()
        snapshot
    }

    private fun readValidConfiguration(): ScriptConfigState.Ready = when (val state = config.read()) {
        is ScriptConfigState.Ready -> state
        is ScriptConfigState.Invalid -> throw ScriptConfigException(state.message)
        else -> throw ScriptConfigException("Create scripts.yml before running a script.")
    }

    private fun prepare(scriptId: String, onError: (String) -> Unit, resolve: () -> ScriptLaunch): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed || project.isDisposed) return false
        val active = synchronized(lock) {
            if (disposed || scriptId in activeRuns) return false
            ActiveRun(scriptId).also { activeRuns[scriptId] = it }
        }
        fireStateChanged()
        try {
            config.saveConfiguration()
        } catch (error: ScriptConfigException) {
            release(active)
            onError(error.message ?: "Cannot save scripts.yml.")
            return false
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (disposed || project.isDisposed) return@executeOnPooledThread
                val snapshot = resolve()
                val handler = ScriptProcessFactory.create(snapshot)
                val accepted = synchronized(lock) {
                    if (disposed || activeRuns[scriptId] !== active) {
                        false
                    } else {
                        active.handler = handler
                        processes.add(handler)
                        true
                    }
                }
                if (!accepted) {
                    handler.startNotify()
                    handler.destroyProcess()
                    return@executeOnPooledThread
                }
                handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        synchronized(lock) { processes.remove(handler) }
                        release(active)
                    }
                })
                val number = sequence.incrementAndGet()
                ApplicationManager.getApplication().invokeLater({
                    if (disposed || project.isDisposed || !isCurrent(active)) {
                        handler.startNotify()
                        stop(handler)
                    } else {
                        showConsole(scriptId, snapshot, handler, number, onError)
                    }
                }, ModalityState.any())
            } catch (error: ScriptConfigException) {
                release(active)
                report(error.message ?: "Invalid script configuration.", onError)
                if (!disposed && !project.isDisposed) config.reload()
            } catch (error: com.intellij.execution.ExecutionException) {
                release(active)
                report("Could not start the script: ${error.message}", onError)
            } catch (error: java.io.IOException) {
                release(active)
                report("Could not start the script: ${error.message}", onError)
            } catch (error: SecurityException) {
                release(active)
                report("Access denied: ${error.message}", onError)
            }
        }
        return true
    }

    private fun showConsole(
        scriptId: String,
        snapshot: ScriptLaunch,
        handler: ColoredProcessHandler,
        number: Long,
        onError: (String) -> Unit,
    ) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val panel = JPanel(BorderLayout())
        val descriptor = object : RunContentDescriptor(console, handler, panel, "${snapshot.name} #$number") {
            override fun isContentReuseProhibited() = true
        }
        descriptor.executionId = number
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val actions = DefaultActionGroup()
        actions.add(object : DumbAwareAction("Rerun", "Run again with the same settings", AllIcons.Actions.Restart) {
            override fun actionPerformed(e: AnActionEvent) {
                rerun(scriptId, snapshot, onError)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !disposed && !isActive(scriptId) && config.state is ScriptConfigState.Ready
            }
        })
        actions.add(object : DumbAwareAction("Stop", "Stop the script and its child processes", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) = stop(handler)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !handler.isProcessTerminated && !handler.isProcessTerminating
            }
        })
        actions.addSeparator()
        console.createConsoleActions().forEach(actions::add)
        actions.add(CloseAction(executor, descriptor, project))
        val toolbar = ActionManager.getInstance().createActionToolbar("ScriptRunner.Console", actions, false)
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.WEST)
        panel.add(console.component, BorderLayout.CENTER)
        Disposer.register(descriptor, Disposable { stop(handler) })
        console.attachToProcess(handler)
        // Each launch creates a new descriptor; active runs are never reused or replaced.
        RunContentManager.getInstance(project).showRunContent(executor, descriptor)
        handler.startNotify()
    }

    private fun isCurrent(active: ActiveRun): Boolean =
        synchronized(lock) { activeRuns[active.scriptId] === active }

    private fun release(active: ActiveRun) {
        val changed = synchronized(lock) {
            if (activeRuns[active.scriptId] !== active) false
            else {
                activeRuns.remove(active.scriptId)
                true
            }
        }
        if (changed) fireStateChanged()
    }

    private fun fireStateChanged() {
        val notify = Runnable {
            if (!disposed && !project.isDisposed) stateListeners.forEach { it() }
        }
        if (ApplicationManager.getApplication().isDispatchThread) notify.run()
        else ApplicationManager.getApplication().invokeLater(notify, ModalityState.any())
    }

    private fun stop(handler: ColoredProcessHandler) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!handler.isProcessTerminated && !handler.isProcessTerminating) handler.destroyProcess()
        }
    }

    private fun report(message: String, onError: (String) -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) onError(message)
        }, ModalityState.any())
    }

    override fun dispose() {
        val running = synchronized(lock) {
            disposed = true
            activeRuns.clear()
            processes.toList().also { processes.clear() }
        }
        stateListeners.clear()
        running.forEach(::stop)
    }
}
