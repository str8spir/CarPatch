plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // ARSCLib 1.4.0 (Apache-2.0, https://github.com/REAndroid/ARSCLib), built from commit 089ec68.
    // Vendored so the exact version the patcher was tested against is used. See libs/ARSCLib-LICENSE.txt.
    api(files("libs/ARSCLib-1.4.0.jar"))
}
