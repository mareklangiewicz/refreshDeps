package de.fayard.refreshVersions.core.internal

import de.fayard.refreshVersions.core.DependencyVersionsFetcher
import de.fayard.refreshVersions.core.Version
import de.fayard.refreshVersions.core.internal.VersionCandidatesResultMode.FilterMode.*
import de.fayard.refreshVersions.core.internal.VersionCandidatesResultMode.SortingMode.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal suspend fun List<DependencyVersionsFetcher>.getVersionCandidates(
    currentVersion: Version,
    resultMode: VersionCandidatesResultMode
): Pair<List<Version>, List<DependencyVersionsFetcher.Result.Failure>> {
    val results = getVersionCandidates(versionFilter = { it > currentVersion })
    return results.sortWith(resultMode)
}

internal fun List<DependencyVersionsFetcher.Result>.sortWith(
    resultMode: VersionCandidatesResultMode
): Pair<List<Version>, List<DependencyVersionsFetcher.Result.Failure>> {
    val results = this
    val versionsList = results.filterIsInstance<DependencyVersionsFetcher.Result.Success>()
    val failures = results.filterIsInstance<DependencyVersionsFetcher.Result.Failure>()
    return when (resultMode.filterMode) {
        AllIntermediateVersions -> when (resultMode.sortingMode) {
            is ByRepo -> versionsList.byRepoSorting(resultMode.sortingMode).flatMap {
                it.availableVersions.asSequence()
            }
            ByVersion -> versionsList.flatMap { it.availableVersions.asSequence() }.sorted()
        }.distinct().toList()
        LatestByStabilityLevel -> when (resultMode.sortingMode) {
            is ByRepo -> versionsList.byRepoSorting(resultMode.sortingMode).flatMap {
                it.availableVersions.filterLatestByStabilityLevel().asSequence()
            }
            ByVersion -> versionsList.flatMap {
                it.availableVersions.filterLatestByStabilityLevel().asSequence()
            }.sorted()
        }.distinct().toList()
        Latest -> when (resultMode.sortingMode) {
            is ByRepo -> versionsList.byRepoSorting(resultMode.sortingMode).map {
                it.availableVersions.last()
            }.distinct().toList()
            is ByVersion -> listOf(versionsList.flatMap { it.availableVersions.asSequence() }.sorted().last())
        }
    } to failures
}

private fun List<DependencyVersionsFetcher.Result.Success>.byRepoSorting(
    sortingMode: ByRepo
): List<DependencyVersionsFetcher.Result.Success> = when (sortingMode) {
    ByRepo -> this
    ByRepo.LastUpdated -> sortedBy { it.lastUpdateTimestampMillis }
    ByRepo.LastVersionComparison -> sortedBy { it.availableVersions.last() }
}

private fun List<Version>.filterLatestByStabilityLevel(): List<Version> {
    return sortedDescendingWorkaround().fold<Version, List<Version>>(emptyList()) { acc, versionCandidate ->
        val previousStabilityLevel = acc.lastOrNull()?.stabilityLevel
            ?: return@fold acc + versionCandidate
        if (versionCandidate.stabilityLevel isMoreStableThan previousStabilityLevel) {
            acc + versionCandidate
        } else acc
    }.asReversed()
}

/*
Looks like there is a bug in Version class comparison algorithm.
I don't have time to analyze it now, it's a lot of code.
So let's do some hacky workaround.

> Task :refreshVersions:test FAILED

MyExperiments > generateDeps() FAILED
    java.lang.IllegalArgumentException: Comparison method violates its general contract!
        at java.base/java.util.TimSort.mergeHi(TimSort.java:903)
        at java.base/java.util.TimSort.mergeAt(TimSort.java:520)
        at java.base/java.util.TimSort.mergeCollapse(TimSort.java:448)
        at java.base/java.util.TimSort.sort(TimSort.java:245)
        at java.base/java.util.Arrays.sort(Arrays.java:1234)
        at kotlin.collections.ArraysKt___ArraysJvmKt.sortWith(_ArraysJvm.kt:2179)
        at kotlin.collections.CollectionsKt___CollectionsKt.sortedWith(_Collections.kt:1075)
        at kotlin.collections.CollectionsKt___CollectionsKt.sortedDescending(_Collections.kt:1063)
        at de.fayard.refreshVersions.core.internal.GettingVersionCandidatesKt.filterLatestByStabilityLevel(GettingVersionCandidates.kt:59)
        at de.fayard.refreshVersions.core.internal.GettingVersionCandidatesKt.sortWith(GettingVersionCandidates.kt:37)
        at de.fayard.refreshVersions.core.internal.GettingVersionCandidatesKt.getVersionCandidates(GettingVersionCandidates.kt:16)
        at de.fayard.refreshVersions.core.internal.GettingVersionCandidatesKt$getVersionCandidates$1.invokeSuspend(GettingVersionCandidates.kt)
        ...
 */
private fun List<Version>.sortedDescendingWorkaround(): List<Version> {
    return try {
        sortedDescending()
    }
    catch (e: IllegalArgumentException) {
        if (e.message == "Comparison method violates its general contract!") {
            println("Warning: Caught: $e")
            println("Warning: Problem with Version.compareTo algorithm!")
            println("Warning: Using workaround: simple alphanumerical sort on Version.value.")
            sortedByDescending { it.value }
        } else throw e
    }
}

private suspend fun List<DependencyVersionsFetcher>.getVersionCandidates(
    versionFilter: ((Version) -> Boolean)? = null
): List<DependencyVersionsFetcher.Result> {

    require(isNotEmpty()) { "Cannot get version candidates with an empty fetchers list." }
    val moduleId = first().moduleId
    require(all { it.moduleId == moduleId })

    return coroutineScope {
        map { fetcher ->
            async {
                fetcher.attemptGettingAvailableVersions(versionFilter = versionFilter)
            }
        }
    }.awaitAll().also { results ->
        if (results.isEmpty()) throw NoSuchElementException(buildString {
            append("$moduleId not found. ")
            appendLine("Searched the following repositories:")
            this@getVersionCandidates.forEach { appendLine("- ${it.repoUrlOrKey}") }
        })
    }.distinctBy {
        when (it) {
            is DependencyVersionsFetcher.Result.Success -> it.availableVersions
            else -> it
        }
    }
}
