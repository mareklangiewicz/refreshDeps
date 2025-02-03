@file:Suppress("SpellCheckingInspection")
@file:OptIn(ExperimentalStdlibApi::class)

package de.fayard.refreshVersions

import de.fayard.refreshVersions.core.ModuleId
import de.fayard.refreshVersions.core.StabilityLevel
import de.fayard.refreshVersions.core.Version
import de.fayard.refreshVersions.internal.getArtifactNameToConstantMapping
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.*

class MyExperiments {

    // refreshDeps/plugins$ ./gradlew :refreshVersions:cleanTest (if needed - especially after "successful" or "disabled" run)
    // refreshDeps/plugins$ GENERATE_DEPS=true ./gradlew --info :refreshVersions:test --tests MyExperiments.generateDeps (takes around 13min)
    @EnabledIfEnvironmentVariable(named = "GENERATE_DEPS", matches = "true")
    @Test
    fun generateDeps() {

        val modules: List<ModuleId.Maven> = emptyList<ModuleId.Maven>() +
            getArtifactNameToConstantMapping().map { it.moduleId } +
            getAdditionalModules() +
            getLangiewiczModules()

        val input = getVersionCandidates(modules)

        val outputmap = mutableMapOf<String, Any>()


        for ((moduleId, versions) in input) {
            val path = moduleId.group
                .split(".")
                .map { it.myCamelCase().replace('-', '_') }

            val valName = moduleId.name
                .toLowerCase(Locale.US)
                .replace('-', '_')
                .replace('.', '_') // yes it happens: "io.arrow-kt.analysis.kotlin:io.arrow-kt.analysis.kotlin.gradle.plugin
                .run {
                    val prefix = path.last().toLowerCase() + '_'
                    if (matches(Regex("$prefix\\w.*"))) substring(prefix.length)
                    else this
                }

            outputmap.putDep(path, valName, moduleId.group to moduleId.name to versions.map { it.value })
        }

        testResources.resolve("objects-for-deps.txt")
            .writeText(buildString { appendDepTree(0, outputmap) })
    }

    private fun getVersionCandidates(
        modules: List<ModuleId.Maven>,
        reposUrls: List<String> = listOf(
            "https://repo.maven.apache.org/maven2/",
            "https://dl.google.com/dl/android/maven2/",
            "https://plugins.gradle.org/m2/",

            // "https://androidx.dev/storage/compose-compiler/repository/",
            // // FIXME: very wasteful, I should use it only for jetpack (androidx) compose compiler

            "https://maven.pkg.jetbrains.space/public/p/compose/dev/",
            // FIXME: very wasteful, I should use it only for compose mpp stuff (jb compose compiler too)
        )
    ): Map<ModuleId.Maven, List<Version>> = runBlocking {
        modules.associateWith { moduleId ->
            // delay(200) // FIXME: remove temporary slowdown (or not?)
            testutils.getVersionCandidates(
                httpClient = defaultHttpClient,
                mavenModuleId = moduleId,
                repoUrls = reposUrls,
                currentVersion = Version("")
            )
        }
    }
}


private val defaultHttpClient by lazy {
    OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor(logger = object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                println(message)
            }
        }).setLevel(HttpLoggingInterceptor.Level.BASIC))
        .build()
}



private val StabilityLevel.instability get() = when (this) {
    StabilityLevel.Stable -> 0
    StabilityLevel.ReleaseCandidate -> 100
    StabilityLevel.Milestone -> 120
    StabilityLevel.EarlyAccessProgram -> 140
    StabilityLevel.Beta -> 200
    StabilityLevel.Alpha -> 300
    StabilityLevel.Development -> 320
    StabilityLevel.Preview -> 400
    StabilityLevel.Snapshot -> 500
}

private typealias GroupAndName = Pair<String, String>
private typealias Ver = String
private typealias Vers = List<Ver>
private typealias Dep = Pair<GroupAndName, Vers>
private typealias DepTree = MutableMap<String, Any>

@Suppress("UNCHECKED_CAST")
private fun DepTree.putDep(
    path: List<String>,
    valName: String,
    dep: Dep
) {
    if (path.isEmpty()) put(valName, dep)
    else {
        val phead = path.first()
        val ptail = path.drop(1)
        if (phead !in this) put(phead, mutableMapOf<String, Any>())
        val map = get(phead)!! as DepTree
        map.putDep(ptail, valName, dep)
    }
}

