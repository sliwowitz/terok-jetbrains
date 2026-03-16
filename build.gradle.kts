// SPDX-FileCopyrightText: 2026 Jiri Vyskocil
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
        instrumentationTools()
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    patchPluginXml {
        sinceBuild.set("251")
        untilBuild.set("")
    }
}
