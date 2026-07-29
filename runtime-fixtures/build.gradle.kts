dependencies {
    implementation(project(":runtime-core"))
    implementation(project(":runtime-libgdx"))
    implementation(project(":runtime-protocol"))
    implementation(project(":runtime-mcp"))
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("fixture.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}

tasks.register<JavaExec>("runMcpFixture") {
    group = "application"
    description = "Runs the deterministic same-JVM runtime and stdio MCP fixture"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.teemuki8.libgdx.agent.runtime.fixtures.McpFixtureApplication")
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