private fun StringBuilder.appendDep(indent: Int, valname: String, dep: Dep) {
    val (groupAndName, vers) = dep
    val (group, name) = groupAndName

    var allVerCorrect = true
    // This flag is workaround for error when getting "com.google.android.gms:play-services-drive"
    //   I get some xml inside version.. have to filter it out and mark whole module as dangerous/deprecated
    //   (todo_someday: fix in upstream refreshVersions repo)

    val versStr = buildString {
        for (ver in vers) {
            if ('<' in ver || '>' in ver) {
                allVerCorrect = false
                println("Incorrect version found for module $name:")
                println(ver)
            }
            else append(" w \"$ver\"")
        }
    }
    if (!allVerCorrect) appendLine("@Deprecated(\"Warning: Some incorrect versions found (filtered out)\")".withIndent(indent))
    appendLine("val $valname = \"$group\" d \"$name\"$versStr".withIndent(indent))
}

@Suppress("UNCHECKED_CAST")
private fun StringBuilder.appendDepTree(indent: Int, tree: DepTree) {
    val entriesSorted = tree.entries.sortedBy { it.key }
    val (entriesForGroups, entriesForDeps) = entriesSorted.partition { it.key[0].isUpperCase() }
    for ((valname, dep) in entriesForDeps)
        appendDep(indent, valname, dep as Dep)
    for ((objectName, content) in entriesForGroups) {
        appendLine("object $objectName {".withIndent(indent))
        appendDepTree(indent + 2, content as DepTree)
        appendLine("}".withIndent(indent))
    }
}

private fun String.withIndent(indent: Int = 2) = " ".repeat(indent) + this

private fun CharSequence.myCamelCase(upUnknownFirst: Boolean = true): String {
    if (isEmpty()) return this.toString()
    val myWords = listOf("app", "layout", "content", "cursor", "adapter", "marek", "langiewicz", "text", "socket",
        "provider", "touch", "graph", "team", "android", "browser", "auto", "fill", "canary", "mockito", "jet", "brains",
        "kotlin", "spring", "framework", "assert", "java", "store", "data", "document", "file", "dynamic", "animation",
        "local", "global", "broadcast", "manager", "media", "router", "view", "share", "target", "wrappers", "type", "safe",
        "reactive", "jake", "wharton", "rx", "mock", "tuple", "abcd", "ktor", "git", "sqlite", "sql", "square", "unit", "kit")
        .sortedByDescending { it.length } // longer known words should be before shorter prefixes (sqlite before sql, etc.)
    for (myWord in myWords) if (startsWith(myWord, ignoreCase = true))
        return myWord.capitalize() + drop(myWord.length).myCamelCase(upUnknownFirst = true)
    return first().upIf(upUnknownFirst) + drop(1).myCamelCase(false)
}

private fun Char.upIf(up: Boolean) = if (up) toUpperCase() else this


// https://mvnrepository.com/artifact/org.jetbrains.kotlin
private val someJbKotlinStuff = listOf(
    // group here is always "org.jetbrains.kotlin"
    "kotlin-bom",
    "kotlin-stdlib",
    "kotlin-stdlib-jdk7",
    "kotlin-stdlib-jdk8",
    "kotlin-stdlib-js",
    "kotlin-stdlib-wasm",
    "kotlin-stdlib-wasm-js",
    "kotlin-stdlib-wasm-wasi",
    "kotlin-gradle-plugin",
    "kotlin-gradle-plugin-api",
    "kotlin-maven-plugin",
    "kotlin-compiler",
    "kotlin-compiler-embeddable",
    "kotlin-scripting-common",
    "kotlin-scripting-jvm",
    "kotlin-scripting-jvm-host",
    "kotlin-scripting-compiler",
    "kotlin-scripting-compiler-embeddable",
    "kotlin-script-runtime",
    "kotlin-allopen",
    "atomicfu",
    "kotlin-atomicfu-runtime",
    "kotlin-dom-api-compat",
    "kotlin-js-plain-objects",
    "kotlin-reflect",
    "kotlin-android-extensions-runtime",
    "kotlin-parcelize-runtime",
    "kotlin-test",
    "kotlin-test-junit",
    "kotlin-test-junit5",
    "kotlin-test-js",
    "kotlin-test-wasm-js",
    "kotlin-serialization",
).map { "org.jetbrains.kotlin" to it }


