/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.str8spir.carpatch.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.str8spir.carpatch.patch.InstalledApp
import io.github.str8spir.carpatch.patch.PatchState
import io.github.str8spir.carpatch.patch.PatchViewModel

private const val PREFS = "carpatch"
private const val KEY_TERMS_ACCEPTED = "terms_accepted_v1"
private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarPatchScreen(viewModel: PatchViewModel = viewModel()) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val state by viewModel.state.collectAsState()

    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var termsAccepted by remember { mutableStateOf(prefs.getBoolean(KEY_TERMS_ACCEPTED, false)) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    var guideExpanded by rememberSaveable { mutableStateOf(true) }

    val uninstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        (viewModel.state.value as? PatchState.NeedsUninstall)?.let { viewModel.onUninstallFinished(it.app) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CarPatch") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh app list")
                    }
                },
            )
        },
    ) { padding ->
        val filtered = remember(apps, query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) apps else apps.filter { q in it.label.lowercase() || q in it.packageName.lowercase() }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SetupGuide(
                    expanded = guideExpanded,
                    onToggle = { guideExpanded = !guideExpanded },
                    onOpenAndroidAuto = { openAndroidAutoSettings(context) },
                )
            }
            item { SafetyNotice() }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text("Search apps") },
                )
            }
            if (loading && apps.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(filtered, key = { it.packageName }) { app ->
                AppRow(app = app, onClick = { selected = app })
            }
        }
    }

    if (!termsAccepted) {
        TermsDialog(onAccept = {
            prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, true).apply()
            termsAccepted = true
        })
    }

    selected?.let { app ->
        ConfirmPatchDialog(
            app = app,
            onConfirm = {
                selected = null
                viewModel.patch(app)
            },
            onDismiss = { selected = null },
        )
    }

    when (val s = state) {
        PatchState.Idle -> Unit
        is PatchState.Working -> ProgressDialog(title = s.app.label, text = s.step)
        is PatchState.Installing -> ProgressDialog(title = s.app.label, text = "Waiting for Android to install the patched app…")
        is PatchState.NeedsUninstall -> AlertDialog(
            onDismissRequest = { viewModel.dismiss() },
            title = { Text("Uninstall the original first") },
            text = {
                Text(
                    "The patched ${s.app.label} is ready. Because it is signed with CarPatch's key, " +
                        "Android won't install it over the original. Uninstall the original now " +
                        "(its data on this phone will be deleted), and CarPatch will install the patched version right after."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", s.app.packageName, null))
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    uninstallLauncher.launch(intent)
                }) { Text("Uninstall original") }
            },
            dismissButton = { TextButton(onClick = { viewModel.dismiss() }) { Text("Cancel") } },
        )
        is PatchState.Done -> AlertDialog(
            onDismissRequest = { viewModel.dismiss() },
            title = { Text("${s.app.label} is patched") },
            text = {
                Text(
                    "Connect your phone to the car. With Android Auto's \"Unknown sources\" setting on, " +
                        "${s.app.label} should appear in the Android Auto app launcher. " +
                        "Use it only while parked or as a passenger."
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.dismiss() }) { Text("OK") } },
        )
        is PatchState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.dismiss() },
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
            title = { Text(s.app?.label?.let { "Couldn't patch $it" } ?: "Something went wrong") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { viewModel.dismiss() }) { Text("OK") } },
        )
    }
}

@Composable
private fun SetupGuide(expanded: Boolean, onToggle: () -> Unit, onOpenAndroidAuto: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DirectionsCar, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Set up Android Auto (once)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                GuideStep(1, "Open Android Auto's settings (Settings → Connected devices → Android Auto).")
                GuideStep(2, "Scroll to the bottom and tap \"Version\" about 10 times until developer settings are enabled.")
                GuideStep(3, "Open the ⋮ menu → Developer settings, and turn on \"Unknown sources\".")
                GuideStep(4, "Patch an app below. VLC is a good first test: it's free and has no signature checks.")
                GuideStep(5, "Connect to your car. The patched app appears in the Android Auto launcher.")
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenAndroidAuto) { Text("Open Android Auto settings") }
            }
        }
    }
}

@Composable
private fun GuideStep(number: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$number.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SafetyNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "Parked or passenger use only. CarPatch does not mark apps as safe for driving, so Android Auto " +
                    "keeps its own driving restrictions for patched apps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (app.icon != null) {
                Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp))
            } else {
                Spacer(Modifier.size(44.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(app.packageName, app.versionName?.let { "v$it" }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (app.isPatched) {
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = onClick, label = { Text("Patched") })
            }
        }
    }
}

@Composable
private fun ConfirmPatchDialog(app: InstalledApp, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val inPlace = app.isSignedByCarPatch
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (inPlace) "Re-patch ${app.label}?" else "Patch ${app.label}?") },
        text = {
            Column {
                if (inPlace) {
                    Text("${app.label} was patched by CarPatch on this phone, so it will be updated in place and keep its data.")
                } else {
                    Text(
                        "CarPatch will make a copy of ${app.label} that Android Auto can show, signed with this phone's own key.\n\n" +
                            "The first time, the original app has to be uninstalled, and its data on this phone will be lost. " +
                            "Later re-patches (for example after an update) keep your data."
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Apps with DRM, signature checks or Play Integrity may refuse to start after patching. " +
                        "Updates from the Play Store won't install over the patched copy; re-patch after updating instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (inPlace) "Re-patch" else "Patch") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProgressDialog(title: String, text: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun TermsDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Before you start") },
        text = {
            Column {
                Text("CarPatch modifies apps installed on this phone so Android Auto can show them.")
                Spacer(Modifier.height(8.dp))
                Text("• Use patched apps only while parked or as a passenger. Never while driving.")
                Text("• Patched apps are for your own use on this phone. Don't share or publish patched APKs.")
                Text("• You are responsible for following the terms of the apps you patch.")
                Text("• CarPatch is free software provided without warranty (GPLv3). Use it at your own risk.")
                Spacer(Modifier.height(8.dp))
                Text("CarPatch collects no data and has no analytics.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("I understand") } },
    )
}

private fun openAndroidAutoSettings(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ANDROID_AUTO_PACKAGE, null))
    try {
        context.startActivity((launch ?: fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
