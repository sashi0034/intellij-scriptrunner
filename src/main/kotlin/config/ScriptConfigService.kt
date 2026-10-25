package dev.sashi0034.config

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

sealed interface ScriptConfigState {
    data object Loading : ScriptConfigState
    data object Missing : ScriptConfigState
    data class Ready(val scripts: List<ScriptDefinition>) : ScriptConfigState
    data class Invalid(val message: String) : ScriptConfigState
}

@Service(Service.Level.PROJECT)
class ScriptConfigService(private val project: Project) : Disposable {
    val projectDirectory: Path? = project.basePath?.let(Path::of)
    val configPath: Path? = projectDirectory?.resolve(".idea/script-runner/scripts.yml")
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val generation = AtomicLong()
    private val listeners = CopyOnWriteArrayList<(ScriptConfigState) -> Unit>()
    @Volatile private var disposed = false
    @Volatile var state: ScriptConfigState = ScriptConfigState.Loading
        private set

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val target = configPath?.let { FileUtil.toSystemIndependentName(it.toString()) } ?: return
                if (events.any { event ->
                        val changed = event.path
                        changed == target || target.startsWith("$changed/") ||
                            event is VFileMoveEvent ||
                            (event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME)
                    }) reload()
            }
        })
        reload()
    }

    fun addListener(parent: Disposable, listener: (ScriptConfigState) -> Unit) {
        listeners.add(listener)
        Disposer.register(parent, Disposable { listeners.remove(listener) })
        listener(state)
    }

    @Synchronized
    fun reload() {
        if (disposed) return
        val request = generation.incrementAndGet()
        publish(ScriptConfigState.Loading, request)
        alarm.cancelAllRequests()
        alarm.addRequest({ publish(read(), request) }, 150)
    }

    /** Called on EDT before execution. Only this plugin's YAML document is saved. */
    fun saveConfiguration() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val file = configPath?.let { LocalFileSystem.getInstance().findFileByNioFile(it) } ?: return
        val manager = FileDocumentManager.getInstance()
        manager.getCachedDocument(file)?.let { document ->
            manager.saveDocument(document)
            if (manager.isDocumentUnsaved(document)) {
                throw ScriptConfigException("Cannot save scripts.yml. Save the configuration before running a script.")
            }
        }
    }

    /** Read afresh on a background thread; stale UI entries must never launch after an invalid edit. */
    fun read(): ScriptConfigState {
        val path = configPath ?: return ScriptConfigState.Invalid("Open a directory-based project to use Script Runner.")
        return try {
            // Do not use exists(): access errors must be reported rather than mistaken for a missing file.
            if (Files.notExists(path)) return ScriptConfigState.Missing
            if (Files.size(path) > 1_000_000) return ScriptConfigState.Invalid("scripts.yml exceeds the 1 MB limit.")
            ScriptConfigState.Ready(ScriptConfigParser().parse(Files.readString(path, StandardCharsets.UTF_8)))
        } catch (error: ScriptConfigException) {
            ScriptConfigState.Invalid(error.message ?: "Invalid configuration.")
        } catch (error: IOException) {
            ScriptConfigState.Invalid("Cannot read scripts.yml: ${error.message}")
        } catch (error: SecurityException) {
            ScriptConfigState.Invalid("Cannot access scripts.yml: ${error.message}")
        }
    }

    fun openConfiguration(create: Boolean, onError: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val path = configPath
            if (path == null) {
                onUi { onError("Open a directory-based project to use Script Runner.") }
                return@executeOnPooledThread
            }
            try {
                if (create) {
                    Files.createDirectories(path.parent)
                    try {
                        Files.writeString(path, SAMPLE, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
                    } catch (_: FileAlreadyExistsException) {
                        // Another click or editor may already have created it. Never overwrite user settings.
                    }
                }
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    ?: throw IOException("File not found: $path")
                onUi { FileEditorManager.getInstance(project).openFile(file, true) }
                reload()
            } catch (error: IOException) {
                onUi { onError("Cannot open scripts.yml: ${error.message}") }
            } catch (error: SecurityException) {
                onUi { onError("Cannot access scripts.yml: ${error.message}") }
            }
        }
    }

    private fun publish(value: ScriptConfigState, request: Long) = onUi {
        if (generation.get() == request) {
            state = value
            listeners.forEach { it(value) }
        }
    }

    private fun onUi(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) action()
        }, ModalityState.any())
    }

    @Synchronized
    override fun dispose() {
        disposed = true
        generation.incrementAndGet()
        listeners.clear()
    }

    companion object {
        val SAMPLE = """
            # Script Runner configuration. Paths are relative to the project root,
            # or absolute paths to files anywhere on this computer.
            version: 1

            # Replace [] with your scripts, using the commented example below.
            scripts: []
            #  - id: generate
            #    name: Generate data
            #    script: scripts/generate.py
            #    command: [python, "-u"]
            #    args: ["--count", "100"]
            #    workingDirectory: .  # Omit to use the script's parent directory.
            #    env:
            #      APP_ENV: development
            #
            # command is an argv prefix: [bash], [node], [pwsh, "-File"], etc.
            # Use command: [] to execute the script itself (requires an executable file).
            # Quote numeric/boolean arguments and environment values as strings.
            # Runtimes must be installed locally. No shell expansion is performed.
        """.trimIndent() + "\n"
    }
}
