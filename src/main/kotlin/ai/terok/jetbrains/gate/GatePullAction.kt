// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains.gate

import ai.terok.jetbrains.TerokCliService
import ai.terok.jetbrains.TerokTask
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

private val LOG = logger<GatePullAction>()

/** Container pushes to gate → IDE fetches → creates branch. */
object GatePullAction {
    fun pull(project: Project, task: TerokTask) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Pulling from ${task.name}...") {
            override fun run(indicator: ProgressIndicator) {
                val cli = TerokCliService.getInstance()
                indicator.text = "Exporting from container..."
                cli.execInTask(task, "bash", "-c",
                    "cd /workspace && git add -A && git diff-index --quiet HEAD || " +
                        "git commit -m 'terok: export' && git push origin HEAD:refs/heads/terok/export/${task.id}")

                val token = cli.gateToken(task.projectId, task.id) ?: return
                val basePath = project.basePath ?: return
                indicator.text = "Fetching from gate..."
                git(basePath, "remote", "add", "terok-gate", "http://$token@localhost:${cli.gatePort()}/${task.projectId}.git")
                git(basePath, "fetch", "terok-gate", "refs/heads/terok/export/${task.id}")
                git(basePath, "remote", "remove", "terok-gate")
                git(basePath, "branch", "-f", "terok/${task.name}", "FETCH_HEAD")
                LOG.info("Agent changes on branch 'terok/${task.name}'")
                // TODO: show diff viewer + notification
            }
        })
    }

    private fun git(dir: String, vararg args: String) = try {
        CapturingProcessHandler(GeneralCommandLine("git", *args).withWorkDirectory(dir))
            .runProcess(15_000).let { if (it.exitCode == 0) it.stdout.trim() else null }
    } catch (_: Exception) { null }
}
