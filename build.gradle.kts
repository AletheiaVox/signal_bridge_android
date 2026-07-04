// Top-level build file
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    // 0.10.6 used groovy.util.XmlSlurper, removed in Gradle 9's Groovy 4 —
    // releaseOssLicensesTask failed with "groovy/util/XmlSlurper".
    id("com.google.android.gms.oss-licenses-plugin") version "0.12.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
