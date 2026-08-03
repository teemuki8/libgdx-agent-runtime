import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugins.signing.SigningExtension
import java.util.zip.ZipFile

val publishedModules = setOf(
    "runtime-core",
    "runtime-libgdx",
    "runtime-protocol",
    "runtime-mcp",
)
val artifactNames = mapOf(
    "runtime-core" to "agent-runtime-core",
    "runtime-libgdx" to "agent-runtime-libgdx",
    "runtime-protocol" to "agent-runtime-protocol",
    "runtime-mcp" to "agent-runtime-mcp",
)
val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT")
val repositoryUrl = "https://github.com/teemuki8/libgdx-agent-runtime"
val mavenCentralStagingUrl =
    "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
val junitJupiter = libs.junit.jupiter
val junitPlatformLauncher = libs.junit.platform.launcher

allprojects {
    group = "io.github.teemuki8"
    version = releaseVersion.get()
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")
    apply(plugin = "jacoco")

    dependencyLocking {
        lockAllConfigurations()
    }

    dependencies {
        add("testImplementation", junitJupiter)
        add("testRuntimeOnly", junitPlatformLauncher)
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
        withJavadocJar()
    }

    extensions.configure<CheckstyleExtension> {
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<Javadoc>().configureEach {
        isFailOnError = true
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xmaxwarns", "1000")
            addBooleanOption("Xdoclint:all,-missing", true)
            addBooleanOption("Werror", true)
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<Jar>().configureEach {
        manifest.attributes["Implementation-Version"] = project.version
    }

    if (name in publishedModules) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        val publicationArchives = tasks.withType<Jar>()
        publicationArchives.configureEach {
            from(rootProject.file("LICENSE")) {
                into("META-INF")
            }
            from(rootProject.file("NOTICE")) {
                into("META-INF")
            }
        }

        val verifyPublicationArchives = tasks.register("verifyPublicationArchives") {
            group = "verification"
            description = "Verifies that every publication archive contains licensing notices"
            dependsOn(publicationArchives)
            doLast {
                publicationArchives.forEach { archiveTask ->
                    val archiveFile = archiveTask.archiveFile.get().asFile
                    ZipFile(archiveFile).use { archive ->
                        listOf("META-INF/LICENSE", "META-INF/NOTICE").forEach { entry ->
                            check(archive.getEntry(entry) != null) {
                                "${archiveFile.name} does not contain $entry"
                            }
                        }
                    }
                }
            }
        }
        tasks.named("check") {
            dependsOn(verifyPublicationArchives)
        }

        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("mavenJava") {
                from(components["java"])
                artifactId = artifactNames.getValue(project.name)
                pom {
                    name.set("libGDX Agent Runtime ${project.name}")
                    description.set(
                        "Bounded semantic runtime inspection for live libGDX applications",
                    )
                    url.set(repositoryUrl)
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("teemuki8")
                            name.set("Teemu Jääskeläinen")
                            email.set("teemuki8@users.noreply.github.com")
                            url.set("https://github.com/teemuki8")
                        }
                    }
                    scm {
                        connection.set("scm:git:$repositoryUrl.git")
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/teemuki8/libgdx-agent-runtime.git",
                        )
                        url.set(repositoryUrl)
                    }
                }
            }
            repositories {
                maven {
                    name = "mavenCentral"
                    url = uri(mavenCentralStagingUrl)
                    credentials {
                        username = providers.environmentVariable("MAVEN_CENTRAL_USERNAME").orNull
                        password = providers.environmentVariable("MAVEN_CENTRAL_PASSWORD").orNull
                    }
                }
            }
        }

        extensions.configure<SigningExtension> {
            val key = providers.environmentVariable("MAVEN_SIGNING_KEY")
            val password = providers.environmentVariable("MAVEN_SIGNING_PASSWORD")
            if (key.isPresent && password.isPresent) {
                useInMemoryPgpKeys(key.get(), password.get())
                sign(extensions.getByType<PublishingExtension>().publications["mavenJava"])
            }
        }
    }
}

tasks.register("javadoc") {
    group = "documentation"
    description = "Generates warning-free Javadocs for all published modules"
    dependsOn(publishedModules.map { project(":$it").tasks.named("javadoc") })
}
