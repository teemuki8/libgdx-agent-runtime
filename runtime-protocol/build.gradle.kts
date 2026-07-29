dependencies {
    api(project(":runtime-core"))
    api(libs.jackson.databind)
    implementation(libs.jackson.datatype.jdk8)
    implementation(libs.jackson.datatype.jsr310)
}
