/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.str8spir.carpatch.patch

import android.os.Build
import com.android.apksig.ApkSigner
import java.io.File

/** Signs APKs with CarPatch's on-device key using Google's apksig (APK Signature Scheme v2 + v3). */
object ApkSigning {

    fun sign(input: File, output: File, key: SigningKey.Material) {
        val signer = ApkSigner.SignerConfig.Builder("CARPATCH", key.privateKey, listOf(key.certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            // CarPatch only runs on Android 15+, and patched apps only ever install on this phone,
            // so the v1 (JAR) scheme is not needed. apksig also zip-aligns the stored entries.
            .setMinSdkVersion(Build.VERSION.SDK_INT)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
