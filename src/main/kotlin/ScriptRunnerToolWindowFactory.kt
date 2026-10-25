package dev.sashi0034

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.sashi0034.config.ScriptConfigService
import dev.sashi0034.config.ScriptConfigState
import dev.sashi0034.config.ScriptDefinition
import dev.sashi0034.execution.ScriptExecutionService
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class ScriptRunnerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = ScriptRunnerPanel(project)
        val content = ContentFactory.getInstance().createContent(view, null, false)
        content.setDisposer(view)
        content.preferredFocusableComponent = view.scriptList
        toolWindow.contentManager.addContent(content)
    }
}

internal class ScriptRunnerPanel(project: Project) : JPanel(BorderLayout()), Disposable {
    private val config = project.service<ScriptConfigService>()
    private val execution = project.service<ScriptExecutionService>()
    private val search = SearchTextField(false)
    private val model = DefaultListModel<ScriptDefinition>()
    val scriptList = JBList(model)
    private val status = JBTextArea()
    private val createButton = JButton("Create configuration")
    private var current: ScriptConfigState = ScriptConfigState.Loading
    private var selectedId: String? = null
    private var disposed = false

    init {
        border = JBUI.Borders.empty(4)
        scriptList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        scriptList.emptyText.text = "No scripts configured"
        scriptList.cellRenderer = object : ColoredListCellRenderer<ScriptDefinition>() {
            override fun customizeCellRenderer(
                list: JList<out ScriptDefinition>, value: ScriptDefinition?, index: Int,
                selected: Boolean, hasFocus: Boolean,
            ) {
                if (value == null) return
                val active = execution.isActive(value.id)
                icon = if (active) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
                iconTextGap = JBUI.scale(6)
                append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.script}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = "${if (active) "Stop" else "Run"} ${value.name} — ${value.script}"
            }
        }
        scriptList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!scriptList.isEnabled || !javax.swing.SwingUtilities.isLeftMouseButton(event) || event.clickCount != 1) return
                val index = scriptList.locationToIndex(event.point)
                if (index < 0) return
                val bounds = scriptList.getCellBounds(index, index) ?: return
                if (!bounds.contains(event.point)) return
                val runAreaWidth = AllIcons.Actions.Execute.iconWidth + JBUI.scale(10)
                if (event.x - bounds.x >= runAreaWidth) return
                scriptList.selectedIndex = index
                toggleSelected()
            }
        })
        scriptList.addListSelectionListener { if (!it.valueIsAdjusting) selectedId = scriptList.selectedValue?.id }
        scriptList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "scriptRunner.run")
        scriptList.actionMap.put("scriptRunner.run", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = toggleSelected()
        })
        search.textEditor.emptyText.text = "Search scripts"
        search.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateList()
        })
        val actions = DefaultActionGroup()
        actions.add(object : DumbAwareAction("Open Configuration", "Open scripts.yml", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) = config.openConfiguration(false, ::showError)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = current !is ScriptConfigState.Missing && current !is ScriptConfigState.Loading
            }
        })
        actions.add(object : DumbAwareAction("Reload", "Reload scripts.yml", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = config.reload()
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("ScriptRunner.Toolbar", actions, true)
        toolbar.targetComponent = this
        val header = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(search, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(scriptList), BorderLayout.CENTER)
        status.isEditable = false
        status.isOpaque = false
        status.lineWrap = true
        status.wrapStyleWord = true
        status.border = JBUI.Borders.empty(6, 2)
        createButton.addActionListener { config.openConfiguration(true, ::showError) }
        val footer = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(createButton, BorderLayout.SOUTH)
        }
        add(footer, BorderLayout.SOUTH)
        execution.addStateListener(this) {
            updateInteractionState()
            scriptList.repaint()
        }
        config.addListener(this) { state ->
            current = state
            updateList()
            createButton.isVisible = state is ScriptConfigState.Missing
            status.foreground = if (state is ScriptConfigState.Invalid) JBColor.RED else JBColor.foreground()
            status.text = when (state) {
                ScriptConfigState.Loading -> "Loading configuration…"
                ScriptConfigState.Missing -> "Create .idea/script-runner/scripts.yml to register scripts."
                is ScriptConfigState.Invalid -> state.message
                is ScriptConfigState.Ready -> if (state.scripts.isEmpty()) "Add scripts to scripts.yml to get started." else "${state.scripts.size} scripts · Click ▶ or press Enter to run"
            }
            revalidate()
            repaint()
        }
    }

    private fun updateList() {
        val scripts = (current as? ScriptConfigState.Ready)?.scripts ?: run {
            // Retain the previous list during reload/errors, but disable all new runs.
            updateInteractionState()
            return
        }
        val previousId = selectedId
        val query = search.text.trim()
        val filtered = scripts.filter { it.name.contains(query, true) || it.script.contains(query, true) }
        model.clear()
        filtered.forEach(model::addElement)
        val index = filtered.indexOfFirst { it.id == previousId }
        if (filtered.isNotEmpty()) scriptList.selectedIndex = if (index >= 0) index else 0
        scriptList.emptyText.text = if (scripts.isEmpty()) "No scripts configured" else "No matching scripts"
        updateInteractionState()
    }

    private fun updateInteractionState() {
        scriptList.isEnabled = current is ScriptConfigState.Ready || (0 until model.size).any {
            execution.isActive(model.getElementAt(it).id)
        }
    }

    private fun toggleSelected() {
        val id = scriptList.selectedValue?.id ?: return
        if (execution.isActive(id)) execution.stop(id)
        else if (current is ScriptConfigState.Ready) execution.run(id, ::showError)
    }

    private fun showError(message: String) {
        if (disposed) return
        status.foreground = JBColor.RED
        status.text = message
    }

    override fun dispose() {
        disposed = true
    }
}
