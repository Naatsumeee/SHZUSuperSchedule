plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.shzu.superschedule"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shzu.superschedule"
        minSdk = 24
        targetSdk = 36
        versionCode = 16
        versionName = "BETA-v1.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.materialIconsExtended)

    implementation("androidx.activity:activity-compose:1.10.1")

    // MiuiX 的弹层体系（MiuixPopupHost）内部会调用 NavigationBackHandler，
    // 而它依赖 LocalNavigationEventDispatcherOwner。该 CompositionLocal
    // 只在 NavHost 里自动提供，本项目没引入 navigation-compose，
    // 所以需要在 MainActivity 里手动提供 —— 这里显式声明依赖，
    // 避免只靠 miuix 的传递依赖（版本漂移时容易编译不过）。
    implementation("androidx.navigationevent:navigationevent:1.1.2")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    // MIUI 风格 UI 库（Compose Multiplatform）
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.3")

    // 课表 HTML 解析
    implementation("org.jsoup:jsoup:1.18.3")
    // 数据持久化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // 解析器 / 周次计算的 JVM 单元测试（见 app/src/test/）。
    // 刻意只引 JUnit4：本机可用内存常 < 4GB，MockK / Robolectric 会明显加重
    // Gradle 测试进程负担，而这两个模块是纯逻辑，并不需要它们。
    testImplementation("junit:junit:4.13.2")
}
