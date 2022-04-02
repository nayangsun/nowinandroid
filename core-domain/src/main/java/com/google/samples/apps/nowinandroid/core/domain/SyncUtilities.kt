/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.core.domain

import android.util.Log
import com.google.samples.apps.nowinandroid.core.datastore.ChangeListVersions
import com.google.samples.apps.nowinandroid.core.datastore.NiaPreferences
import com.google.samples.apps.nowinandroid.core.network.model.NetworkChangeList
import kotlin.coroutines.cancellation.CancellationException

/**
 * Attempts [block], returning a successful [Result] if it succeeds, otherwise a [Result.Failure]
 * taking care not to break structured concurrency
 */
private suspend fun <T> suspendRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellationException: CancellationException) {
    throw cancellationException
} catch (exception: Exception) {
    Log.i(
        "suspendRunCatching",
        "Failed to evaluate a suspendRunCatchingBlock. Returning failure Result",
        exception
    )
    Result.failure(exception)
}

/**
 * Utility function for syncing a repository with the network.
 * [versionReader] Reads the current version of the model that needs to be synced
 * [changeListFetcher] Fetches the change list for the model
 * [versionUpdater] Updates the [ChangeListVersions] after a successful sync
 * [modelUpdater] Updates the model by consuming the ids of the models that have changed.
 *
 * Note that the blocks defined above may be run concurrently due to concurrent calls
 * to [changeListSync]. It is the caller's responsibility to make [modelUpdater] performs atomic
 * operations.
 */
suspend fun changeListSync(
    niaPreferences: NiaPreferences,
    versionReader: (ChangeListVersions) -> Int,
    changeListFetcher: suspend (Int) -> List<NetworkChangeList>,
    versionUpdater: ChangeListVersions.(Int) -> ChangeListVersions,
    modelUpdater: suspend (List<Int>) -> Unit
) = suspendRunCatching {
    // Fetch the change list since last sync (akin to a git fetch)
    val currentVersion = versionReader(niaPreferences.getChangeListVersions())
    val changeList = changeListFetcher(currentVersion)
    if (changeList.isEmpty()) return@suspendRunCatching true

    // Using the change list, pull down and save the changes (akin to a git pull)
    modelUpdater(changeList.map(NetworkChangeList::id))

    // Update the last synced version (akin to updating local git HEAD)
    val latestVersion = changeList.last().changeListVersion
    niaPreferences.updateChangeListVersion {
        versionUpdater(latestVersion)
    }
}.isSuccess