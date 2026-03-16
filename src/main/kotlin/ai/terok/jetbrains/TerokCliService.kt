// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path

private val LOG = logger<TerokCliService>()

data class TerokProject(val id: String, val configPath: Path)

data class TerokTask(
    val id: String, val projectId: String, val name: String,
    val mode: String?, var containerState: String? = null,
) {
    val isRunning get() = containerState == "running"
    val displayName get() = "#$id $name"
}

/** Wraps terokctl CLI calls. All container interaction goes through here. */
@Service(Service.Level.APP)
class TerokCliService {
    companion object {
        fun getInstance(): TerokCliService =
            ApplicationManager.getApplication().getService(TerokCliService::class.java)
    }

    fun run(vararg args: String, timeoutMs: Int = 10_000): String? = try {
        val r = CapturingProcessHandler(GeneralCommandLine(terokctl(), *args)).runProcess(timeoutMs)
        if (r.exitCode == 0) r.stdout.trim() else null.also { LOG.warn("terokctl ${args.toList()}: ${r.stderr}") }
    } catch (e: Exception) { null.also { LOG.warn("terokctl failed: ${e.message}") } }

    fun listProjects(): List<TerokProject> =
        configRoot().resolve("projects").toFile()
            .listFiles { f -> f.isDirectory && f.resolve("project.yml").isFile }
            ?.map { TerokProject(it.name, it.toPath()) }?.sortedBy { it.id } ?: emptyList()

    /** List tasks via `terokctl task list` (includes live container state). Falls back to YAML. */
    fun listTasks(projectId: String): List<TerokTask> {
        val output = run("task", "list", projectId, timeoutMs = 15_000)
        if (output != null) {
            val tasks = output.lines().filter { it.startsWith("-") }.mapNotNull { parseTaskLine(projectId, it) }
            if (tasks.isNotEmpty() || output.contains("No tasks found")) return tasks
        }
        return listTasksYaml(projectId)  // fallback
    }

    fun workspacePath(projectId: String, taskId: String): Path? {
        val ws = stateRoot().resolve("tasks/$projectId/$taskId/workspace-dangerous")
        return ws.takeIf { it.toFile().isDirectory }
    }

    fun containerName(task: TerokTask) = "${task.projectId}-${task.mode ?: "cli"}-${task.id}"

    // TODO: replace with `terokctl task exec`
    fun execInTask(task: TerokTask, vararg cmd: String, timeoutMs: Int = 30_000): String? = try {
        val r = CapturingProcessHandler(
            GeneralCommandLine("podman", "exec", "-i", containerName(task), *cmd)
        ).runProcess(timeoutMs)
        if (r.exitCode == 0) r.stdout.trim() else null
    } catch (_: Exception) { null }

    fun gatePort() = 9418  // TODO: read from terok config
    fun gateToken(projectId: String, taskId: String): String? = try {
        val text = stateRoot().resolve("gate/tokens.json").toFile().readText()
        """"(\w+)":\s*\{\s*"project":\s*"${Regex.escape(projectId)}",\s*"task":\s*"${Regex.escape(taskId)}"""
            .toRegex().find(text)?.groupValues?.get(1)
    } catch (_: Exception) { null }

    // --- internals ---

    /** Find terokctl — IDE may not have full PATH (pipx, etc.). TODO: proper resolution. */
    private fun terokctl(): String {
        val home = System.getProperty("user.home")
        return listOf("$home/.local/bin/terokctl", "/usr/local/bin/terokctl", "/usr/bin/terokctl")
            .firstOrNull { java.io.File(it).canExecute() } ?: "terokctl"
    }

    private fun configRoot(): Path =
        System.getenv("TEROK_CONFIG_DIR")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".config", "terok")

    private fun stateRoot(): Path =
        System.getenv("TEROK_STATE_DIR")?.let { Path.of(it) }
            ?: System.getenv("XDG_DATA_HOME")?.let { Path.of(it, "terok") }
            ?: Path.of(System.getProperty("user.home"), ".local", "share", "terok")

    private fun listTasksYaml(projectId: String): List<TerokTask> =
        stateRoot().resolve("projects/$projectId/tasks").toFile()
            .listFiles { f -> f.extension == "yml" }
            ?.mapNotNull { f ->
                val lines = f.readLines()
                fun field(n: String) = lines.firstOrNull { it.startsWith("$n:") }
                    ?.substringAfter(":")?.trim()?.removeSurrounding("\"")
                TerokTask(field("task_id") ?: f.nameWithoutExtension, projectId,
                    field("name") ?: f.nameWithoutExtension, field("mode"))
            }?.sortedBy { it.id } ?: emptyList()

    private fun parseTaskLine(projectId: String, line: String): TerokTask? {
        val rest = line.removePrefix("-").trim()
        val colon = rest.indexOf(':').takeIf { it >= 0 } ?: return null
        val id = rest.substring(0, colon).trim()
        var tail = rest.substring(colon + 1).trim()

        var mode: String? = null
        val bracket = tail.indexOf('[')
        if (bracket >= 0) {
            tail.substring(bracket + 1).removeSuffix("]").split(";").forEach { part ->
                val (k, v) = part.trim().split("=", limit = 2).takeIf { it.size == 2 } ?: return@forEach
                if (k.trim() == "mode") mode = v.trim()
            }
            tail = tail.substring(0, bracket).trim()
        }

        val statuses = listOf("running", "stopped", "created", "exited", "completed", "failed", "not found")
        var name = tail; var state: String? = null
        for (s in statuses) if (tail.endsWith(s, ignoreCase = true)) {
            state = s.lowercase()
            name = tail.dropLast(s.length).trimEnd { !it.isLetterOrDigit() && it != '-' && it != '_' }.trim()
            break
        }
        return TerokTask(id, projectId, name.ifBlank { id }, mode, state)
    }
}
