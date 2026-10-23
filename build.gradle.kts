plugins {
    id("java")
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "dev.sergeylord"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

application {
    mainClass.set("dev.sergeylord.Main")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dconsole.encoding=UTF-8"
    )
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.graphics")
}

dependencies {
    implementation("com.microsoft.playwright:playwright:1.49.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("playwrightInstall") {
    group = "playwright"
    description = "Download Playwright browser binaries"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
}

/**
 * Windows app-image с ArbitrParser.exe (jpackage + JDK 21 runtime).
 *
 *   gradlew packageExe
 *
 * Результат: build/jpackage/ArbitrParser/ArbitrParser.exe
 */
tasks.register("packageExe") {
    group = "distribution"
    description = "Build Windows ArbitrParser.exe via jpackage"
    dependsOn("installDist")

    doLast {
        // Берём JDK из toolchain компиляции (не из PATH — там может быть Java 17)
        val jdkHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile

        val jpackage = File(jdkHome, "bin/jpackage.exe")
        if (!jpackage.exists()) {
            throw GradleException("jpackage not found at ${jpackage.absolutePath}")
        }

        val distLib = layout.buildDirectory.dir("install/ArbitrParser/lib").get().asFile
        val mainJar = distLib.listFiles()?.firstOrNull {
            it.name.startsWith("ArbitrParser-") && it.name.endsWith(".jar")
        } ?: throw GradleException("Main jar not found in ${distLib.absolutePath}")

        val outDir = layout.buildDirectory.dir("jpackage").get().asFile
        outDir.mkdirs()
        val appImage = File(outDir, "ArbitrParser")
        if (appImage.exists()) {
            appImage.deleteRecursively()
        }

        val iconFile = file("packaging/app.ico")
        if (!iconFile.exists()) {
            throw GradleException("App icon not found: ${iconFile.absolutePath}")
        }

        exec {
            commandLine(
                jpackage.absolutePath,
                "--type", "app-image",
                "--name", "ArbitrParser",
                "--app-version", "1.0.0",
                "--input", distLib.absolutePath,
                "--main-jar", mainJar.name,
                "--main-class", "dev.sergeylord.Main",
                "--dest", outDir.absolutePath,
                "--icon", iconFile.absolutePath,
                "--java-options", "-Dfile.encoding=UTF-8",
                "--java-options", "-Dconsole.encoding=UTF-8"
            )
        }

        val exe = File(appImage, "ArbitrParser.exe")
        println()
        if (exe.exists()) {
            println("=== EXE ready ===")
            println(exe.absolutePath)
        } else {
            println("jpackage done, check: ${outDir.absolutePath}")
        }
    }
}
