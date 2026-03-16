// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains.chat

import ai.terok.jetbrains.TerokCliService
import ai.terok.jetbrains.TerokTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

private val LOG = logger<AcpBridgeManager>()
private val HOME = System.getProperty("user.home")
private val DATA_DIR = Path.of(HOME, ".local", "share", "terok-jetbrains")
private val ACP_JSON = Path.of(HOME, ".jetbrains", "acp.json")

/**
 * Single persistent "terok" ACP agent. Task switching via state file —
 * no acp.json rewrite or IDE restart needed per task change.
 */
object AcpBridgeManager {

    /** Register the "terok" agent in acp.json (idempotent, call early in startup). */
    fun ensureRegistered() {
        val acpFile = ACP_JSON.toFile()
        val bridgePath = deployBridgeScript()
        if (acpFile.exists() && "\"terok\"" in acpFile.readText()) return
        val stateFile = DATA_DIR.resolve("active-task")

        // Preserve existing non-terok entries
        val others = if (acpFile.exists()) extractNonTerokEntries(acpFile.readText()) else emptyList()
        val terok = """"terok": { "command": "python3", "args": ["${esc(bridgePath)}", "--state-file", "${esc(stateFile)}"] }"""
        val entries = (others + terok).joinToString(",\n    ")

        acpFile.parentFile.mkdirs()
        acpFile.writeText("""{ "default_mcp_settings": { "use_custom_mcp": true, "use_idea_mcp": false }, "agent_servers": { $entries } }""")
        LOG.info("Registered terok ACP agent in ${acpFile.path}")
    }

    /** Switch active chat task (just writes state file — bridge reads it on next session). */
    fun startChat(project: Project, task: TerokTask) {
        val cli = TerokCliService.getInstance()
        DATA_DIR.toFile().mkdirs()
        DATA_DIR.resolve("active-task").toFile().writeText(cli.containerName(task) + "\n")
        LOG.info("Chat target: ${task.displayName}")
    }

    /** Poke VFS + external touch so AI Assistant notices acp.json. */
    fun pokeAcpFile() {
        ApplicationManager.getApplication().invokeLater {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(ACP_JSON.toString())?.refresh(false, false)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(3000)
            runCatching { ProcessBuilder("touch", ACP_JSON.toString()).start().waitFor() }
            ApplicationManager.getApplication().invokeLater {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(ACP_JSON.toString())?.refresh(true, false)
            }
        }
    }

    private fun deployBridgeScript(): Path {
        DATA_DIR.toFile().mkdirs()
        val target = DATA_DIR.resolve("terok_acp_bridge.py")
        AcpBridgeManager::class.java.getResourceAsStream("/terok_acp_bridge.py")
            ?.bufferedReader()?.readText()?.let { target.toFile().writeText(it); target.toFile().setExecutable(true) }
        return target
    }

    private fun extractNonTerokEntries(json: String): List<String> {
        val block = """"agent_servers"\s*:\s*\{([\s\S]*)\}\s*\}""".toRegex().find(json)?.groupValues?.get(1) ?: return emptyList()
        return """"([^"]+)":\s*\{[^}]*\}""".toRegex().findAll(block)
            .filter { it.groupValues[1] != "terok" && !it.groupValues[1].startsWith("terok:") }
            .map { it.value }.toList()
    }

    private fun esc(p: Path) = p.toString().replace("\\", "\\\\").replace("\"", "\\\"")
}
