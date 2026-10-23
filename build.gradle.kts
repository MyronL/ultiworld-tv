plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this only picks the Kotlin version it uses.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}
