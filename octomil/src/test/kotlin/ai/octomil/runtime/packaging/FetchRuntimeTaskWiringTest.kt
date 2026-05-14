package ai.octomil.runtime.packaging

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Gradle TestKit integration tests for the fetchRuntime task.
 *
 * These tests stand up a minimal project and verify:
 *   - fetchRuntime appears in the task graph before preBuild
 *   - skipFetch=true produces BUILD SUCCESSFUL with no network access
 *   - The sentinel prevents re-runs when already up to date
 *
 * We use a minimal synthetic project (not the real octomil project) to
 * keep these tests fast and offline-safe.
 *
 * These tests require the Gradle daemon and will be slower than the pure
 * unit tests above. They run as part of :octomil:testDebugUnitTest.
 *
 * Note: Gradle configuration cache is NOT enabled in the TestKit project
 * because the classpath injection required by GradleRunner is incompatible
 * with configuration cache's input tracking.
 */
class FetchRuntimeTaskWiringTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    /**
     * Verify that fetchRuntime is wired as a dependency of preBuild.
     *
     * We use --dry-run so no actual HTTP calls are made. The task should
     * appear in the task graph BEFORE :preBuild.
     */
    @Test
    fun `fetchRuntime appears in dependency graph before preBuild (skipFetch=true)`() {
        setupMinimalProject(skipFetch = true)

        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withArguments(":lib:preBuild", "--dry-run", "-PoctomilRuntime.skipFetch=true", "--no-configuration-cache")
            .build()

        // --dry-run reports task names in stdout; result.tasks() may be empty in dry-run mode.
        // Use the text output instead.
        val output = result.output
        val fetchIdx  = output.indexOf("fetchRuntime")
        val preBuildIdx = output.indexOf("preBuild")

        assertTrue("fetchRuntime task should appear in the dry-run output (got: $output)", fetchIdx >= 0)
        assertTrue("preBuild task should appear in the dry-run output (got: $output)", preBuildIdx >= 0)
        assertTrue(
            "fetchRuntime ($fetchIdx) should appear before preBuild ($preBuildIdx) in output",
            fetchIdx < preBuildIdx
        )
    }

    /**
     * Verify that skipFetch=true causes fetchRuntime to succeed without
     * making any network requests (BUILD SUCCESSFUL, task outcome SUCCESS).
     */
    @Test
    fun `fetchRuntime skips successfully with skipFetch=true`() {
        setupMinimalProject(skipFetch = true)

        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withArguments(":lib:fetchRuntime", "-PoctomilRuntime.skipFetch=true", "--no-configuration-cache")
            .build()

        val task = result.task(":lib:fetchRuntime")
        assertNotNull("fetchRuntime task should have executed", task)
        assertEquals(TaskOutcome.SUCCESS, task!!.outcome)
        assertTrue(result.output.contains("skipFetch=true"))
    }

    /**
     * Verify that the sentinel file prevents a re-run when the .so files
     * and sentinel are already in place.
     */
    @Test
    fun `fetchRuntime reports cache hit when sentinel and so files present`() {
        setupMinimalProject(skipFetch = false)

        // Pre-populate the cache + jniLibs to simulate a previous successful run
        val version = "v0.1.5"
        val flavor  = "chat"
        val abi     = "arm64-v8a"

        val cacheDir = File(projectDir.root, ".gradle/octomil-runtime/$version/$flavor")
        cacheDir.mkdirs()
        File(cacheDir, ".extracted-ok").writeText("$version:$flavor\n")

        val jniLibsDir = File(projectDir.root, "lib/src/main/jniLibs/$abi")
        jniLibsDir.mkdirs()
        File(jniLibsDir, "liboctomil-runtime.so").writeBytes(ByteArray(4) { 0x7F.toByte() })
        File(jniLibsDir, "libc++_shared.so").writeBytes(ByteArray(4) { 0x7F.toByte() })

        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withArguments(":lib:fetchRuntime", "--no-configuration-cache")
            .build()

        val task = result.task(":lib:fetchRuntime")
        assertEquals(TaskOutcome.SUCCESS, task!!.outcome)
        assertTrue("Output should mention cache hit", result.output.contains("cache hit"))
    }

    // ── Minimal project setup ─────────────────────────────────────────────────

    /**
     * Write a minimal Android library project that includes only the
     * fetchRuntime task wiring, backed by a stub android {} block.
     * We do NOT use the full octomil build.gradle.kts to keep this fast.
     */
    private fun setupMinimalProject(skipFetch: Boolean) {
        // settings.gradle.kts
        File(projectDir.root, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "test-project"
            include(":lib")
            """.trimIndent()
        )

        // octomil-runtime.properties at root
        File(projectDir.root, "octomil-runtime.properties").writeText(
            """
            octomilRuntime.version=v0.1.5
            octomilRuntime.flavor=chat
            octomilRuntime.abi=arm64-v8a
            octomilRuntime.skipFetch=${if (skipFetch) "true" else "false"}
            """.trimIndent()
        )

        // local.properties — required by AGP
        File(projectDir.root, "local.properties").writeText(
            "sdk.dir=${System.getenv("ANDROID_HOME") ?: "/usr/local/lib/android/sdk"}\n"
        )

        // lib/ module directory
        val libDir = File(projectDir.root, "lib")
        libDir.mkdirs()
        File(libDir, "src/main").mkdirs()
        File(libDir, "src/main/AndroidManifest.xml").apply {
            parentFile?.mkdirs()
            writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>""")
        }

        // Minimal build.gradle.kts for the lib module — only wires fetchRuntime
        // without pulling in the full AGP / Kotlin toolchain (for speed).
        // We stub preBuild as a lifecycle task so wiring is testable.
        File(libDir, "build.gradle.kts").writeText(
            """
            import org.gradle.api.DefaultTask
            import org.gradle.api.tasks.Input
            import org.gradle.api.tasks.TaskAction
            import org.gradle.api.GradleException
            import java.io.File
            import java.util.Properties

            buildscript {
                repositories { mavenCentral() }
                dependencies { classpath("org.json:json:20251224") }
            }

            abstract class FetchRuntimeTask : DefaultTask() {
                @get:Input abstract var runtimeVersion: String
                @get:Input abstract var runtimeFlavor: String
                @get:Input abstract var runtimeAbi: String
                @get:Input abstract var skipFetch: Boolean

                @TaskAction
                fun fetch() {
                    if (skipFetch) {
                        logger.lifecycle("fetchRuntime: skipFetch=true — skipping GitHub download")
                        return
                    }
                    val cacheDir = File(project.rootProject.projectDir, ".gradle/octomil-runtime/${'$'}runtimeVersion/${'$'}runtimeFlavor")
                    val jniLibsDir = File(project.projectDir, "src/main/jniLibs/${'$'}runtimeAbi")
                    val sentinelFile = File(cacheDir, ".extracted-ok")
                    val sentinelContent = "${'$'}runtimeVersion:${'$'}runtimeFlavor"
                    val libSo = File(jniLibsDir, "liboctomil-runtime.so")
                    val cppSo = File(jniLibsDir, "libc++_shared.so")
                    if (sentinelFile.exists() && sentinelFile.readText().trim() == sentinelContent && libSo.exists() && cppSo.exists()) {
                        logger.lifecycle("fetchRuntime: cache hit — ${'$'}runtimeVersion/${'$'}runtimeFlavor already staged")
                        return
                    }
                    throw GradleException("fetchRuntime: no GitHub token available (expected in test: use skipFetch=true or pre-populate cache)")
                }
            }

            fun loadRuntimeProperties(): Properties {
                val props = Properties()
                val propsFile = rootProject.file("octomil-runtime.properties")
                if (propsFile.exists()) propsFile.inputStream().use<java.io.InputStream, Unit> { props.load(it) }
                return props
            }

            val runtimeProps = loadRuntimeProperties()

            fun gradleOrPropString(key: String, default: String): String =
                providers.gradleProperty(key).orNull ?: runtimeProps.getProperty(key, default)

            fun gradleOrPropBool(key: String, default: Boolean): Boolean =
                providers.gradleProperty(key).orNull?.toBoolean()
                    ?: runtimeProps.getProperty(key)?.toBoolean() ?: default

            val fetchRuntime = tasks.register<FetchRuntimeTask>("fetchRuntime") {
                group = "octomil"
                description = "Download liboctomil-runtime.so from GitHub Releases and stage into jniLibs"
                runtimeVersion = gradleOrPropString("octomilRuntime.version", "v0.1.5")
                runtimeFlavor  = gradleOrPropString("octomilRuntime.flavor", "chat")
                runtimeAbi     = gradleOrPropString("octomilRuntime.abi", "arm64-v8a")
                skipFetch      = gradleOrPropBool("octomilRuntime.skipFetch", false)
            }

            // Stub preBuild as a lifecycle task
            val preBuild = tasks.register("preBuild") {
                group = "build"
                description = "Stub preBuild lifecycle task for wiring tests"
                dependsOn(fetchRuntime)
            }
            """.trimIndent()
        )
    }
}
