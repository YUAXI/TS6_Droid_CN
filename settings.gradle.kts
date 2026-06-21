pluginManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven {
                url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }

        google()
        mavenCentral()
    }
}

rootProject.name = "TS6_Droid"
include(":app")