// https://mvnrepository.com/artifact/org.jetbrains.kotlinx
private val someJbKotlinXStuff = listOf(
    // group here is always "org.jetbrains.kotlinx"
    "kotlinx-coroutines-core",
    "kotlinx-coroutines-core-jvm",
    "kotlinx-coroutines-core-js",
    "kotlinx-coroutines-jdk8",
    "kotlinx-coroutines-reactive",
    "kotlinx-coroutines-reactor",
    "kotlinx-coroutines-rx2",
    "kotlinx-coroutines-rx3",
    "kotlinx-coroutines-debug",
    "kotlinx-coroutines-android",
    "kotlinx-coroutines-guava",
    "kotlinx-coroutines-swing",
    "kotlinx-coroutines-javafx",
    "kotlinx-coroutines-slf4j",
    "kotlinx-coroutines-test",
    "kotlinx-coroutines-test-jvm",
    "kotlinx-coroutines-test-js",
    "kotlinx-io-core",
    "kotlinx-io-core-jvm",
    "kotlinx-io-core-js",
    "kotlinx-io-core-wasm-js",
    "kotlinx-serialization-core",
    "kotlinx-serialization-core-jvm",
    "kotlinx-serialization-core-js",
    "kotlinx-serialization-core-wasm-js",
    "kotlinx-serialization-core-wasm-wasi",
    "kotlinx-serialization-json",
    "kotlinx-serialization-json-jvm",
    "kotlinx-serialization-json-js",
    "kotlinx-serialization-json-wasm-js",
    "kotlinx-serialization-protobuf-jvm",
    "kotlinx-serialization-protobuf-js",
    "kotlinx-datetime",
    "kotlinx-datetime-jvm",
    "kotlinx-datetime-js",
    "kotlinx-datetime-wasm-js",
    "atomicfu",
    "atomicfu-jvm",
    "atomicfu-js",
    "atomicfu-wasm-js",
    "dataframe",
    "kotlinx-html",
    "kotlinx-html-jvm",
    "kotlinx-html-js",
    "kotlinx-browser-wasm-js",
).map { "org.jetbrains.kotlinx" to it }

private val someJbMainComposeStuff = listOf(
    // group here is always JUST "org.jetbrains.compose"
    "compose-gradle-plugin",
    "compose-full",
    "preview-rpc",
    "compose-preview-runtime-desktop",
).map { "org.jetbrains.compose" to it }

// https://mvnrepository.com/artifact/org.jetbrains.compose
private val someJbComposeStuff = listOf(
    // group here is always PREFIXED with "org.jetbrains.compose"

    "compiler" to "compiler",
    "compiler" to "compiler-hosted",

    "runtime" to "runtime",
    "runtime" to "runtime-js",
    "runtime" to "runtime-wasm-js",
    "runtime" to "runtime-saveable",
    "runtime" to "runtime-saveable-js",
    "runtime" to "runtime-saveable-wasm-js",

    "ui" to "ui",
    "ui" to "ui-js",
    "ui" to "ui-wasm-js",
    "ui" to "ui-tooling",
    "ui" to "ui-tooling-preview",
    "ui" to "ui-unit",
    "ui" to "ui-unit-js",
    "ui" to "ui-unit-wasm-js",
    "ui" to "ui-graphics",
    "ui" to "ui-graphics-js",
    "ui" to "ui-graphics-wasm-js",
    "ui" to "ui-text",
    "ui" to "ui-text-js",
    "ui" to "ui-text-wasm-js",
    "ui" to "ui-geometry",
    "ui" to "ui-geometry-js",
    "ui" to "ui-geometry-wasm-js",
    "ui" to "ui-test",
    "ui" to "ui-test-js",
    "ui" to "ui-test-wasm-js",
    "ui" to "ui-test-junit4",

    "animation" to "animation",
    "animation" to "animation-js",
    "animation" to "animation-wasm-js",

    "animation" to "animation-core",
    "animation" to "animation-core-js",
    "animation" to "animation-core-wasm-js",

    "animation" to "animation-graphics",
    "animation" to "animation-graphics-js",
    "animation" to "animation-graphics-wasm-js",

    "foundation" to "foundation",
    "foundation" to "foundation-js",
    "foundation" to "foundation-wasm-js",
    "foundation" to "foundation-layout",
    "foundation" to "foundation-layout-js",
    "foundation" to "foundation-layout-wasm-js",

    "material3" to "material3",
    "material3" to "material3-js",
    "material3" to "material3-wasm-js",
    "material3" to "material3-window-size-class",
    "material3" to "material3-window-size-class-js",
    "material3" to "material3-window-size-class-wasm-js",

    // TODO_maybe: add adaptive subgroup? https://mvnrepository.com/artifact/org.jetbrains.compose.material3.adaptive
    // TODO_maybe: add common subgroup? https://mvnrepository.com/artifact/org.jetbrains.compose.material3.common

    "material" to "material",
    "material" to "material-js",
    "material" to "material-wasm-js",
    "material" to "material-icons-core",
    "material" to "material-icons-core-js",
    "material" to "material-icons-core-wasm-js",
    "material" to "material-icons-extended",
    "material" to "material-icons-extended-js",
    "material" to "material-icons-extended-wasm-js",
    "material" to "material-ripple",
    "material" to "material-ripple-js",
    "material" to "material-ripple-wasm-js",
    "material" to "material-navigation",
    "material" to "material-navigation-js",
    "material" to "material-navigation-wasm-js",

    "components" to "components-resources",
    "components" to "components-resources-js",
    "components" to "components-resources-wasmJs", // yes, bad/different naming, "wasmJs" suffix instead of "wasm-js"
    "components" to "components-ui-tooling-preview",
    "components" to "components-ui-tooling-preview-js",
    "components" to "components-ui-tooling-preview-wasmJs",
    "components" to "components-splitpane",
    "components" to "components-animatedimage",

    "desktop" to "desktop",
    "desktop" to "desktop-jvm",

    "html" to "html-core",
    "html" to "html-core-jvm",
    "html" to "html-core-js",
    "html" to "html-svg",
    "html" to "html-svg-js",
    "html" to "html-test-utils",
    "html" to "html-test-utils-js",
    "html" to "html-benchmark-core",
    "html" to "html-benchmark-core-js",
).map { "org.jetbrains.compose." + it.first to it.second }


