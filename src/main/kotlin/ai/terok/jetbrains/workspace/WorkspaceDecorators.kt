// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains.workspace

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.util.function.Function
import javax.swing.JComponent

private fun isWsFile(file: VirtualFile) = file.path.contains("workspace-dangerous")
private fun isActive(project: Project) = WorkspaceSwapper.isAttached(project)
private fun tabColor() = if (UIUtil.isUnderDarcula()) Color(120, 70, 0, 110) else Color(255, 200, 80, 40)

class TabColor : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile) =
        if (isActive(project) && isWsFile(file)) tabColor() else null
}

class TabTitle : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile) =
        if (isActive(project) && isWsFile(file)) "\u2699 ${file.name}" else null
}

class Banner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile) = Function<FileEditor, JComponent?> {
        if (!isActive(project) || !isWsFile(file)) return@Function null
        EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
            text = "Agent workspace \u2014 edits visible to agent immediately"
            createActionLabel("Return to Local") { WorkspaceSwapper.returnToLocal(project) }
        }
    }
}
