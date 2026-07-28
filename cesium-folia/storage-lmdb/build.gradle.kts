plugins {
    `java-library`
}

dependencies {
    api(project(":storage-core"))
    implementation("org.lmdbjava:lmdbjava:0.9.2")
    implementation("com.github.luben:zstd-jni:1.5.7-6")
}
