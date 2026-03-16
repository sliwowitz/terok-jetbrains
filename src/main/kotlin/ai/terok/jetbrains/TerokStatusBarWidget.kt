// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.*
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

class TerokStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ID
    override fun getDisplayName() = "terok Active Task"
    override fun createWidget(project: Project) = TerokStatusBarWidget(project)
}

private const val ID = "terok.activeTask"

class TerokStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        fun update(project: Project) {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }
    }

    override fun ID() = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {}
    override fun getAlignment() = Component.LEFT_ALIGNMENT
    override fun getClickConsumer(): Consumer<MouseEvent>? = null
    override fun getTooltipText(): String {
        val svc = TerokProjectService.getInstance(project)
        return svc.attachedTaskId?.let { "terok: ${svc.activeProjectId}/$it" } ?: "No active terok task"
    }

    override fun getText(): String {
        val svc = TerokProjectService.getInstance(project)
        val taskId = svc.attachedTaskId ?: return "terok: \u2014"
        val projectId = svc.activeProjectId ?: return "terok: #$taskId"
        val task = TerokCliService.getInstance().listTasks(projectId).find { it.id == taskId }
        return "terok: ${task?.displayName ?: "#$taskId"}"
    }
}
