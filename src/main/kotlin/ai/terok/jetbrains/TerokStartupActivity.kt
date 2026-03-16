// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains

import ai.terok.jetbrains.chat.AcpBridgeManager
import ai.terok.jetbrains.serviceview.TerokServiceContributor
import com.intellij.execution.services.ServiceEventListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

private val LOG = logger<TerokStartupActivity>()

class TerokStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        cleanWorkspaceVcsMappings(project)
        AcpBridgeManager.pokeAcpFile()
        // Auto-refresh Services tree every 10s. TODO: replace with file-watch.
        AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (!project.isDisposed)
                project.messageBus.syncPublisher(ServiceEventListener.TOPIC)
                    .handle(ServiceEventListener.ServiceEvent.createResetEvent(TerokServiceContributor::class.java))
        }, 10, 10, TimeUnit.SECONDS)
    }

    private fun cleanWorkspaceVcsMappings(project: Project) {
        val mgr = ProjectLevelVcsManager.getInstance(project)
        val cleaned = mgr.directoryMappings.filter { !it.directory.contains("workspace-dangerous") }
        if (cleaned.size < mgr.directoryMappings.size) mgr.directoryMappings = cleaned
    }
}