// https://mvnrepository.com/artifact/org.jetbrains.androidx
private val someJbAndroidxStuff = listOf(
    // group here is always PREFIXED with "org.jetbrains.androidx"

    "lifecycle" to "lifecycle-common",
    "lifecycle" to "lifecycle-common-js",
    "lifecycle" to "lifecycle-common-wasm-js",
    "lifecycle" to "lifecycle-runtime",
    "lifecycle" to "lifecycle-runtime-js",
    "lifecycle" to "lifecycle-runtime-wasm-js",
    "lifecycle" to "lifecycle-runtime-compose",
    "lifecycle" to "lifecycle-runtime-compose-js",
    "lifecycle" to "lifecycle-runtime-compose-wasm-js",
    "lifecycle" to "lifecycle-viewmodel",
    "lifecycle" to "lifecycle-viewmodel-js",
    "lifecycle" to "lifecycle-viewmodel-wasm-js",
    "lifecycle" to "lifecycle-viewmodel-compose",
    "lifecycle" to "lifecycle-viewmodel-compose-js",
    "lifecycle" to "lifecycle-viewmodel-compose-wasm-js",
    "lifecycle" to "lifecycle-viewmodel-savedstate",
    "lifecycle" to "lifecycle-viewmodel-savedstate-js",
    "lifecycle" to "lifecycle-viewmodel-savedstate-wasm-js",

    // TODO_maybe: https://mvnrepository.com/artifact/org.jetbrains.androidx.navigation
    // TODO_maybe: https://mvnrepository.com/artifact/org.jetbrains.androidx.core
    // TODO_maybe: https://mvnrepository.com/artifact/org.jetbrains.androidx.savedstate
    // TODO_maybe: https://mvnrepository.com/artifact/org.jetbrains.androidx.window
    // TODO_maybe: https://mvnrepository.com/artifact/org.jetbrains.androidx.graphics
).map { "org.jetbrains.androidx." + it.first to it.second }
// TODO_maybe more: https://mvnrepository.com/artifact/org.jetbrains.kotlin-wrappers

