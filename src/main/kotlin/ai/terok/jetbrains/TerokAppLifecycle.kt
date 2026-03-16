// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

package ai.terok.jetbrains

import ai.terok.jetbrains.chat.AcpBridgeManager
import com.intellij.ide.AppLifecycleListener

/** Early startup: register ACP agent before AI Assistant reads acp.json. */
class TerokAppLifecycle : AppLifecycleListener {
    override fun appStarted() = AcpBridgeManager.ensureRegistered()
}
