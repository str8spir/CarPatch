/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.str8spir.carpatch.patch

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The key patched apps are signed with. It is created once in the Android Keystore on this phone,
 * never leaves it, and stays the same for every patch, so re-patching an app later is an in-place
 * update that keeps its data. Uninstalling CarPatch or clearing its storage deletes the key; after
 * that, already patched apps must be uninstalled once before they can be patched again.
 */
object SigningKey {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "carpatch_signing_v1"

    data class Material(val privateKey: PrivateKey, val certificate: X509Certificate)

    @Synchronized
    fun getOrCreate(): Material {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            val notBefore = Date()
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 50) }.time
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=CarPatch local signing key"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(notAfter)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return Material(entry.privateKey, entry.certificate as X509Certificate)
    }

    /** DER bytes of the signing certificate, or null if the key has not been created yet. */
    fun certificateBytesOrNull(): ByteArray? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) return null
        return keyStore.getCertificate(ALIAS)?.encoded
    }
}
