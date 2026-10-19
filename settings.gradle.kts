pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "scc-anchored"
include(":server")
// The server Docker build intentionally skips Android configuration; desktop IDE builds include the app.
if (System.getenv("SCC_SERVER_ONLY") != "true") include(":composeApp")
