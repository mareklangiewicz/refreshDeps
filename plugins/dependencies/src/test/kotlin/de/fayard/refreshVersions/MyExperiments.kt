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
            getLangaraModules()

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

            val vers = versions.map { it.value to it.stabilityLevel.instability }
            outputmap.putDep(path, valName, moduleId.group to moduleId.name to vers)
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

            "https://androidx.dev/storage/compose-compiler/repository/",
            // FIXME: very wasteful, I should use it only for jetpack (androidx) compose compiler

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
private typealias Ver = Pair<String, Int>
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
        for ((ver, instability) in vers) {
            if ('<' in ver || '>' in ver) {
                allVerCorrect = false
                println("Incorrect version found for module $name:")
                println(ver)
            }
            else append(", Ver(\"$ver\", $instability)")
        }
    }
    if (!allVerCorrect) appendLine("@Deprecated(\"Warning: Some incorrect versions found (filtered out)\")".withIndent(indent))
    appendLine("val $valname = Dep(\"$group\", \"$name\"$versStr)".withIndent(indent))
}

@Suppress("UNCHECKED_CAST")
private fun StringBuilder.appendDepTree(indent: Int, tree: DepTree) {
    val entriesSorted = tree.entries.sortedBy { it.key }
    val (entriesForGroups, entriesForDeps) = entriesSorted.partition { it.key[0].isUpperCase() }
    for ((valname, dep) in entriesForDeps)
        appendDep(indent, valname, dep as Dep)
    for ((objectName, content) in entriesForGroups) {
        appendLine("object $objectName {".withIndent(indent))
        appendDepTree(indent + 4, content as DepTree)
        appendLine("}".withIndent(indent))
    }
}

private fun String.withIndent(indent: Int = 4) = " ".repeat(indent) + this

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

private fun getAdditionalModules(): List<ModuleId.Maven> = (
    emptyList<Pair<String, String>>() +
        listOf("org.jetbrains.compose.compiler" to "compiler") +
        listOf("androidx.compose.compiler" to "compiler") +
        listOf(
            "androidx.percentlayout" to "percentlayout",
            "org.mockito.kotlin" to "mockito-kotlin",
            "com.google.truth" to "truth",
            "com.google.truth" to "truth-parent",
            "io.realm" to "realm-gradle-plugin",
            "io.github.typesafegithub" to "github-workflows-kt",
            "com.github.ajalt.mordant" to "mordant",
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
private fun getLangaraModules(): List<ModuleId.Maven> = listOf(
    "abcdk", "abcdk-js", "abcdk-jvm", "abcdk-linuxx64",
    "kground", "kground-io", "kgroundx", "kgroundx-io", "kgroundx-maintenance",
    "kground-jvm", "kground-io-jvm", "kgroundx-jvm", "kgroundx-io-jvm", "kgroundx-maintenance-jvm",
    "kground-js", "kground-io-js", "kgroundx-js", "kgroundx-io-js", "kgroundx-maintenance",
    "kgroundx-jupyter", "kgroundx-jupyter-jvm",
    "kommandline", "kommandline-js", "kommandline-jvm",
    "kommandsamples", "kommandsamples-js", "kommandsamples-jvm",
    "kommandjupyter", "kommandjupyter-jvm",
    "rxmock", "rxmock-jvm", "smokk",
    "smokk-jvm", "smokkx", "smokkx-jvm",
    "template-andro-app", "template-andro-lib", "template-mpp-lib", "template-mpp-lib-js", "template-mpp-lib-jvm",
    "tuplek", "tuplek-js", "tuplek-jvm", "tuplek-linuxx64",
    "upue", "upue-js", "upue-jvm", "upue-linuxx64", "upue-test", "upue-test-js", "upue-test-jvm",
    "uspek", "uspek-js", "uspek-jvm", "uspek-linuxx64", "uspekx",
    "uspekx-js", "uspekx-junit4", "uspekx-junit4-jvm", "uspekx-junit5", "uspekx-junit5-jvm", "uspekx-jvm", "uspekx-linuxx64",
    "uwidgets", "uwidgets-js", "uwidgets-jvm", "uwidgets-udemo", "uwidgets-udemo-js", "uwidgets-udemo-jvm",
).map { ModuleId.Maven("pl.mareklangiewicz", it) }
