/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.str8spir.carpatch.core;

import com.reandroid.apk.ApkModule;
import com.reandroid.archive.InputSource;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * File-level patching of a base APK. The output is <b>unsigned</b>; sign it (together with any
 * split APKs) with {@code ApkSigner} from apksig, which also zip-aligns the stored entries.
 */
public final class ApkPatcher {

    private ApkPatcher() {
    }

    /** Patches {@code inBase} and writes the unsigned result to {@code outBase}. */
    public static CarManifestPatcher.Result patchBase(File inBase, File outBase) throws PatchException {
        Map<String, Integer> originalMethods = readEntryMethods(inBase);
        ApkModule module;
        try {
            module = ApkModule.loadApkFile(inBase);
        } catch (IOException e) {
            throw new PatchException("Could not read the APK: " + e.getMessage(), e);
        }
        try {
            CarManifestPatcher.Result result = CarManifestPatcher.patch(module);
            prepareForWriting(module, originalMethods);
            module.writeApk(outBase);
            return result;
        } catch (IOException e) {
            throw new PatchException("Could not write the patched APK: " + e.getMessage(), e);
        } finally {
            try {
                module.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    /** True when {@code apk}'s manifest carries CarPatch's marker. */
    public static boolean isPatched(File apk) {
        try {
            ApkModule module = ApkModule.loadApkFile(apk);
            try {
                return CarManifestPatcher.isPatched(module);
            } finally {
                module.close();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Drops the old signatures and restores each entry's original compression method.
     * Android 11+ requires resources.arsc to be stored uncompressed, and native libraries that
     * were stored must stay stored for extractNativeLibs=false apps.
     */
    static void prepareForWriting(ApkModule module, Map<String, Integer> originalMethods) {
        module.setApkSignatureBlock(null);
        module.getZipEntryMap().removeIf(source -> isJarSignatureFile(source.getAlias()));
        for (InputSource source : module.getZipEntryMap().toArray()) {
            String name = source.getAlias();
            if ("resources.arsc".equals(name)) {
                source.setMethod(ZipEntry.STORED);
                continue;
            }
            Integer method = originalMethods.get(name);
            source.setMethod(method != null ? method : ZipEntry.DEFLATED);
        }
    }

    static boolean isJarSignatureFile(String name) {
        if (name == null || !name.startsWith("META-INF/")) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("META-INF/MANIFEST.MF")) {
            return true;
        }
        if (upper.indexOf('/', "META-INF/".length()) != -1) {
            return false; // only top-level META-INF files belong to the JAR signature
        }
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }

    static Map<String, Integer> readEntryMethods(File apk) throws PatchException {
        Map<String, Integer> methods = new HashMap<>();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                methods.put(entry.getName(), entry.getMethod());
            }
        } catch (IOException e) {
            throw new PatchException("Could not read the APK: " + e.getMessage(), e);
        }
        return methods;
    }
}
