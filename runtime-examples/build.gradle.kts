plugins {
    application
}

// Consumer examples are compiled and tested but never published.
dependencies {
    implementation(project(":runtime-core"))
    implementation(project(":runtime-box2d"))
    implementation(project(":runtime-libgdx"))
    implementation(project(":runtime-protocol"))
    implementation(project(":runtime-mcp"))
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    runtimeOnly(
        "com.badlogicgames.gdx:gdx-box2d-platform:${libs.versions.box2d.get()}:natives-desktop",
    )
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

application {
    applicationName = "runtime-mcp-example"
    mainClass = "io.github.teemuki8.libgdx.agent.runtime.examples.SameJvmMcpApplication"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.named("installDist"))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("example.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    systemProperty(
        "example.mcp.launcher",
        project.file("run-mcp-example-xvfb.sh").absolutePath,
    )
}
