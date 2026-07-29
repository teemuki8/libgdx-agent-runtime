plugins {
    application
}

dependencies {
    api(project(":runtime-protocol"))
    implementation(libs.jackson.databind)
    api(libs.mcp)
}

application {
    mainClass.set("io.github.teemuki8.libgdx.agent.runtime.mcp.Main")
}
