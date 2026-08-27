val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

// ⚠️ 项目级仓库必须在这里声明完整集合。
// Gradle 的 dependencyResolutionManagement 三种模式都不会"合并"settings 与
// project 两级仓库（PREFER_SETTINGS 会忽略 project 仓、PREFER_PROJECT 会忽略
// settings 仓），而 Flutter 插件又必须在 project 级注入本地 engine 产物仓
// （提供 io.flutter:flutter_embedding_release）。因此唯一同时满足两者的方案：
// 使用 PREFER_PROJECT（见 settings.gradle.kts），并在这里把所有需要的远程仓库
// 声明到项目级，与 Flutter 注入的 engine 仓共存。
//  - google/mavenCentral：AndroidX、Kotlin 等常规依赖
//  - jitpack.io（主站 + www 镜像）：SuperLyricApi (com.github.HChenX:SuperLyricApi:3.4)
//    注意 JitPack 首次请求是懒构建（可能 404 后等 60~120s），workflow 已做自动重试。
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://www.jitpack.io") }
    }
}

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
