/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.str8spir.carpatch.patch

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.str8spir.carpatch.core.ApkPatcher
import io.github.str8spir.carpatch.core.PatchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PatchState {
    data object Idle : PatchState
    data class Working(val app: InstalledApp, val step: String) : PatchState
    /** Patched APKs are ready, but the original (differently signed) app must be uninstalled first. */
    data class NeedsUninstall(val app: InstalledApp) : PatchState
    data class Installing(val app: InstalledApp) : PatchState
    data class Done(val app: InstalledApp) : PatchState
    data class Failed(val app: InstalledApp?, val message: String) : PatchState
}

class PatchViewModel(application: Application) : AndroidViewModel(application) {

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _state = MutableStateFlow<PatchState>(PatchState.Idle)
    val state: StateFlow<PatchState> = _state.asStateFlow()

    private var pendingApks: List<File> = emptyList()

    init {
        refresh()
        viewModelScope.launch {
            AppInstaller.results.collect { result ->
                val current = (_state.value as? PatchState.Installing)?.app
                    ?: (_state.value as? PatchState.Working)?.app
                when (result) {
                    is AppInstaller.Result.Success -> {
                        val app = InstalledApps.describe(getApplication(), result.packageName) ?: current
                        _state.value = if (app != null) PatchState.Done(app) else PatchState.Idle
                        cleanWorkDir(result.packageName)
                    }
                    is AppInstaller.Result.Cancelled ->
                        _state.value = PatchState.Failed(current, "Install cancelled.")
                    is AppInstaller.Result.Failure ->
                        _state.value = PatchState.Failed(current, result.message)
                }
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _apps.value = withContext(Dispatchers.IO) { InstalledApps.load(getApplication()) }
            _loading.value = false
        }
    }

    fun dismiss() {
        _state.value = PatchState.Idle
    }

    fun patch(app: InstalledApp) {
        viewModelScope.launch {
            try {
                val apks = withContext(Dispatchers.IO) { buildPatchedApks(app) }
                pendingApks = apks
                // Re-check right before installing: the signing key may have been created just now.
                val current = InstalledApps.describe(getApplication(), app.packageName) ?: app
                if (current.isSignedByCarPatch) {
                    install(current)
                } else {
                    _state.value = PatchState.NeedsUninstall(current)
                }
            } catch (e: PatchException) {
                _state.value = PatchState.Failed(app, e.message ?: "Patching failed.")
            } catch (e: Exception) {
                _state.value = PatchState.Failed(app, "Patching failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Called when Android's uninstall screen closes. */
    fun onUninstallFinished(app: InstalledApp) {
        if (InstalledApps.isInstalled(getApplication(), app.packageName)) {
            _state.value = PatchState.Failed(app, "The original app is still installed, so the patched version can't be installed.")
        } else {
            install(app)
        }
    }

    private fun install(app: InstalledApp) {
        _state.value = PatchState.Installing(app)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { AppInstaller.install(getApplication(), app.packageName, pendingApks) }
            } catch (e: Exception) {
                _state.value = PatchState.Failed(app, "Could not start the install: ${e.message}")
            }
        }
    }

    private suspend fun buildPatchedApks(app: InstalledApp): List<File> {
        val context = getApplication<Application>()
        val info = context.packageManager.getApplicationInfo(app.packageName, PackageManager.ApplicationInfoFlags.of(0))
        val workDir = cleanWorkDir(app.packageName).apply { mkdirs() }

        step(app, "Patching ${app.label}…")
        val unsignedBase = File(workDir, "base-unsigned.apk")
        ApkPatcher.patchBase(File(info.sourceDir), unsignedBase)

        step(app, "Signing…")
        val key = SigningKey.getOrCreate()
        val outputs = mutableListOf<File>()
        val signedBase = File(workDir, "base.apk")
        ApkSigning.sign(unsignedBase, signedBase, key)
        unsignedBase.delete()
        outputs += signedBase

        info.splitSourceDirs.orEmpty().forEachIndexed { index, splitPath ->
            step(app, "Signing split ${index + 1} of ${info.splitSourceDirs!!.size}…")
            val signedSplit = File(workDir, "split_$index.apk")
            ApkSigning.sign(File(splitPath), signedSplit, key)
            outputs += signedSplit
        }
        return outputs
    }

    private suspend fun step(app: InstalledApp, text: String) = withContext(Dispatchers.Main) {
        _state.value = PatchState.Working(app, text)
    }

    private fun cleanWorkDir(packageName: String): File {
        val dir = File(getApplication<Application>().cacheDir, "patch/$packageName")
        dir.deleteRecursively()
        return dir
    }
}
