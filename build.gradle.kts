plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Publish relay-core / relay-ui / relay-call to GitHub Packages so a host app depends on this SDK
// the same way it depends on any other Maven artifact — nothing here is shipped as source.
// Credentials are never hardcoded: a publisher supplies GITHUB_ACTOR/GITHUB_TOKEN (CI) or
// gpr.user/gpr.token in their own ~/.gradle/gradle.properties (local), and a *consuming* app
// only needs a GitHub PAT with the read:packages scope — never write access — configured the
// same way. See README.md's "Consuming the published SDK" section.
val publishedModules = listOf("relay-core", "relay-ui", "relay-call")

subprojects {
    if (name !in publishedModules) return@subprojects

    apply(plugin = "maven-publish")

    afterEvaluate {
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("release") {
                    groupId = project.findProperty("RELAY_GROUP_ID") as? String ?: "dev.relay"
                    artifactId = project.name
                    version = project.findProperty("RELAY_VERSION_NAME") as? String ?: "0.0.0"
                    afterEvaluate { from(components.findByName("release")) }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/WhollySoftware/relay-android")
                    credentials {
                        username = (findProperty("gpr.user") as String?)
                            ?: System.getenv("GITHUB_ACTOR")
                        password = (findProperty("gpr.token") as String?)
                            ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}
