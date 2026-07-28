plugins {
    `java-library`
}

dependencies {
    api(project(":storage-core"))
    api(project(":storage-lmdb"))
}
