// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains.workspace

import ai.terok.jetbrains.TerokCliService
import ai.terok.jetbrains.TerokProjectService
import ai.terok.jetbrains.TerokTask
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

private val LOG = logger<WorkspaceSwapper>()

/** Swaps content root between local workspace and agent's workspace-dangerous. */
object WorkspaceSwapper {
    private var savedOriginalRoot: String? = null

    fun openWorkspace(project: Project, task: TerokTask) {
        val wsPath = TerokCliService.getInstance().workspacePath(task.projectId, task.id) ?: return
        swapContentRoot(project, wsPath)
        with(TerokProjectService.getInstance(project)) { attachedTaskId = task.id; activeProjectId = task.projectId }
        cleanVcs(project)
    }

    fun returnToLocal(project: Project) {
        val orig = savedOriginalRoot ?: return
        swapContentRoot(project, Path.of(orig))
        with(TerokProjectService.getInstance(project)) { attachedTaskId = null }
        savedOriginalRoot = null
    }

    fun isAttached(project: Project) = TerokProjectService.getInstance(project).attachedTaskId != null

    private fun swapContentRoot(project: Project, newRoot: Path) {
        val module = ModuleManager.getInstance(project).modules.firstOrNull() ?: return
        val rootModel = ModuleRootManager.getInstance(module)
        if (savedOriginalRoot == null) savedOriginalRoot = rootModel.contentRoots.firstOrNull()?.path
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(newRoot) ?: return
        WriteAction.run<Exception> {
            val m = rootModel.modifiableModel
            m.contentEntries.forEach { m.removeContentEntry(it) }
            m.addContentEntry(vf)
            m.commit()
        }
        LOG.info("Content root → $newRoot")
    }

    private fun cleanVcs(project: Project) {
        val mgr = ProjectLevelVcsManager.getInstance(project)
        val cleaned = mgr.directoryMappings.filter { !it.directory.contains("workspace-dangerous") }
        if (cleaned.size < mgr.directoryMappings.size) mgr.directoryMappings = cleaned
    }
}
