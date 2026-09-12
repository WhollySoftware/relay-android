plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.relay.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    // Needed only for RelayColors.kt (the shared theming type + LocalRelayColors/RelayTheme) —
    // relay-core is otherwise pure Kotlin. See RelayColors.kt's doc comment for why this small
    // slice of Compose lives here rather than in relay-ui: relay-call depends on relay-core but
    // not on relay-ui, so a type both need to read has to live at their common ancestor.
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    // api (not implementation): RelayColors exposes Color-typed properties and RelayTheme is a
    // public @Composable, so downstream modules (relay-ui, relay-call, app-demo) need these
    // types visible transitively just from depending on relay-core. Same reasoning extends
    // material-icons-extended: RelayIcons exposes ImageVector-typed properties sourced from it
    // (Reply/Forward/Logout/SearchOff/NotificationsOff/ChevronRight/RadioButtonUnchecked aren't
    // in material-icons-core).
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.material3)
    api(libs.androidx.material.icons.extended)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
