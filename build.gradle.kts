import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject

plugins {
    id("java")
    id("application")
    id("com.gradleup.shadow") version "9.6.1"
    id("org.cyclonedx.bom") version "3.4.1"
}

group = "com.redactado"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jline:jline:3.27.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Reference modules built against the public API (see examples/*/README.md); not part of the node itself.
sourceSets {
    create("examples") {
        java.srcDir("examples/static-hosting/src/main/java")
        compileClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
    }
}

tasks.register<Jar>("staticHostingJar") {
    group = "examples"
    description = "Builds the static hosting example module (examples/static-hosting) as build/libs/static-hosting.jar."
    archiveFileName.set("static-hosting.jar")
    from(sourceSets["examples"].output)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("com.redactado.noray.NorayBootstrap")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// `./gradlew installDist` leaves a runnable tree in build/install/noray (bin/noray, lib/*.jar); the service unit and the
// example configuration travel with it.
distributions {
    main {
        contents {
            from("dist") { into("etc") }
        }
    }
}

// Reproducible archives: the same sources give byte-identical jars, zips and tars, so a release can be rebuilt and
// compared by anyone (no timestamps, fixed entry order, fixed permissions).
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    filePermissions { unix("0644") }
    dirPermissions { unix("0755") }
}
// the launcher scripts must stay executable inside the distribution archives
distributions.main {
    contents {
        filesMatching("bin/*") { permissions { unix("0755") } }
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.redactado.noray.NorayBootstrap",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

tasks.shadowJar {
    manifest {
        attributes(
            "Main-Class" to "com.redactado.noray.NorayBootstrap",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
    // Strip signatures to prevent JCE SecurityExceptions
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // long chaos runs are opt-in: ./gradlew test -Dnoray.chaos.rounds=200
    System.getProperties().stringPropertyNames().filter { it.startsWith("noray.") }.forEach {
        systemProperty(it, System.getProperty(it))
    }
}

// ── release packaging ──────────────────────────────────────────────────────────────────────────────────────────────

// Software bill of materials (CycloneDX JSON) of what ships: the runtime classpath only.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    jsonOutput.set(layout.buildDirectory.file("reports/sbom/noray-bom.json"))
    xmlOutput.unsetConvention()
    includeBomSerialNumber.set(false) // a random UUID would make every build differ
}

/**
 * A self-contained runtime: a jlink image holding only the JDK modules the fat jar needs (found with jdeps), the jar,
 * and a launcher. Nothing has to be installed on the target besides glibc.
 */
abstract class JlinkImage @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile abstract val fatJar: RegularFileProperty
    @get:InputDirectory abstract val etcDir: DirectoryProperty
    @get:Input abstract val javaHome: Property<String>
    @get:Input abstract val extraModules: ListProperty<String>
    @get:OutputDirectory abstract val imageDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = imageDir.get().asFile
        out.deleteRecursively()
        val bin = "${javaHome.get()}/bin"
        val deps = ByteArrayOutputStream()
        exec.exec {
            commandLine("$bin/jdeps", "--ignore-missing-deps", "--multi-release", "24", "--print-module-deps",
                fatJar.get().asFile.absolutePath)
            standardOutput = deps
        }
        val modules = (deps.toString().trim().split(",") + extraModules.get()).filter { it.isNotBlank() }.toSortedSet()
        exec.exec {
            commandLine("$bin/jlink", "--add-modules", modules.joinToString(","), "--strip-debug", "--no-header-files",
                "--no-man-pages", "--compress", "zip-6", "--output", File(out, "runtime").absolutePath)
        }
        File(out, "lib").mkdirs()
        fatJar.get().asFile.copyTo(File(out, "lib/noray.jar"))
        etcDir.get().asFile.copyRecursively(File(out, "etc"))
        val launcher = File(out, "bin/noray")
        launcher.parentFile.mkdirs()
        launcher.writeText(
            """
            |#!/bin/sh
            |# Noray launcher (self-contained runtime). JAVA_OPTS adds JVM options, e.g. JAVA_OPTS=-Xmx4g
            |HERE=${'$'}(cd "${'$'}(dirname "${'$'}0")/.." && pwd)
            |exec "${'$'}HERE/runtime/bin/java" --enable-native-access=ALL-UNNAMED ${'$'}JAVA_OPTS -jar "${'$'}HERE/lib/noray.jar" "${'$'}@"
            |""".trimMargin()
        )
        launcher.setExecutable(true, false)
        logger.lifecycle("jlink image with modules $modules -> $out")
    }
}

val jlinkImage = tasks.register<JlinkImage>("jlinkImage") {
    group = "distribution"
    description = "Builds a self-contained runtime image in build/image (jlink + fat jar + launcher)."
    fatJar.set(tasks.shadowJar.flatMap { it.archiveFile })
    etcDir.set(layout.projectDirectory.dir("dist"))
    javaHome.set(providers.systemProperty("java.home"))
    // jdeps cannot see it: JLine handles terminal signals through sun.misc (jdk.unsupported)
    extraModules.set(listOf("jdk.unsupported"))
    imageDir.set(layout.buildDirectory.dir("image/noray"))
}

val imageTar = tasks.register<Tar>("imageTar") {
    group = "distribution"
    description = "Packs the jlink image as build/release/noray-<version>-linux-<arch>.tar.gz."
    dependsOn(jlinkImage)
    compression = Compression.GZIP
    archiveFileName.set("noray-${project.version}-linux-${System.getProperty("os.arch")}.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    into("noray") {
        from(jlinkImage.flatMap { it.imageDir }) {
            filesMatching(listOf("bin/*", "runtime/bin/*", "runtime/lib/jspawnhelper", "runtime/lib/jexec")) {
                permissions { unix("0755") }
            }
        }
    }
}

/** Everything a release needs in one directory, with a SHA256SUMS file that `sha256sum -c` checks. */
tasks.register("release") {
    group = "distribution"
    description = "Builds jars, distributions, the runtime image and the SBOM into build/release with SHA256SUMS."
    dependsOn(tasks.shadowJar, tasks.distZip, tasks.distTar, imageTar, "cyclonedxDirectBom")
    val outDir = layout.buildDirectory.dir("release")
    val inputs = files(
        tasks.shadowJar.flatMap { it.archiveFile },
        tasks.distZip.flatMap { it.archiveFile },
        tasks.distTar.flatMap { it.archiveFile },
        layout.buildDirectory.file("reports/sbom/noray-bom.json"),
    )
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        inputs.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        // the SBOM's timestamp is the one varying byte; pin it the reproducible-builds way when asked to
        System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()?.let { epoch ->
            val bom = File(dir, "noray-bom.json")
            val stamp = Instant.ofEpochSecond(epoch).toString()
            bom.writeText(bom.readText().replaceFirst(Regex("\"timestamp\"\\s*:\\s*\"[^\"]*\""), "\"timestamp\" : \"$stamp\""))
        }
        val sums = dir.listFiles()!!.filter { it.isFile && it.name != "SHA256SUMS" }.sortedBy { it.name }.joinToString("") { f ->
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            md.digest().joinToString("") { "%02x".format(it) } + "  " + f.name + "\n"
        }
        File(dir, "SHA256SUMS").writeText(sums)
        logger.lifecycle("release artifacts in $dir:\n$sums")
    }
}
