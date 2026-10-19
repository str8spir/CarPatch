/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.str8spir.carpatch.patch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/** Installs a set of APKs (base + splits) as one app through Android's PackageInstaller. */
object AppInstaller {

    const val ACTION_INSTALL_RESULT = "io.github.str8spir.carpatch.INSTALL_RESULT"
    const val EXTRA_TARGET_PACKAGE = "target_package"

    sealed interface Result {
        val packageName: String

        data class Success(override val packageName: String) : Result
        data class Failure(override val packageName: String, val message: String) : Result
        data class Cancelled(override val packageName: String) : Result
    }

    private val _results = MutableSharedFlow<Result>(extraBufferCapacity = 4)
    val results: SharedFlow<Result> = _results.asSharedFlow()

    internal fun emit(result: Result) {
        _results.tryEmit(result)
    }

    /** Writes the APKs into an install session and commits it. Android asks you to confirm. */
    fun install(context: Context, packageName: String, apks: List<File>) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apks.forEachIndexed { index, apk ->
                session.openWrite("apk_$index.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            val callback = Intent(context, InstallResultReceiver::class.java)
                .setAction(ACTION_INSTALL_RESULT)
                .putExtra(EXTRA_TARGET_PACKAGE, packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

/** Receives install status from PackageInstaller and shows Android's confirmation screen. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppInstaller.ACTION_INSTALL_RESULT) return
        val target = intent.getStringExtra(AppInstaller.EXTRA_TARGET_PACKAGE).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    AppInstaller.emit(AppInstaller.Result.Failure(target, "Android did not show the install confirmation"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppInstaller.emit(AppInstaller.Result.Success(target))
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppInstaller.emit(AppInstaller.Result.Cancelled(target))
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppInstaller.emit(AppInstaller.Result.Failure(target, describeFailure(status, message)))
            }
        }
    }

    private fun describeFailure(status: Int, message: String?): String {
        val detail = message?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "The original app is still installed with a different signature. Uninstall it, then try again.$detail"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Android says this app is not compatible with this phone.$detail"
            PackageInstaller.STATUS_FAILURE_INVALID ->
                "Android rejected the patched APK as invalid.$detail"
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                "Not enough storage to install the app.$detail"
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "The install was blocked.$detail"
            else -> "Install failed.$detail"
        }
    }
}
