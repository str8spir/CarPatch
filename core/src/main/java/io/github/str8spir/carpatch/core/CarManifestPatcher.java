/*
 * CarPatch - make Android apps appear on Android Auto
 * Copyright (C) 2026 str8spir and CarPatch contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.str8spir.carpatch.core;

import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlDocument;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ValueType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Adds the declarations Android Auto looks for to an app's base APK:
 *
 * <ul>
 *   <li>an application-level {@code com.google.android.gms.car.application} meta-data entry that
 *       points to an {@code automotive_app_desc} XML resource declaring {@code <uses name="media"/>};</li>
 *   <li>{@code androidx.car.app.minCarApiLevel} (only when the app does not already declare it);</li>
 *   <li>the {@code CAR_LAUNCHER}, {@code androidx.car.app.category.NAVIGATION} and {@code APP_MAPS}
 *       categories on every launcher (MAIN + LAUNCHER) activity or activity-alias.</li>
 * </ul>
 *
 * <p>It deliberately does <b>not</b> add {@code distractionOptimized}: that flag tells Android Auto an
 * app is safe to use while driving. Leaving it out keeps Android Auto's own driving restrictions
 * in force for patched apps.</p>
 *
 * <p>Patching is idempotent: running it on an already patched APK changes nothing further.</p>
 */
public final class CarManifestPatcher {

    public static final String META_CAR_APPLICATION = "com.google.android.gms.car.application";
    public static final String META_MIN_CAR_API_LEVEL = "androidx.car.app.minCarApiLevel";
    /** Marker so a later run can tell this APK was patched by CarPatch. */
    public static final String META_PATCHED_BY = "io.github.str8spir.carpatch.PATCHED";

    static final String ACTION_MAIN = "android.intent.action.MAIN";
    static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
    static final String[] CAR_CATEGORIES = {
            "android.intent.category.CAR_LAUNCHER",
            "androidx.car.app.category.NAVIGATION",
            "android.intent.category.APP_MAPS",
    };

    static final String DESC_RESOURCE_NAME = "carpatch_automotive_app_desc";
    static final String DESC_RESOURCE_PATH = "res/carpatch_automotive_app_desc.xml";
    static final String DESC_USES = "media";
    static final int MIN_CAR_API_LEVEL = 1;

    private static final String TAG_APPLICATION = "application";
    private static final String TAG_ACTIVITY = "activity";
    private static final String TAG_ACTIVITY_ALIAS = "activity-alias";
    private static final String TAG_INTENT_FILTER = "intent-filter";
    private static final String TAG_ACTION = "action";
    private static final String TAG_CATEGORY = "category";
    private static final String TAG_META_DATA = "meta-data";

    private static final String NAME_name = "name";
    private static final String NAME_value = "value";
    private static final String NAME_resource = "resource";
    private static final int ID_name = 0x01010003;
    private static final int ID_resource = 0x01010025;
    private static final int ID_value = 0x01010024;

    private CarManifestPatcher() {
    }

    /** Result of patching one base APK. */
    public static final class Result {
        /** Launcher activities (or aliases) that now carry the car categories. */
        public final List<String> launcherActivities;
        /** True when nothing needed changing (already patched). */
        public final boolean alreadyPatched;

        Result(List<String> launcherActivities, boolean alreadyPatched) {
            this.launcherActivities = launcherActivities;
            this.alreadyPatched = alreadyPatched;
        }
    }

