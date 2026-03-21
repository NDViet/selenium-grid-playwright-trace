import java.net.URL

plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.diffplug.spotless") version "7.0.2"
}

group = "org.ndviet"
version = "1.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories {
    mavenCentral()
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Resolves the selenium-server JAR from GitHub releases.
// - Default: latest stable release (selenium-server-*.jar)
// - Nightly:  ./gradlew -Pnightly=true  → selenium-server-*-SNAPSHOT.jar
// - Override: ./gradlew -PseleniumServerJar=/absolute/path/to/selenium-server.jar
fun fetchSeleniumServerJarUrl(isNightly: Boolean): String {
    val apiEndpoint = if (isNightly)
        "https://api.github.com/repos/SeleniumHQ/selenium/releases/tags/nightly"
    else
        "https://api.github.com/repos/SeleniumHQ/selenium/releases/latest"
    val json = URL(apiEndpoint).readText()
    val pattern = if (isNightly)
        Regex(""""browser_download_url":\s*"(https://[^"]+/selenium-server-[^"]*-SNAPSHOT\.jar)"""")
    else
        Regex(""""browser_download_url":\s*"(https://[^"]+/selenium-server-\d[^"]*\.jar)"""")
    return pattern.find(json)?.groupValues?.get(1)
        ?: error("Could not find selenium-server JAR in GitHub release (nightly=$isNightly)")
}

val seleniumServerJar: String by lazy {
    val prop = findProperty("seleniumServerJar") as String?
    if (prop != null) return@lazy prop

    val isNightly = (findProperty("nightly") as String?)?.toBoolean() ?: false
    val jarUrl = fetchSeleniumServerJarUrl(isNightly)
    val jarName = jarUrl.substringAfterLast("/")
    val cacheDir = file("${rootProject.projectDir}/.gradle/selenium-server").also { it.mkdirs() }
    val dest = File(cacheDir, jarName)
    if (!dest.exists()) {
        println("Downloading $jarName ...")
        URL(jarUrl).openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        println("Saved to ${dest.absolutePath}")
    }
    dest.absolutePath
}

dependencies {
    // Selenium Grid provides NodeCommandInterceptor, EventBus, Config, etc. at runtime.
    // Use 'compileOnly' so we don't bundle it — it's already on the classpath when --ext loads us.
    compileOnly(files(seleniumServerJar))

    // Playwright is bundled into our fat JAR (not on the server classpath).
    implementation("com.microsoft.playwright:playwright:+")

    // Testing — same server JAR on test classpath
    testImplementation(files(seleniumServerJar))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

val playwrightPlatforms = listOf("linux", "linux-arm64", "mac", "mac-arm64", "win32_x64")

// Register a shadowJar task per platform, each bundling only that platform's driver.
// Output: build/libs/selenium-grid-playwright-trace-<version>-<platform>.jar
val platformJarTasks = playwrightPlatforms.map { platform ->
    tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar-$platform") {
        group = "shadow"
        description = "Assembles a fat JAR for platform: $platform"
        archiveClassifier = platform
        from(sourceSets.main.get().output)
        configurations = listOf(project.configurations.runtimeClasspath.get())
        dependencies {
            exclude { it.moduleGroup.startsWith("org.seleniumhq") }
        }
        playwrightPlatforms.filter { it != platform }.forEach { exclude("driver/$it/**") }
        mergeServiceFiles()
    }
}

// Disable the default shadowJar to avoid building the 200 MB all-platforms JAR.
tasks.shadowJar { enabled = false }

// Make 'build' produce all platform JARs.
tasks.build { dependsOn(platformJarTasks) }
