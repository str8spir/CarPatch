/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.str8spir.carpatch.core;

import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.File;
import java.util.Iterator;

/**
 * Command-line harness: {@code strip <in> <out>} removes Android Auto declarations (to simulate an
 * ordinary app); {@code patch <in> <out>} runs CarPatch's patcher; {@code patch-twice <in> <out>}
 * checks idempotency.
 */
public final class PatchHarness {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        File in = new File(args[1]);
        File out = new File(args[2]);
        switch (mode) {
            case "strip":
                strip(in, out);
                break;
            case "patch": {
                CarManifestPatcher.Result r = ApkPatcher.patchBase(in, out);
                System.out.println("launchers=" + r.launcherActivities + " alreadyPatched=" + r.alreadyPatched);
                break;
            }
            case "patch-twice": {
                File mid = new File(out.getPath() + ".mid.apk");
                ApkPatcher.patchBase(in, mid);
                CarManifestPatcher.Result r = ApkPatcher.patchBase(mid, out);
                System.out.println("second run alreadyPatched=" + r.alreadyPatched + " isPatched=" + ApkPatcher.isPatched(out));
                break;
            }
            default:
                throw new IllegalArgumentException(mode);
        }
    }

    /** Removes car categories and car meta-data, simulating an app with no Android Auto support. */
    static void strip(File in, File out) throws Exception {
        java.util.Map<String, Integer> methods = ApkPatcher.readEntryMethods(in);
        ApkModule module = ApkModule.loadApkFile(in);
        AndroidManifestBlock manifest = module.getAndroidManifest();
        ResXmlElement app = manifest.getApplicationElement();
        app.removeElementsIf(e -> e.equalsName("meta-data") && isCarMeta(CarManifestPatcher.androidName(e)));
        Iterator<ResXmlElement> it = app.getElements();
        while (it.hasNext()) {
            ResXmlElement activity = it.next();
            activity.removeElementsIf(e -> e.equalsName("meta-data") && "distractionOptimized".equals(CarManifestPatcher.androidName(e)));
            Iterator<ResXmlElement> filters = activity.getElements();
            while (filters.hasNext()) {
                ResXmlElement filter = filters.next();
                filter.removeElementsIf(e -> e.equalsName("category") && isCarCategory(CarManifestPatcher.androidName(e)));
            }
        }
        manifest.refresh();
        ApkPatcher.prepareForWriting(module, methods);
        module.writeApk(out);
        module.close();
    }

    private static boolean isCarMeta(String name) {
        return "com.google.android.gms.car.application".equals(name)
                || "androidx.car.app.minCarApiLevel".equals(name)
                || "distractionOptimized".equals(name);
    }

    private static boolean isCarCategory(String name) {
        for (String c : CarManifestPatcher.CAR_CATEGORIES) {
            if (c.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
