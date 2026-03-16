// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains.serviceview

import ai.terok.jetbrains.*
import ai.terok.jetbrains.chat.AcpBridgeManager
import ai.terok.jetbrains.workspace.WorkspaceSwapper
import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent

/** terok tasks in the Services tool window, grouped by project. */
class TerokServiceContributor : ServiceViewContributor<TerokServiceContributor.Item> {
    sealed class Item {
        data class Header(val project: TerokProject, val tasks: List<TerokTask>) : Item()
        data class Leaf(val task: TerokTask) : Item()
    }

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = object : ServiceViewDescriptor {
        override fun getPresentation(): ItemPresentation = PresentationData("terok", null, AllIcons.Nodes.HomeFolder, null)
    }

    override fun getServices(project: Project): List<Item> {
        val cli = TerokCliService.getInstance()
        val items = mutableListOf<Item>()
        for (proj in cli.listProjects()) {
            val tasks = cli.listTasks(proj.id)
            if (tasks.isEmpty()) continue
            items.add(Item.Header(proj, tasks))
            tasks.forEach { items.add(Item.Leaf(it)) }
        }
        return items
    }

    override fun getServiceDescriptor(project: Project, item: Item) = when (item) {
        is Item.Header -> headerDescriptor(item)
        is Item.Leaf -> taskDescriptor(project, item.task)
    }

    private fun headerDescriptor(item: Item.Header) = object : ServiceViewDescriptor {
        override fun getPresentation(): ItemPresentation {
            val running = item.tasks.count { it.isRunning }
            return PresentationData(item.project.id, "$running/${item.tasks.size} running", AllIcons.Nodes.Module, null)
        }
    }

    private fun taskDescriptor(project: Project, task: TerokTask) = object : ServiceViewDescriptor {
        val svc get() = TerokProjectService.getInstance(project)
        val isActive get() = svc.attachedTaskId == task.id && svc.activeProjectId == task.projectId

        override fun getPresentation(): ItemPresentation {
            val icon = when { isActive -> AllIcons.Actions.Checked; task.isRunning -> AllIcons.Actions.Execute; else -> AllIcons.Actions.Suspend }
            val marker = if (isActive) " \u25C0" else ""
            return PresentationData("${task.displayName}${task.mode?.let { " ($it)" } ?: ""}$marker",
                task.containerState ?: "not started", icon, null)
        }

        override fun getContentComponent(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JBPanel<JBPanel<*>>(java.awt.GridLayout(0, 1, 0, 4)).apply {
                add(JBLabel("Task: ${task.displayName}"))
                add(JBLabel("Project: ${task.projectId}"))
                add(JBLabel("Status: ${task.containerState ?: "not started"}"))
                task.mode?.let { add(JBLabel("Mode: $it")) }
                if (isActive) add(JBLabel(">>> Active task (workspace + chat)"))
            }, BorderLayout.NORTH)
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                if (task.isRunning) {
                    add(JButton(if (isActive) "Active" else "Switch to Task").apply {
                        isEnabled = !isActive
                        addActionListener {
                            TerokCliService.getInstance().workspacePath(task.projectId, task.id)
                                ?.let { WorkspaceSwapper.openWorkspace(project, task) }
                            AcpBridgeManager.startChat(project, task)
                            TerokStatusBarWidget.update(project)
                            notify(project, "Switched to ${task.displayName}. Select 'terok' in AI Chat.")
                        }
                    })
                    add(JButton("Stop").apply {
                        addActionListener {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                TerokCliService.getInstance().run("task", "stop", task.projectId, task.id, timeoutMs = 30_000)
                                notify(project, "Stopped ${task.displayName}")
                            }
                        }
                    })
                } else {
                    add(JButton("Open Workspace").apply {
                        addActionListener {
                            WorkspaceSwapper.openWorkspace(project, task)
                            TerokStatusBarWidget.update(project)
                        }
                    })
                }
            }, BorderLayout.SOUTH)
        }
    }
}

private fun notify(project: Project, msg: String, type: NotificationType = NotificationType.INFORMATION) =
    NotificationGroupManager.getInstance().getNotificationGroup("terok").createNotification(msg, type).notify(project)
