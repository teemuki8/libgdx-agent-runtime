import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "libgdx-agent-runtime"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "runtime-core",
    "runtime-libgdx",
    "runtime-protocol",
    "runtime-mcp",
    "runtime-fixtures",
)
