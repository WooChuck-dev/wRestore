import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.pumpkinmc.plugin") version "0.1.2"
}

group = "dev.woochuck.restore"
version = "0.1.0-beta.1"

repositories {
    mavenLocal {
        content { includeGroupAndSubgroups("org.pumpkinmc") }
    }
    mavenCentral()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        wasmWasiMain.dependencies { implementation(project(":domain")) }
    }
}

pumpkin {
    apiVersion.set("0.1.2")
    pluginClass.set("dev.woochuck.restore.WRestorePlugin")
}

// ---- Final component = Kotlin plugin + prebuilt SQLite component (see components/sqlite) ----
// The pumpkin Gradle plugin's own assemble cannot add the extra `wrestore:sqlite/db` import, so it is replaced here.
// Same pipeline as the plugin (wasm-tools embed -> new --adapt), plus compose with SQLite and validate.
// shortcut: uses the deprecated `wasm-tools compose`; switch to `wac` when it is worth adding a tool download.
val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
val wasmTools = layout.buildDirectory.file("tools/wasm-tools/1.258.0/bin/wasm-tools$exe") // installed by the pumpkin plugin
val apiGenerated = layout.buildDirectory.dir("generated/pumpkin-api")
val pluginWit = layout.buildDirectory.dir("plugin-wit")
val coreWasm = layout.buildDirectory.file("compileSync/wasmWasi/main/productionExecutable/optimized/wRestore.wasm")
val sqliteDir = layout.projectDirectory.dir("components/sqlite")
val stage = layout.buildDirectory.dir("component")
val finalWasm = layout.buildDirectory.file("wRestore.wasm")

val preparePluginWit = tasks.register<Sync>("preparePluginWit") {
    dependsOn("unpackPumpkinApiSources")
    from(apiGenerated.map { it.dir("wit/v0.1") }) {
        filter { if (it.startsWith("world plugin {")) it + "\n    import wrestore:sqlite/db@0.1.0;" else it }
    }
    from(sqliteDir.dir("wit")) { into("deps/sqlite") }
    into(pluginWit)
}

val verifySqliteComponent = tasks.register("verifySqliteComponent") {
    val bin = sqliteDir.file("sqlite-comp.wasm")
    val sha = sqliteDir.file("sqlite-comp.wasm.sha256")
    inputs.files(bin, sha)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256").digest(bin.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == sha.asFile.readText().trim()) { "components/sqlite/sqlite-comp.wasm does not match its .sha256 (run components/sqlite/rebuild.sh)" }
    }
}

val embedComponent = tasks.register<Exec>("embedComponent") {
    dependsOn(preparePluginWit, "installWasmTools", "compileProductionExecutableKotlinWasmWasiOptimize")
    inputs.dir(pluginWit); inputs.file(coreWasm); outputs.file(stage.map { it.file("embedded.wasm") })
    doFirst {
        stage.get().asFile.mkdirs()
        commandLine(wasmTools.get().asFile.path, "component", "embed", pluginWit.get().asFile.path, coreWasm.get().asFile.path,
            "-o", stage.get().file("embedded.wasm").asFile.path)
    }
}

val componentNew = tasks.register<Exec>("componentNew") {
    dependsOn(embedComponent)
    inputs.file(stage.map { it.file("embedded.wasm") }); outputs.file(stage.map { it.file("plugin-only.wasm") })
    doFirst {
        commandLine(wasmTools.get().asFile.path, "component", "new", stage.get().file("embedded.wasm").asFile.path,
            "--adapt", "wasi_snapshot_preview1=" + apiGenerated.get().file("wasi/wasi_snapshot_preview1.reactor.wasm").asFile.path,
            "-o", stage.get().file("plugin-only.wasm").asFile.path)
    }
}

val composeSqlite = tasks.register<Exec>("composeSqlite") {
    dependsOn(componentNew, verifySqliteComponent)
    inputs.file(stage.map { it.file("plugin-only.wasm") }); inputs.file(sqliteDir.file("sqlite-comp.wasm")); outputs.file(finalWasm)
    doFirst {
        commandLine(wasmTools.get().asFile.path, "compose", stage.get().file("plugin-only.wasm").asFile.path,
            "-d", sqliteDir.file("sqlite-comp.wasm").asFile.path, "-o", finalWasm.get().asFile.path)
    }
}

val validateComponent = tasks.register<Exec>("validateComponent") {
    dependsOn(composeSqlite)
    inputs.file(finalWasm)
    doFirst { commandLine(wasmTools.get().asFile.path, "validate", finalWasm.get().asFile.path) }
}

// Replace the plugin's own assemble/validate with the pipeline above.
tasks.matching { it.name == "assemblePluginRelease" || it.name == "validatePluginRelease" }.configureEach { enabled = false }
tasks.named("assemble") { dependsOn(validateComponent) }
