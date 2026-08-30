pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    // 显式把 plugins {} block 中声明的 plugin id 映射到真实的 Maven artifact
    // 坐标，避免 Gradle 去 Google Maven 查找 plugin marker（例如
    // com.android.application.gradle.plugin:8.6.1.pom）。因为 marker 并非
    // 所有 AGP 版本都有上传，在 CI 环境经常出现 "plugin ... was not found"。
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application",
                "com.android.library",
                "com.android.dynamic-feature",
                "com.android.test",
                "com.android.instantapp" -> {
                    // ⚠️ 版本号 MUST_SYNC：请与文件底部 plugins {} block 中
                    //   `id("com.android.application") version "x.y.z"` 保持一致！
                    // 为什么硬编码而不写顶层 const val？
                    //   - Gradle 会在脚本主体编译 "之前" 对 `plugins {}` block 做
                    //     一次单独的静态解析/抽取；顶层 const val 在那个阶段
                    //     根本不存在，会直接导致
                    //     `Unresolved reference: AGP_VERSION` 脚本编译失败。
                    // 为什么要有兜底？
                    //   - Flutter includeBuild + 根 build.gradle.kts 里
                    //     `subprojects { evaluationDependsOn(":app") }` 会走一条合成
                    //     的 plugin-apply 路径，此时 requested.version == null，
                    //     不能再 error()。
                    // ⚠️ 版本不能低于 Flutter stable 要求的最低 AGP（当前 CI 上
                    //   是 8.11.1），否则 flutter-gradle-plugin apply 时直接报错：
                    //   "Your project's Android Gradle Plugin version (x.y.z) is
                    //    lower than Flutter's minimum supported version 8.11.1"。
                    val agpVersion = requested.version ?: "8.11.1"
                    useModule("com.android.tools.build:gradle:$agpVersion")
                }
                "org.jetbrains.kotlin.android",
                "kotlin-android",
                "android",
                "org.jetbrains.kotlin.jvm",
                "kotlin",
                "jvm" -> {
                    // ⚠️ MUST_SYNC：请与 `plugins {}` block 中
                    //   `id("org.jetbrains.kotlin.android") version "x.y.z"` 保持一致。
                    // 2.2.20 是项目原始版本（本地已验证可用），不要随意降级。
                    val kotlinVersion = requested.version ?: "2.2.20"
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
                }
            }
        }
    }
}

dependencyResolutionManagement {
    // ⚠️ 对 Flutter 项目必须使用 PREFER_PROJECT（三种模式实测结论）：
    // - FAIL_ON_PROJECT_REPOS：配置期直接报错退出——Flutter 官方插件
    //   `dev.flutter.flutter-gradle-plugin` 在 apply 时会向各 project 动态注入
    //   maven 仓库，触发 "repository 'maven' was added by plugin ..."。
    // - PREFER_SETTINGS：配置期能过，但 Flutter 注入的本地 engine 产物仓
    //   （bin/cache/artifacts/engine/...，唯一提供 io.flutter:flutter_embedding_release
    //   的仓库）会被静默忽略 → 执行期报：
    //   "Could not find io.flutter:flutter_embedding_release:1.0.0-<hash>"
    // - PREFER_PROJECT（正确）：project 仓（含 Flutter engine 仓）优先参与解析，
    //   settings 仓（google/mavenCentral/JitPack 双镜像）作为兜底继续生效，
    //   SuperLyricApi 等 JitPack 依赖依然能解析。
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        // JitPack（主站 + 官方镜像，按顺序尝试）——用于 SuperLyricApi:3.4
        // 注意：JitPack 是首次请求时才在服务器端"懒构建"对应 GitHub tag/commit，
        // 第一次解析可能返回 404，等 60~120 秒后再请求即可拉到构建产物，
        // workflow 里对这种情况做了一次自动 sleep + refresh-dependencies 重试。
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://www.jitpack.io") }
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // Android Gradle Plugin 与 Kotlin 版本说明：
    // - AGP 8.11.1 是 CI 上 Flutter stable（subosito/flutter-action@v2 拉取的最新版）
    //   要求的最低 AGP 版本，低于它 flutter-gradle-plugin apply 时会直接报错：
    //   "Your project's AGP version is lower than Flutter's minimum supported
    //    version of Android Gradle Plugin version 8.11.1"。
    //   （历史教训：曾为规避 marker 解析把 AGP 降到 8.6.1/8.7.4，
    //    结果触发上述校验失败；真正解决 marker 问题的是上面
    //    resolutionStrategy.eachPlugin 的 useModule 显式映射。）
    // - AGP 9+ 才开始只读 new DSL；本项目在 gradle.properties 已配置
    //   `android.newDsl=false` 提前 opt-out，保持 Flutter 兼容。
    // - Kotlin 2.2.20 是项目原始版本（本地已验证），与 AGP 8.11.1 + Gradle 8.14 配合正常。
    // ⚠️ MUST_SYNC：上面 pluginManagement.resolutionStrategy.eachPlugin 里的
    //   fallback 硬编码 ("8.11.1" / "2.2.20") 必须与以下两行的 version 保持一致。
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

include(":app")
