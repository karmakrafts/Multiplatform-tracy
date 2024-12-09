import de.undercouch.gradle.tasks.download.Download
import org.gradle.internal.extensions.stdlib.capitalized
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.createDirectories

/*
 * Copyright 2024 Karma Krafts & associates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.downloadTask)
    `maven-publish`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val buildDirectory = layout.buildDirectory.get().asFile
val ensureBuildDirectory: Task = tasks.create("ensureBuildDirectory") {
    doLast { buildDirectory.createDirectories() }
    onlyIf { !buildDirectory.exists() }
}

val downloadTracyHeaders: Exec = tasks.create<Exec>("downloadTracyHeaders") {
    val tracyVersion = libs.versions.tracy.get()
    
    group = "tracyHeaders"
    dependsOn(ensureBuildDirectory)
    workingDir = buildDirectory
    commandLine("git", "clone", "--branch", tracyVersion, "--single-branch", "https://github.com/wolfpld/tracy", "tracy")
    onlyIf { !buildDirectory.resolve("tracy").exists() }
}

val updateTracyHeaders: Exec = tasks.create<Exec>("updateTracyHeaders") {
    group = "tracyHeaders"
    dependsOn(downloadTracyHeaders)
    workingDir = buildDirectory.resolve("tracy")
    commandLine("git", "pull", "--force")
    onlyIf { buildDirectory.resolve("tracy").exists() }
}


fun downloadSdlBinariesTask(platform: String, arch: String): Download =
    tasks.create<Download>("downloadTracyBinaries${platform.capitalized()}${arch.capitalized()}") {
        val fileName = "build-$platform-client-$arch-debug.zip"
        val destPath = buildDirectory.resolve("tracy-binaries").resolve(fileName)
        
        group = "tracyBinaries"
        dependsOn(ensureBuildDirectory)
        src("https://git.karmakrafts.dev/api/v4/projects/345/packages/generic/build/${libs.versions.tracy.get()}/$fileName")
        dest(destPath)
        overwrite(true)
        onlyIf { !destPath.exists() }
    }

val downloadSdlBinariesWindowsX64: Download = downloadSdlBinariesTask("windows", "x64")
val downloadSdlBinariesLinuxX64: Download = downloadSdlBinariesTask("linux", "x64")
val downloadSdlBinariesLinuxArm64: Download = downloadSdlBinariesTask("linux", "arm64")
val downloadSdlBinariesMacosX64: Download = downloadSdlBinariesTask("macos", "x64")
val downloadSdlBinariesMacosArm64: Download = downloadSdlBinariesTask("macos", "arm64")


fun extractTracyBinariesTask(platform: String, arch: String): Copy =
    tasks.create<Copy>("extractTracyBinaries${platform.capitalized()}${arch.capitalized()}") {
        val downloadTaskName = "downloadTracyBinaries${platform.capitalized()}${arch.capitalized()}"
        val destPath = buildDirectory.resolve("tracy-binaries").resolve("$platform-$arch")
        
        group = "tracyBinaries"
        dependsOn(downloadTaskName)
        from(zipTree(buildDirectory.resolve("tracy-binaries").resolve("build-$platform-client-$arch-debug.zip")))
        into(destPath)
        onlyIf { !destPath.exists() }
    }

val extractTracyBinariesTaskWindowsX64: Copy = extractTracyBinariesTask("windows", "x64")
val extractTracyBinariesTaskLinuxX64: Copy = extractTracyBinariesTask("linux", "x64")
val extractTracyBinariesTaskLinuxArm64: Copy = extractTracyBinariesTask("linux", "arm64")
val extractTracyBinariesTaskMacosX64: Copy = extractTracyBinariesTask("macos", "x64")
val extractTracyBinariesTaskMacosArm64: Copy = extractTracyBinariesTask("macos", "arm64")

val extractTracyBinaries: Task = tasks.create("extractTracyBinaries") {
    group = "tracyBinaries"
    dependsOn(extractTracyBinariesTaskWindowsX64)
    dependsOn(extractTracyBinariesTaskLinuxX64)
    dependsOn(extractTracyBinariesTaskLinuxArm64)
    dependsOn(extractTracyBinariesTaskMacosX64)
    dependsOn(extractTracyBinariesTaskMacosArm64)
}


kotlin {
    listOf(
        mingwX64(), linuxX64(), linuxArm64(), macosX64(), macosArm64()
    ).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                val tracy by creating {
                    defFile("src/nativeInterop/cinterop/tracy.def")
                    tasks.getByName(interopProcessingTaskName) {
                        dependsOn(extractTracyBinaries, updateTracyHeaders)
                    }
                }
            }
        }
    }
    
    linuxX64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    
    applyDefaultHierarchyTemplate()
    
    sourceSets {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}

val dokkaJar by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaHtml)
    from(tasks.dokkaHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

tasks {
    dokkaHtml {
        dokkaSourceSets.create("main") {
            reportUndocumented = false
            jdkVersion = java.toolchain.languageVersion.get().asInt()
            noAndroidSdkLink = true
            externalDocumentationLink("https://docs.karmakrafts.dev/${rootProject.name}")
        }
    }
    System.getProperty("publishDocs.root")?.let { docsDir ->
        create<Copy>("publishDocs") {
            mustRunAfter(dokkaJar)
            from(zipTree(dokkaJar.get().outputs.files.first()))
            into(docsDir)
        }
    }
}

publishing {
    System.getenv("CI_API_V4_URL")?.let { apiUrl ->
        repositories {
            maven {
                url = uri("$apiUrl/projects/${System.getenv("CI_PROJECT_ID")}/packages/maven")
                name = "GitLab"
                credentials(HttpHeaderCredentials::class) {
                    name = "Job-Token"
                    value = System.getenv("CI_JOB_TOKEN")
                }
                authentication {
                    create("header", HttpHeaderAuthentication::class)
                }
            }
        }
    }
    publications.configureEach {
        if (this is MavenPublication) {
            artifact(dokkaJar)
            pom {
                name = project.name
                description = "Multiplatform bindings for Tracy on Linux, Windows and macOS."
                url = System.getenv("CI_PROJECT_URL")
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "cach30verfl0w"
                        name = "Cedric Hammes"
                        url = "https://git.karmakrafts.dev/cach30verfl0w"
                    }
                }
                scm {
                    url = this@pom.url
                }
            }
        }
    }
}
