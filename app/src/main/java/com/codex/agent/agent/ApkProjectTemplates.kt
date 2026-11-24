package com.codex.agent.agent

import java.io.File

/**
 * Geradores de projetos Android Studio completos, prontos para:
 * 1) Compilação DIRETA no dispositivo (template java_xml — pipeline aapt2/javac/d8, sem dependências
 *    externas: UI programática com android.* puro);
 * 2) Abertura no Android Studio / build Gradle (ambos os templates geram a estrutura Gradle completa).
 */
object ApkProjectTemplates {

    data class ScaffoldSpec(
        val projectName: String,
        val packageName: String,
        val minSdk: Int = 24,
        val targetSdk: Int = 34,
        val versionCode: Int = 1,
        val versionName: String = "1.0.0",
        val permissions: List<String> = emptyList()
    )

    fun validatePackageName(pkg: String): Boolean =
        Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(pkg)

    fun sanitizeDirName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "MeuApp" }

    /** Gera o projeto e retorna a lista de arquivos criados. */
    fun scaffold(spec: ScaffoldSpec, root: File, template: String): List<File> {
        require(validatePackageName(spec.packageName)) {
            "package_name inválido: '${spec.packageName}'. Use ex.: com.exemplo.meuapp"
        }
        root.mkdirs()
        return when (template.lowercase()) {
            "kotlin_compose", "compose" -> composeProject(spec, root)
            "iptv" -> iptvProject(spec, root)
            "webview" -> webviewProject(spec, root)
            "game" -> gameProject(spec, root)
            "service" -> serviceProject(spec, root)
            else -> javaXmlProject(spec, root)
        }
    }

    private fun write(root: File, relative: String, content: String): File =
        File(root, relative).apply {
            parentFile?.mkdirs()
            writeText(content.trimIndent().trimStart('\n') + "\n")
        }

    private fun manifestXml(spec: ScaffoldSpec): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        appendLine("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">")
        spec.permissions.forEach { permission ->
            appendLine("    <uses-permission android:name=\"android.permission.$permission\" />")
        }
        appendLine("""
            <application
                android:label="@string/app_name"
                android:icon="@drawable/ic_launcher"
                android:theme="@style/AppTheme"
                android:allowBackup="true">
                <activity
                    android:name=".MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
        """.trimIndent())
    }.trimEnd() // package é injetado pelo build direto quando necessário (AGP usa namespace)

    // ------------------------------------------------------------------
    // Template 1 — Java + XML (compilável no dispositivo, sem androidx)
    // ------------------------------------------------------------------

    private fun javaXmlProject(spec: ScaffoldSpec, root: File): List<File> {
        val pkgPath = spec.packageName.replace('.', '/')
        val files = mutableListOf<File>()

        files += write(root, "settings.gradle.kts", """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "${spec.projectName}"
            include(":app")
        """)

        files += write(root, "build.gradle.kts", """
            plugins {
                id("com.android.application") version "8.7.2" apply false
            }
        """)

        files += write(root, "gradle.properties", """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
            android.nonTransitiveRClass=true
        """)

        files += write(root, "gradle/wrapper/gradle-wrapper.properties", """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """)

        files += write(root, ".gitignore", """
            *.iml
            .gradle/
            .idea/
            build/
            local.properties
            .codex_build/
        """)

        files += write(root, "README.md", """
            # ${spec.projectName}

            Projeto Android gerado pelo Codex Agent.

            - Package: `${spec.packageName}`
            - minSdk ${spec.minSdk} / targetSdk ${spec.targetSdk} / versão ${spec.versionName}
            - Template: Java + XML (UI programática, zero dependências externas — compilável
              no próprio dispositivo via pipeline direto `aapt2 → javac → d8 → apksigner`).
            - Para build no Android Studio: abra esta pasta e sincronize o Gradle.
        """)

        files += write(root, "app/build.gradle.kts", """
            plugins {
                id("com.android.application")
            }

            android {
                namespace = "${spec.packageName}"
                compileSdk = 35

                defaultConfig {
                    applicationId = "${spec.packageName}"
                    minSdk = ${spec.minSdk}
                    targetSdk = ${spec.targetSdk}
                    versionCode = ${spec.versionCode}
                    versionName = "${spec.versionName}"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        """)

        files += write(root, "app/src/main/AndroidManifest.xml", manifestXml(spec))

        files += write(root, "app/src/main/java/$pkgPath/MainActivity.java", """
            package ${spec.packageName};

            import android.app.Activity;
            import android.graphics.Color;
            import android.os.Bundle;
            import android.view.Gravity;
            import android.widget.Button;
            import android.widget.LinearLayout;
            import android.widget.TextView;

            public class MainActivity extends Activity {

                private int counter = 0;

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);

                    LinearLayout layout = new LinearLayout(this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setGravity(Gravity.CENTER);
                    layout.setBackgroundColor(Color.parseColor("#0D1117"));
                    layout.setPadding(48, 48, 48, 48);

                    TextView title = new TextView(this);
                    title.setText(R.string.app_name);
                    title.setTextSize(26f);
                    title.setTextColor(Color.parseColor("#58A6FF"));

                    TextView counterView = new TextView(this);
                    counterView.setText(String.valueOf(counter));
                    counterView.setTextSize(56f);
                    counterView.setTextColor(Color.parseColor("#E6EDF3"));

                    Button incrementButton = new Button(this);
                    incrementButton.setText(R.string.increment);
                    incrementButton.setOnClickListener(v -> {
                        counter++;
                        counterView.setText(String.valueOf(counter));
                    });

                    layout.addView(title);
                    layout.addView(counterView);
                    layout.addView(incrementButton);
                    setContentView(layout);
                }
            }
        """)

        files += write(root, "app/src/main/res/values/strings.xml", """
            <resources>
                <string name="app_name">${spec.projectName}</string>
                <string name="increment">Incrementar</string>
            </resources>
        """)

        files += write(root, "app/src/main/res/values/colors.xml", """
            <resources>
                <color name="primary">#58A6FF</color>
                <color name="primaryDark">#0D1117</color>
                <color name="accent">#3FB950</color>
            </resources>
        """)

        files += write(root, "app/src/main/res/values/themes.xml", """
            <resources>
                <style name="AppTheme" parent="android:Theme.Material.Light.NoActionBar">
                    <item name="android:colorPrimary">@color/primary</item>
                    <item name="android:colorPrimaryDark">@color/primaryDark</item>
                    <item name="android:colorAccent">@color/accent</item>
                    <item name="android:windowBackground">@color/primaryDark</item>
                </style>
            </resources>
        """)

        files += write(root, "app/src/main/res/drawable/ic_launcher.xml", launcherIconVector())

        return files
    }

    // ------------------------------------------------------------------
    // Template 2 — Kotlin + Jetpack Compose (Android Studio / Gradle)
    // ------------------------------------------------------------------

    private fun composeProject(spec: ScaffoldSpec, root: File): List<File> {
        val pkgPath = spec.packageName.replace('.', '/')
        val files = mutableListOf<File>()

        files += write(root, "settings.gradle.kts", """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "${spec.projectName}"
            include(":app")
        """)

        files += write(root, "build.gradle.kts", """
            plugins {
                id("com.android.application") version "8.7.2" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
            }
        """)

        files += write(root, "gradle.properties", """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
        """)

        files += write(root, "gradle/wrapper/gradle-wrapper.properties", """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """)

        files += write(root, ".gitignore", """
            *.iml
            .gradle/
            .idea/
            build/
            local.properties
            .codex_build/
        """)

        files += write(root, "README.md", """
            # ${spec.projectName}

            Projeto Android (Kotlin + Jetpack Compose) gerado pelo Codex Agent.

            - Package: `${spec.packageName}`
            - minSdk ${spec.minSdk} / targetSdk ${spec.targetSdk} / versão ${spec.versionName}
            - Abra no Android Studio e sincronize o Gradle para compilar.
        """)

        files += write(root, "app/build.gradle.kts", """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
                id("org.jetbrains.kotlin.plugin.compose")
            }

            android {
                namespace = "${spec.packageName}"
                compileSdk = 35

                defaultConfig {
                    applicationId = "${spec.packageName}"
                    minSdk = ${spec.minSdk}
                    targetSdk = ${spec.targetSdk}
                    versionCode = ${spec.versionCode}
                    versionName = "${spec.versionName}"
                }

                buildFeatures { compose = true }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = "17" }
            }

            dependencies {
                implementation(platform("androidx.compose:compose-bom:2024.10.01"))
                implementation("androidx.activity:activity-compose:1.9.2")
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.material3:material3")
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
            }
        """)

        files += write(root, "app/src/main/AndroidManifest.xml", manifestXml(spec))

        files += write(root, "app/src/main/java/$pkgPath/MainActivity.kt", """
            package ${spec.packageName}

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Button
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableIntStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.res.stringResource
            import androidx.compose.ui.unit.dp
            import androidx.compose.ui.unit.sp

            class MainActivity : ComponentActivity() {

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MaterialTheme {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                var counter by remember { mutableIntStateOf(0) }
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(text = stringResource(R.string.app_name), fontSize = 24.sp)
                                    Text(text = counter.toString(), fontSize = 56.sp)
                                    Button(onClick = { counter++ }) {
                                        Text(text = stringResource(R.string.increment))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        """)

        files += write(root, "app/src/main/res/values/strings.xml", """
            <resources>
                <string name="app_name">${spec.projectName}</string>
                <string name="increment">Incrementar</string>
            </resources>
        """)

        files += write(root, "app/src/main/res/drawable/ic_launcher.xml", launcherIconVector())

        return files
    }

    // ------------------------------------------------------------------
    // Template 3 — IPTV (WebView + ExoPlayer + M3U + fullscreen)
    // ------------------------------------------------------------------

    private fun iptvProject(spec: ScaffoldSpec, root: File): List<File> {
        val pkgPath = spec.packageName.replace('.', '/')
        val files = mutableListOf<File>()

        files += write(root, "settings.gradle.kts", settingsGradle(spec.projectName))
        files += write(root, "build.gradle.kts", rootBuildGradle())
        files += write(root, "gradle.properties", gradleProps())
        files += write(root, "gradle/wrapper/gradle-wrapper.properties", wrapperProps())
        files += write(root, ".gitignore", gitignore())
        files += write(root, "README.md", """
            # ${spec.projectName} (IPTV)
            Projeto IPTV gerado pelo Codex Agent.
            - Package: ${spec.packageName} | minSdk ${spec.minSdk} / targetSdk ${spec.targetSdk}
            - Template: IPTV (ExoPlayer + WebView + fullscreen + M3U parser)
            - Abra no Android Studio e sincronize o Gradle.
        """)

        files += write(root, "app/build.gradle.kts", """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android {
                namespace = "${spec.packageName}"
                compileSdk = 35
                defaultConfig {
                    applicationId = "${spec.packageName}"
                    minSdk = ${spec.minSdk}
                    targetSdk = ${spec.targetSdk}
                    versionCode = ${spec.versionCode}
                    versionName = "${spec.versionName}"
                }
                buildFeatures { viewBinding = true }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = "17" }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.media3:media3-exoplayer:1.4.1")
                implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
                implementation("androidx.media3:media3-ui:1.4.1")
                implementation("androidx.webkit:webkit:1.12.1")
                implementation("com.google.android.material:material:1.12.0")
                implementation("androidx.constraintlayout:constraintlayout:2.1.4")
            }
        """)

        files += write(root, "app/src/main/AndroidManifest.xml", iptvManifest(spec))
        files += write(root, "app/src/main/java/$pkgPath/MainActivity.kt", iptvMainActivity(spec))
        files += write(root, "app/src/main/java/$pkgPath/PlayerActivity.kt", iptvPlayerActivity(spec))
        files += write(root, "app/src/main/java/$pkgPath/M3uParser.kt", iptvM3uParser(spec))
        files += write(root, "app/src/main/res/layout/activity_main.xml", iptvMainLayout())
        files += write(root, "app/src/main/res/layout/activity_player.xml", iptvPlayerLayout())
        files += write(root, "app/src/main/res/values/strings.xml", stringsXml(spec.projectName, listOf(
            "app_name" to spec.projectName,
            "channels" to "Canais",
            "play" to "Reproduzir",
            "loading" to "Carregando...",
            "error_stream" to "Erro ao carregar stream"
        )))
        files += write(root, "app/src/main/res/values/colors.xml", colorsXml())
        files += write(root, "app/src/main/res/values/themes.xml", themesXml())
        files += write(root, "app/src/main/res/drawable/ic_launcher.xml", launcherIconVector())
        return files
    }

    // ------------------------------------------------------------------
    // Template 4 — WebView (navegador embutido / app web)
    // ------------------------------------------------------------------

    private fun webviewProject(spec: ScaffoldSpec, root: File): List<File> {
        val pkgPath = spec.packageName.replace('.', '/')
        val files = mutableListOf<File>()

        files += write(root, "settings.gradle.kts", settingsGradle(spec.projectName))
        files += write(root, "build.gradle.kts", rootBuildGradle())
        files += write(root, "gradle.properties", gradleProps())
        files += write(root, "gradle/wrapper/gradle-wrapper.properties", wrapperProps())
        files += write(root, ".gitignore", gitignore())
        files += write(root, "README.md", """
            # ${spec.projectName} (WebView)
            App WebView gerado pelo Codex Agent.
            - Package: ${spec.packageName} | minSdk ${spec.minSdk} / targetSdk ${spec.targetSdk}
        """)

        files += write(root, "app/build.gradle.kts", """
            plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
            android {
                namespace = "${spec.packageName}"
                compileSdk = 35
                defaultConfig {
                    applicationId = "${spec.packageName}"
                    minSdk = ${spec.minSdk}
                    targetSdk = ${spec.targetSdk}
                    versionCode = ${spec.versionCode}
                    versionName = "${spec.versionName}"
                }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
                kotlinOptions { jvmTarget = "17" }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.webkit:webkit:1.12.1")
                implementation("com.google.android.material:material:1.12.0")
            }
        """)

        files += write(root, "app/src/main/AndroidManifest.xml", webviewManifest(spec))
        files += write(root, "app/src/main/java/$pkgPath/MainActivity.kt", webviewMainActivity(spec))
        files += write(root, "app/src/main/res/layout/activity_main.xml", webviewMainLayout())
        files += write(root, "app/src/main/res/values/strings.xml", stringsXml(spec.projectName, listOf(
            "app_name" to spec.projectName,
            "loading" to "Carregando..."
        )))
        files += write(root, "app/src/main/res/values/colors.xml", colorsXml())
        files += write(root, "app/src/main/res/values/themes.xml", themesXml())
        files += write(root, "app/src/main/res/drawable/ic_launcher.xml", launcherIconVector())
        return files
    }

    // ------------------------------------------------------------------
    // Template 5 — Game (SurfaceView + game loop + assets)
    // ------------------------------------------------------------------

    private fun gameProject(spec: ScaffoldSpec, root: File): List<File> {
        val pkgPath = spec.packageName.replace('.', '/')
        val files = mutableListOf<File>()

        files += write(root, "settings.gradle.kts", settingsGradle(spec.projectName))
        files += write(root, "build.gradle.kts", rootBuildGradle())
        files += write(root, "gradle.properties", gradleProps())
        files += write(root, "gradle/wrapper/gradle-wrapper.properties", wrapperProps())
        files += write(root, ".gitignore", gitignore())
        files += write(root, "README.md", """
            # ${spec.projectName} (Game)
            Jogo Android gerado pelo Codex Agent (SurfaceView + game loop).
            - Package: ${spec.packageName} | minSdk ${spec.minSdk} / targetSdk ${spec.targetSdk}
        """)

        files += write(root, "app/build.gradle.kts", """
            plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
            android {
                namespace = "${spec.packageName}"
                compileSdk = 35
                defaultConfig {
                    applicationId = "${spec.packageName}"
                    minSdk = ${spec.minSdk}
                    targetSdk = ${spec.targetSdk}
                    versionCode = ${spec.versionCode}
                    versionName = "${spec.versionName}"
                }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
                kotlinOptions { jvmTarget = "17" }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.appcompat:appcompat:1.7.0")
            }
        """)

        files += write(root, "app/src/main/AndroidManifest.xml", gameManifest(spec))
        files += write(root, "app/src/main/java/$pkgPath/MainActivity.kt", gameMainActivity(spec))
        files += write(root, "app/src/main/java/$pkgPath/GameView.kt", gameView(spec))
        files += write(root, "app/src/main/res/values/strings.xml", stringsXml(spec.projectName, listOf("app_name" to spec.projectName)))
        files += write(root, "app/src/main/res/values/colors.xml", colorsXml())
        files += write(root, "app/src/main/res/values/themes.xml", themesXml())
        files += write(root, "app/src/main/res/drawable/ic_launcher.xml", launcherIconVector())
        return files
    }

    // ------------------------------------------------------------------
    // Template 6 — Service/Background (foreground service + notification)
    // ------------------------------------------------------------------

    private fun serviceProject(spec: ScaffoldSpec, root: File): List<File> {
        val pkgPath = spec.packageName.replace('.', '/')
        val files = mutableListOf<File>()

        files += write(root, "settings.gradle.kts", settingsGradle(spec.projectName))
        files += write(root, "build.gradle.kts", rootBuildGradle())
        files += write(root, "gradle.properties", gradleProps())
        files += write(root, "gradle/wrapper/gradle-wrapper.properties", wrapperProps())
        files += write(root, ".gitignore", gitignore())
        files += write(root, "README.md", """
            # ${spec.projectName} (Service)
            App com Foreground Service gerado pelo Codex Agent.
            - Package: ${spec.packageName} | minSdk ${spec.minSdk} / targetSdk ${spec.targetSdk}
        """)

        files += write(root, "app/build.gradle.kts", """
            plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
            android {
                namespace = "${spec.packageName}"
                compileSdk = 35
                defaultConfig {
                    applicationId = "${spec.packageName}"
                    minSdk = ${spec.minSdk}
                    targetSdk = ${spec.targetSdk}
                    versionCode = ${spec.versionCode}
                    versionName = "${spec.versionName}"
                }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
                kotlinOptions { jvmTarget = "17" }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.appcompat:appcompat:1.7.0")
            }
        """)

        files += write(root, "app/src/main/AndroidManifest.xml", serviceManifest(spec))
        files += write(root, "app/src/main/java/$pkgPath/MainActivity.kt", serviceMainActivity(spec))
        files += write(root, "app/src/main/java/$pkgPath/MyForegroundService.kt", serviceForegroundService(spec))
        files += write(root, "app/src/main/res/values/strings.xml", stringsXml(spec.projectName, listOf(
            "app_name" to spec.projectName,
            "start_service" to "Iniciar Serviço",
            "stop_service" to "Parar Serviço"
        )))
        files += write(root, "app/src/main/res/values/colors.xml", colorsXml())
        files += write(root, "app/src/main/res/values/themes.xml", themesXml())
        files += write(root, "app/src/main/res/drawable/ic_launcher.xml", launcherIconVector())
        return files
    }

    // ------------------------------------------------------------------
    // Shared helpers for templates
    // ------------------------------------------------------------------

    private fun settingsGradle(name: String) = """
        pluginManagement {
            repositories { google(); mavenCentral(); gradlePluginPortal() }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories { google(); mavenCentral() }
        }
        rootProject.name = "$name"
        include(":app")
    """

    private fun rootBuildGradle() = """
        plugins {
            id("com.android.application") version "8.7.2" apply false
        }
    """

    private fun gradleProps() = """
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        android.useAndroidX=true
        android.nonTransitiveRClass=true
    """

    private fun wrapperProps() = """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
    """

    private fun gitignore() = """
        *.iml
        .gradle/
        .idea/
        build/
        local.properties
        .codex_build/
    """

    private fun colorsXml() = """
        <resources>
            <color name="primary">#58A6FF</color>
            <color name="primaryDark">#0D1117</color>
            <color name="accent">#3FB950</color>
        </resources>
    """

    private fun themesXml() = """
        <resources>
            <style name="AppTheme" parent="android:Theme.Material.Light.NoActionBar">
                <item name="android:colorPrimary">@color/primary</item>
                <item name="android:colorPrimaryDark">@color/primaryDark</item>
                <item name="android:colorAccent">@color/accent</item>
            </style>
        </resources>
    """

    private fun stringsXml(appName: String, extras: List<Pair<String, String>>) = buildString {
        appendLine("<resources>")
        appendLine("    <string name=\"app_name\">$appName</string>")
        extras.forEach { (k, v) -> appendLine("    <string name=\"$k\">$v</string>") }
        appendLine("</resources>")
    }

    // ------------------------------------------------------------------
    // Manifests por template
    // ------------------------------------------------------------------

    private fun iptvManifest(spec: ScaffoldSpec) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
            <uses-permission android:name="android.permission.WAKE_LOCK" />
            <application
                android:label="@string/app_name"
                android:icon="@drawable/ic_launcher"
                android:theme="@style/AppTheme"
                android:allowBackup="true"
                android:usesCleartextTraffic="true">
                <activity android:name=".MainActivity" android:exported="true" android:screenOrientation="landscape">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
                <activity android:name=".PlayerActivity" android:exported="false" android:screenOrientation="landscape" android:configChanges="orientation|screenSize" />
            </application>
        </manifest>
    """

    private fun webviewManifest(spec: ScaffoldSpec) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
            <application
                android:label="@string/app_name"
                android:icon="@drawable/ic_launcher"
                android:theme="@style/AppTheme"
                android:allowBackup="true"
                android:usesCleartextTraffic="true">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """

    private fun gameManifest(spec: ScaffoldSpec) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.WAKE_LOCK" />
            <application
                android:label="@string/app_name"
                android:icon="@drawable/ic_launcher"
                android:theme="@style/AppTheme"
                android:allowBackup="true">
                <activity android:name=".MainActivity" android:exported="true" android:screenOrientation="landscape">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """

    private fun serviceManifest(spec: ScaffoldSpec) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
            <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
            <uses-permission android:name="android.permission.WAKE_LOCK" />
            <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
            <application
                android:label="@string/app_name"
                android:icon="@drawable/ic_launcher"
                android:theme="@style/AppTheme"
                android:allowBackup="true">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
                <service android:name=".MyForegroundService" android:exported="false" android:foregroundServiceType="dataSync" />
            </application>
        </manifest>
    """

    // ------------------------------------------------------------------
    // Código-fonte por template
    // ------------------------------------------------------------------

    private fun iptvMainActivity(spec: ScaffoldSpec) = """
        package ${spec.packageName}

        import android.content.Intent
        import android.os.Bundle
        import android.widget.ArrayAdapter
        import android.widget.ListView
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            private val channels = mutableListOf<Pair<String, String>>()

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_main)

                val listView = findViewById<ListView>(R.id.channelList)
                channels.add("Sample Channel 1" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
                channels.add("Sample Channel 2" to "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8")

                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, channels.map { it.first })
                listView.adapter = adapter
                listView.setOnItemClickListener { _, _, pos, _ ->
                    val intent = Intent(this, PlayerActivity::class.java)
                    intent.putExtra("url", channels[pos].second)
                    intent.putExtra("name", channels[pos].first)
                    startActivity(intent)
                }
            }
        }
    """

    private fun iptvPlayerActivity(spec: ScaffoldSpec) = """
        package ${spec.packageName}

        import android.net.Uri
        import android.os.Bundle
        import androidx.appcompat.app.AppCompatActivity
        import androidx.media3.common.MediaItem
        import androidx.media3.exoplayer.ExoPlayer
        import androidx.media3.exoplayer.hls.HlsMediaSource
        import androidx.media3.ui.PlayerView

        class PlayerActivity : AppCompatActivity() {
            private var player: ExoPlayer? = null

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_player)
                val url = intent.getStringExtra("url") ?: return
                val name = intent.getStringExtra("name") ?: "Player"

                supportActionBar?.title = name
                supportActionBar?.setDisplayHomeAsUpEnabled(true)

                player = ExoPlayer.Builder(this).build()
                findViewById<PlayerView>(R.id.playerView).player = player
                val mediaItem = MediaItem.fromUri(Uri.parse(url))
                player?.setMediaItem(mediaItem)
                player?.prepare()
                player?.playWhenReady = true
            }

            override fun onStop() {
                super.onStop()
                player?.release()
                player = null
            }
        }
    """

    private fun iptvM3uParser(spec: ScaffoldSpec) = """
        package ${spec.packageName}

        object M3uParser {
            fun parse(content: String): List<Pair<String, String>> {
                val result = mutableListOf<Pair<String, String>>()
                var name = ""
                content.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXTINF")) {
                        name = trimmed.substringAfter(",").trim()
                    } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        result.add(name.ifBlank { "Canal" } to trimmed)
                        name = ""
                    }
                }
                return result
            }
        }
    """

    private fun iptvMainLayout() = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical">
            <ListView
                android:id="@+id/channelList"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />
        </LinearLayout>
    """

    private fun iptvPlayerLayout() = """
        <?xml version="1.0" encoding="utf-8"?>
        <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <androidx.media3.ui.PlayerView
                android:id="@+id/playerView"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />
        </FrameLayout>
    """

    private fun webviewMainActivity(spec: ScaffoldSpec) = """
        package ${spec.packageName}

        import android.os.Bundle
        import android.webkit.WebView
        import android.webkit.WebViewClient
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_main)
                val webView = findViewById<WebView>(R.id.webView)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.webViewClient = WebViewClient()
                webView.loadUrl("https://www.google.com")
            }
        }
    """

    private fun webviewMainLayout() = """
        <?xml version="1.0" encoding="utf-8"?>
        <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <WebView
                android:id="@+id/webView"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />
        </FrameLayout>
    """

    private fun gameMainActivity(spec: ScaffoldSpec) = """
        package ${spec.packageName}

        import android.os.Bundle
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(GameView(this))
            }
        }
    """

    private fun gameView(spec: ScaffoldSpec) = """
        package ${spec.packageName}

        import android.content.Context
        import android.graphics.Canvas
        import android.graphics.Color
        import android.graphics.Paint
        import android.view.SurfaceHolder
        import android.view.SurfaceView

        class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
            private val paint = Paint().apply { color = Color.WHITE; textSize = 48f }
            private var running = true
            private var score = 0

            init {
                holder.addCallback(this)
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                Thread {
                    while (running) {
                        val canvas = holder.lockCanvas() ?: continue
                        canvas.drawColor(Color.parseColor("#0D1117"))
                        canvas.drawText("Score: ${'$'}score", 50f, 100f, paint)
                        score++
                        holder.unlockCanvasAndPost(canvas)
                        Thread.sleep(16)
                    }
                }.start()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { running = false }
        }
    """

    private fun serviceMainActivity(spec: ScaffoldSpec) = """
        package ${spec.packageName}

        import android.content.Intent
        import android.os.Bundle
        import android.widget.Button
        import android.widget.LinearLayout
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val startBtn = Button(this).apply { text = getString(R.string.start_service) }
                val stopBtn = Button(this).apply { text = getString(R.string.stop_service) }
                startBtn.setOnClickListener { startForegroundService(Intent(this, MyForegroundService::class.java)) }
                stopBtn.setOnClickListener { stopService(Intent(this, MyForegroundService::class.java)) }
                layout.addView(startBtn)
                layout.addView(stopBtn)
                setContentView(layout)
            }
        }
    """

    private fun serviceForegroundService(spec: ScaffoldSpec) = """
        package ${spec.packageName}

        import android.app.Notification
        import android.app.NotificationChannel
        import android.app.NotificationManager
        import android.app.Service
        import android.content.Intent
        import android.os.Build
        import android.os.IBinder

        class MyForegroundService : Service() {
            override fun onCreate() {
                super.onCreate()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel("svc", "Serviço", NotificationManager.IMPORTANCE_LOW)
                    (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
                }
            }
            override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                val notif = Notification.Builder(this, "svc")
                    .setContentTitle("${spec.projectName}")
                    .setContentText("Serviço em execução")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .build()
                startForeground(1, notif)
                return START_STICKY
            }
            override fun onBind(intent: Intent?): IBinder? = null
        }
    """

    private fun launcherIconVector(): String = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
            <path android:fillColor="#0D1117" android:pathData="M0,0h108v108h-108z" />
            <path
                android:fillColor="#3FB950"
                android:pathData="M54,20 a34,34 0 1 0 0.1,0 z" />
            <path
                android:pathData="M38,46 L50,58 L38,70"
                android:strokeColor="#0D1117"
                android:strokeWidth="6"
                android:strokeLineCap="round"
                android:strokeLineJoin="round" />
            <path
                android:pathData="M56,70 H72"
                android:strokeColor="#0D1117"
                android:strokeWidth="6"
                android:strokeLineCap="round" />
        </vector>
    """

    fun describe(spec: ScaffoldSpec, root: File, template: String, files: List<File>): String = buildString {
        appendLine("Projeto criado com sucesso em: ${root.absolutePath}")
        appendLine("Template: $template | Package: ${spec.packageName} | minSdk ${spec.minSdk} / targetSdk ${spec.targetSdk}")
        appendLine("Arquivos gerados (${files.size}):")
        files.sortedBy { it.absolutePath }.forEach { file ->
            appendLine("  - ${file.relativeTo(root).path} (${file.length()} bytes)")
        }
        appendLine()
        if (template.equals("kotlin_compose", ignoreCase = true)) {
            appendLine("Este template é para Android Studio/Gradle. Para compilar no celular use o template java_xml.")
        } else {
            appendLine("Próximo passo: android_build_tool(action='build_apk', project_dir='${root.absolutePath}')")
        }
    }.trimEnd()
}