private fun getAdditionalModules(): List<ModuleId.Maven> = (
    emptyList<Pair<String, String>>() +
        someJbKotlinStuff +
        someJbKotlinXStuff +
        someJbMainComposeStuff +
        someJbComposeStuff +
        someJbAndroidxStuff +
        listOf("androidx.compose.compiler" to "compiler") + // FIXME: do it "new way" ??
        listOf(
            "androidx.percentlayout" to "percentlayout",
            "org.mockito.kotlin" to "mockito-kotlin",
            "com.google.truth" to "truth",
            "com.google.truth" to "truth-parent",
            "io.realm" to "realm-gradle-plugin",
            "io.github.typesafegithub" to "github-workflows-kt",
            "io.github.typesafegithub" to "action-binding-generator",
            "io.github.typesafegithub" to "action-updates-checker",
            "io.github.typesafegithub" to "shared-internal",
            "com.github.ajalt.clikt" to "clikt", // https://repo1.maven.org/maven2/com/github/ajalt/clikt/
            "com.github.ajalt.clikt" to "clikt-core",
            "com.github.ajalt.clikt" to "clikt-markdown",
            "com.github.ajalt.mordant" to "mordant",
            "com.github.ajalt.mordant" to "mordant-coroutines",
            "com.github.ajalt.mordant" to "mordant-markdown",
            "com.github.ajalt.mordant" to "mordant-jvm-jna",
            "com.github.ajalt.mordant" to "mordant-omnibus",
            "com.github.ajalt.mordant" to "mordant-graal-ffi",
            "com.github.ajalt.mordant" to "mordant-jvm-ffm",
            "org.slf4j" to "slf4j-api",
            "org.slf4j" to "slf4j-simple",
            "org.hildan.chrome" to "chrome-devtools-kotlin",
        ).map { it.first to it.second } +
        listOf(
            "core", "ktor-client", "ktor-server", "transport-ktor", "transport-ktor-websocket",
            "transport-ktor-websocket-client", "transport-ktor-websocket-server",
            "transport-ktor-tcp", "transport-nodejs-tcp"
        ).map { "io.rsocket.kotlin" to "rsocket-$it" } +
        listOf(
            "compose" to "compose", "kotlinx" to "atomicfu"
        ).map { "org.jetbrains." + it.first to it.second + "-gradle-plugin"} +
        listOf(
            "wrappers-bom", "actions-toolkit", "browser", "cesium", "css", "csstype", "emotion", "history",
            "js", "mui", "mui-icons", "node", "popper", "react", "react-beautiful-dnd", "react-core", "react-dom",
            "react-dom-legacy", "react-dom-test-utils", "react-legacy", "react-redux", "react-router",
            "react-router-dom", "react-popper", "react-select", "react-use", "redux", "remix-run-router",
            "ring-ui", "styled", "styled-next", "tanstack-query-core", "tanstack-react-query",
            "tanstack-react-query-devtools", "tanstack-react-table", "tanstack-react-virtual",
            "tanstack-table-core", "tanstack-virtual-core", "typescript", "web",
        ).map { "org.jetbrains.kotlin-wrappers" to "kotlin-$it" } +
        listOf(
            "coil", "glide", "picasso", "imageloading-core"
        ).map { "com.google.accompanist" to "accompanist-$it" }
    ).map { ModuleId.Maven(it.first, it.second) }

// TODO_later: fetch the list from maven central instead of hardcoding
//  https://repo1.maven.org/maven2/pl/mareklangiewicz/
private fun getLangiewiczModules(): List<ModuleId.Maven> = (
        listOf("abcdk").withPlatforms() +
        listOf("tuplek").withPlatforms() +
        listOf("kground").withSuffixes("", "-io", "x", "x-io").withPlatforms() +
        listOf("kground").withSuffixes("x-maintenance", "x-workflows", "x-experiments", "x-jupyter").withPlatforms(withJs = false, withLinuxX64 = false) +
        listOf("kommand").withSuffixes("-line", "-samples").withPlatforms() +
        listOf("rxmock").withPlatforms(withJs = false, withLinuxX64 = false) +
        listOf("smokk", "smokkx").withPlatforms() +
        listOf("upue").withPlatforms() +
        listOf("uspek").withSuffixes("", "x").withPlatforms() +
        listOf("uspek").withSuffixes("x-junit4", "x-junit5").withPlatforms(withJs = false, withLinuxX64 = false) +
        listOf("uwidgets", "uwidgets-demo").withPlatforms(withLinuxX64 = false)
    )
    .map { ModuleId.Maven("pl.mareklangiewicz", it) }

private fun List<String>.withSuffixes(suffixes: Iterable<String>) =
    flatMap { base -> buildList { for (s in suffixes) add("$base$s") } }

private fun List<String>.withSuffixes(vararg suffixes: String) = withSuffixes(suffixes.toList())

private fun List<String>.withPlatforms(withBase: Boolean = true, withJvm: Boolean = true, withJs: Boolean = true, withLinuxX64: Boolean = true) =
    withSuffixes(listOfNotNull("".takeIf { withBase  }, "-jvm".takeIf { withJvm }, "-js".takeIf { withJs }, "-linuxx64".takeIf { withLinuxX64 }))