    /**
     * Patches the loaded base APK in memory. Write it out with {@link ApkPatcher}.
     *
     * @throws PatchException when the APK has no launcher activity or no resource table
     */
    public static Result patch(ApkModule base) throws PatchException {
        AndroidManifestBlock manifest = base.getAndroidManifest();
        if (manifest == null) {
            throw new PatchException("The APK has no AndroidManifest.xml");
        }
        ResXmlElement application = manifest.getApplicationElement();
        if (application == null) {
            throw new PatchException("The manifest has no <application> element");
        }

        boolean changed = false;

        // 1. Car categories on every launcher activity / alias.
        List<String> launchers = new ArrayList<>();
        for (ResXmlElement activity : children(application)) {
            if (!activity.equalsName(TAG_ACTIVITY) && !activity.equalsName(TAG_ACTIVITY_ALIAS)) {
                continue;
            }
            for (ResXmlElement filter : children(activity)) {
                if (!filter.equalsName(TAG_INTENT_FILTER) || !isLauncherFilter(filter)) {
                    continue;
                }
                for (String category : CAR_CATEGORIES) {
                    if (!hasNamedChild(filter, TAG_CATEGORY, category)) {
                        filter.newElement(TAG_CATEGORY)
                                .getOrCreateAndroidAttribute(NAME_name, ID_name)
                                .setValueAsString(category);
                        changed = true;
                    }
                }
                launchers.add(androidName(activity));
            }
        }
        if (launchers.isEmpty()) {
            throw new PatchException("The app has no launcher activity, so there is nothing to show on the car screen");
        }

        // 2. automotive_app_desc resource + meta-data pointing at it.
        ResXmlElement carMeta = findMetaData(application, META_CAR_APPLICATION);
        if (carMeta == null) {
            int descResId = addAutomotiveAppDesc(base);
            ResXmlElement meta = application.newElement(TAG_META_DATA);
            meta.getOrCreateAndroidAttribute(NAME_name, ID_name).setValueAsString(META_CAR_APPLICATION);
            ResXmlAttribute resource = meta.getOrCreateAndroidAttribute(NAME_resource, ID_resource);
            resource.setValueType(ValueType.REFERENCE);
            resource.setData(descResId);
            changed = true;
        }

        // 3. minCarApiLevel, only if missing (real Car App Library apps already declare their own).
        if (findMetaData(application, META_MIN_CAR_API_LEVEL) == null) {
            ResXmlElement meta = application.newElement(TAG_META_DATA);
            meta.getOrCreateAndroidAttribute(NAME_name, ID_name).setValueAsString(META_MIN_CAR_API_LEVEL);
            ResXmlAttribute value = meta.getOrCreateAndroidAttribute(NAME_value, ID_value);
            value.setValueType(ValueType.DEC);
            value.setData(MIN_CAR_API_LEVEL);
            changed = true;
        }

        // 4. Marker.
        if (findMetaData(application, META_PATCHED_BY) == null) {
            ResXmlElement meta = application.newElement(TAG_META_DATA);
            meta.getOrCreateAndroidAttribute(NAME_name, ID_name).setValueAsString(META_PATCHED_BY);
            meta.getOrCreateAndroidAttribute(NAME_value, ID_value).setValueAsBoolean(true);
            changed = true;
        }

        if (changed) {
            manifest.refresh();
        }
        return new Result(launchers, !changed);
    }

    /** True when the base APK's manifest carries CarPatch's marker. */
    public static boolean isPatched(ApkModule base) {
        AndroidManifestBlock manifest = base.getAndroidManifest();
        if (manifest == null || manifest.getApplicationElement() == null) {
            return false;
        }
        return findMetaData(manifest.getApplicationElement(), META_PATCHED_BY) != null;
    }

    private static int addAutomotiveAppDesc(ApkModule base) throws PatchException {
        if (!base.hasTableBlock()) {
            throw new PatchException("The APK has no resources.arsc, so the Android Auto descriptor can't be added");
        }
        TableBlock table = base.getTableBlock();
        PackageBlock pkg = findAppPackage(table, base.getPackageName());
        if (pkg == null) {
            throw new PatchException("Could not find the app's resource package");
        }
        Entry entry = pkg.getOrCreate("", "xml", DESC_RESOURCE_NAME);
        entry.setValueAsString(DESC_RESOURCE_PATH);

        ResXmlDocument doc = new ResXmlDocument();
        ResXmlElement root = doc.getOrCreateElement("automotiveApp");
        root.newElement("uses").getOrCreateAttribute(NAME_name, 0).setValueAsString(DESC_USES);
        doc.refreshFull();
        base.add(new ByteInputSource(doc.getBytes(), DESC_RESOURCE_PATH));

        table.refresh();
        return entry.getResourceId();
    }

    private static PackageBlock findAppPackage(TableBlock table, String packageName) {
        PackageBlock byId = table.getPackageBlockById(0x7f);
        if (byId != null) {
            return byId;
        }
        if (packageName != null) {
            for (PackageBlock pkg : table.listPackages()) {
                if (packageName.equals(pkg.getName())) {
                    return pkg;
                }
            }
        }
        return table.pickOne();
    }

    private static boolean isLauncherFilter(ResXmlElement filter) {
        return hasNamedChild(filter, TAG_ACTION, ACTION_MAIN)
                && hasNamedChild(filter, TAG_CATEGORY, CATEGORY_LAUNCHER);
    }

    private static boolean hasNamedChild(ResXmlElement parent, String tag, String name) {
        for (ResXmlElement child : children(parent)) {
            if (child.equalsName(tag) && name.equals(androidName(child))) {
                return true;
            }
        }
        return false;
    }

    static ResXmlElement findMetaData(ResXmlElement application, String name) {
        for (ResXmlElement child : children(application)) {
            if (child.equalsName(TAG_META_DATA) && name.equals(androidName(child))) {
                return child;
            }
        }
        return null;
    }

    static String androidName(ResXmlElement element) {
        ResXmlAttribute attribute = element.searchAttributeByResourceId(ID_name);
        return attribute == null ? null : attribute.getValueAsString();
    }

    private static List<ResXmlElement> children(ResXmlElement parent) {
        List<ResXmlElement> list = new ArrayList<>();
        Iterator<ResXmlElement> it = parent.getElements();
        while (it.hasNext()) {
            list.add(it.next());
        }
        return list;
    }
}
