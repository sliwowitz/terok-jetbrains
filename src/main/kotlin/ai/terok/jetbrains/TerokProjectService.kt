// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/** Per-project state: active terok project and attached task. */
@Service(Service.Level.PROJECT)
class TerokProjectService(private val project: Project) {
    var activeProjectId: String? = null
    var attachedTaskId: String? = null
    companion object {
        fun getInstance(project: Project): TerokProjectService = project.getService(TerokProjectService::class.java)
    }
}
