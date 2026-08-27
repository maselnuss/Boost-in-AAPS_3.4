plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.aps"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))

    testImplementation(project(":pump:virtual"))
    testImplementation(project(":shared:tests"))

    api(libs.androidx.appcompat)
    api(libs.androidx.swiperefreshlayout)
    api(libs.androidx.gridlayout)
    api(kotlin("reflect"))

    // APS (it should be androidTestImplementation but it doesn't work)
    api(libs.org.mozilla.rhino)

    //Logger
    api(libs.org.slf4j.api)

    // Health Connect — for HR ingest path (2026-06-03)
    implementation(libs.androidx.health.connect.client)

    // Play Services Activity Recognition — Konzept 8 GPS part (2026-08-27): live, phone-only
    // ON_BICYCLE enter/exit transitions (real incident this targets: cycling missed by the
    // HR+steps detector). Already used elsewhere in this repo (plugins/automation, plugins/sync) —
    // same artifact, not a new dep.
    implementation(libs.com.google.android.gms.playservices.location)

    ksp(libs.com.google.dagger.android.processor)
}