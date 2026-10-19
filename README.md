# CarPatch

**Open-source tool that patches Android apps on your phone so they appear on Android Auto.**

CarPatch takes an app you already have installed, adds the declarations Android Auto looks for,
re-signs it with a key that lives only on your phone, and reinstalls it. No root, no PC.

> [!CAUTION]
> **Parked or passenger use only.** Never watch or interact with a patched app while driving.
> CarPatch deliberately does **not** mark apps as safe for driving (`distractionOptimized`), so
> Android Auto keeps its own driving restrictions in force for every app you patch.

## Requirements

- Android 15 or newer
- Android Auto with developer settings and **Unknown sources** enabled (the app walks you through it)

## How to use

1. Install CarPatch and open it. Accept the terms.
2. Enable Android Auto's **Unknown sources** setting:
   Android Auto settings → tap **Version** about 10 times → ⋮ → **Developer settings** → **Unknown sources**.
3. Pick an app in CarPatch and tap **Patch**. The first time, Android asks you to uninstall the
   original app (its data on the phone is lost), then installs the patched copy.
4. Connect to your car. The app appears in the Android Auto launcher.

**Try VLC first.** It's free and has no signature checks, so it's the easiest way to check that
your car works with this approach.

## What it changes

In the app's base APK:

- adds `com.google.android.gms.car.application` meta-data pointing to a new
  `automotive_app_desc` resource (`<uses name="media"/>`)
- adds `androidx.car.app.minCarApiLevel` (only if the app doesn't already declare it)
- adds the `CAR_LAUNCHER`, `androidx.car.app.category.NAVIGATION` and `APP_MAPS` categories
  to the app's launcher activities
- adds an `io.github.str8spir.carpatch.PATCHED` marker

Then the base and all split APKs are re-signed (APK Signature Scheme v2 + v3) with CarPatch's
on-device key, held in the Android Keystore, and installed together with Android's PackageInstaller.

## Limitations

- **First patch loses the app's data.** Android won't install an app over one signed with a
  different key, so the original must be uninstalled once. Re-patching later (for example after
  an app update) is an in-place update and keeps your data.
- **Play Store updates won't install over a patched app.** Update the app, then re-patch it.
- **Some apps won't run once patched:** apps that check their own signature, use Play Integrity,
  or depend on hardware DRM (Netflix, most banking apps, and similar).
- **Uninstalling CarPatch or clearing its storage deletes the signing key.** Patched apps then
  need a fresh first patch (uninstall + reinstall).
- Google can change Android Auto at any time and stop showing sideloaded apps.

## Rules

- Patched APKs are for your own use on your own phone. **Don't share or publish them.** They
  contain other developers' work, and patching doesn't give you any rights to it.
- You're responsible for following the terms of the apps you patch and your local road laws.

## Privacy

CarPatch has no analytics, no network access and collects no data. Everything happens on your phone.

## Building

Open the project in Android Studio and build the `app` module. The patching engine is a plain
Java module (`core`) that can also be run on a desktop JVM.

## Credits

- [ARSCLib](https://github.com/REAndroid/ARSCLib) by REAndroid (Apache-2.0) reads and writes
  binary manifests and resource tables. A build of version 1.4.0 is included in `core/libs`.
- [apksig](https://android.googlesource.com/platform/tools/apksig/) by Google (Apache-2.0) signs APKs.
- The Android Auto declarations follow the ones used by
  [AABrowser](https://github.com/kododake/AABrowser) (GPLv3).

CarPatch is an independent project. It contains no code from AA Anything or any other closed-source app.

## License

CarPatch is free software under the [GNU General Public License v3.0](LICENSE) or later.
