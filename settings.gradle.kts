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
        exclusiveContent {
            forRepository {
                ivy {
                    name = "RedrainCastNativeReleases"
                    url = uri("https://github.com/arisewanggithub/RedrainCast-Native/releases/download")
                    patternLayout {
                        artifact("[module]-[revision]/[artifact]-[revision].[ext]")
                        artifact("redraincast-ffmpeg-android-pcm-[revision]/[artifact]-[revision].[ext]")
                        artifact("redraincast-ffmpeg-[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("dev.redraincast.native", "ffmpeg-pcm-android") }
        }
    }
}
rootProject.name = "RedrainCast"
include(":app")
