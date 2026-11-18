pluginManagement {
    repositories {
        // pumpkin-api-kt is not published yet; build it locally (see README).
        mavenLocal {
            content { includeGroupAndSubgroups("org.pumpkinmc") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "wRestore"
include(":domain")
