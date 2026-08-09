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
        "com.badlogicgames.gdx:gdx-box2d-platform:${libs.versions.gdx.get()}:natives-desktop",
    )
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("example.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
