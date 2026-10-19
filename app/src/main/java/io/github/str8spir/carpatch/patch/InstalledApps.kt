/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.str8spir.carpatch.patch

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import io.github.str8spir.carpatch.core.CarManifestPatcher

/** An installed app that can be patched. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val icon: Bitmap?,
    /** The installed copy carries CarPatch's marker. */
    val isPatched: Boolean,
    /** The installed copy is signed with this phone's CarPatch key, so re-patching keeps its data. */
    val isSignedByCarPatch: Boolean,
    val splitCount: Int,
)

object InstalledApps {

    /** User-installed apps with a launcher icon, excluding CarPatch itself. */
    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val ourCert = SigningKey.certificateBytesOrNull()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }

        return packages.mapNotNull { pkg -> describe(pm, pkg, ourCert) }
            .sortedBy { it.label.lowercase() }
    }

    fun describe(context: Context, packageName: String): InstalledApp? =
        describe(context.packageManager, packageName, SigningKey.certificateBytesOrNull())

    private fun describe(pm: PackageManager, pkg: String, ourCert: ByteArray?): InstalledApp? {
        val info = try {
            pm.getPackageInfo(
                pkg,
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES).toLong()
                ),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val app = info.applicationInfo ?: return null
        val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
        if (isSystem) return null

        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        val signedByUs = ourCert != null && signers.any { it.toByteArray().contentEquals(ourCert) }
        val patched = app.metaData?.containsKey(CarManifestPatcher.META_PATCHED_BY) == true

        val icon = try {
            pm.getApplicationIcon(app).toBitmap(96, 96)
        } catch (e: Exception) {
            null
        }
        return InstalledApp(
            packageName = pkg,
            label = pm.getApplicationLabel(app).toString(),
            versionName = info.versionName,
            icon = icon,
            isPatched = patched,
            isSignedByCarPatch = signedByUs,
            splitCount = app.splitSourceDirs?.size ?: 0,
        )
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
