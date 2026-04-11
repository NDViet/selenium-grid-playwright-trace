plugins {
    java
    `maven-publish`
    signing
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.diffplug.spotless") version "7.0.2"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "org.ndviet"
version = "4.43.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

val seleniumVersion = findProperty("seleniumVersion") as String? ?: "+"

repositories {
    mavenCentral()
    if (seleniumVersion.endsWith("-SNAPSHOT")) {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencies {
    // Selenium Grid + remote-driver provide NodeCommandInterceptor, EventBus, Config, SessionId,
    // HttpRequest/Response, etc. at runtime. Use 'compileOnly' so we don't bundle them — they're
    // already on the classpath when --ext loads us. Available on Maven Central from 4.42.0;
    // use -PseleniumVersion=x.y.z-SNAPSHOT for nightly.
    compileOnly("org.seleniumhq.selenium:selenium-grid:$seleniumVersion")
    compileOnly("org.seleniumhq.selenium:selenium-remote-driver:$seleniumVersion")

    // Playwright is bundled into our fat JAR (not on the server classpath).
    implementation("com.microsoft.playwright:playwright:+")

    // Testing
    testImplementation("org.seleniumhq.selenium:selenium-grid:$seleniumVersion")
    testImplementation("org.seleniumhq.selenium:selenium-remote-driver:$seleniumVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

val playwrightPlatforms = listOf("linux", "linux-arm64", "mac", "mac-arm64", "win32_x64")

// Derives the Playwright platform classifier from JVM system properties so callers
// never need to hardcode it (used by shadowJarCurrentPlatform and the README examples).
fun currentPlatform(): String {
    val os   = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("linux") && arch == "aarch64"                       -> "linux-arm64"
        os.contains("linux")                                             -> "linux"
        (os.contains("mac") || os.contains("darwin")) && arch == "aarch64" -> "mac-arm64"
        os.contains("mac") || os.contains("darwin")                     -> "mac"
        os.contains("win")                                               -> "win32_x64"
        else -> error("Unsupported platform: os.name=$os, os.arch=$arch")
    }
}

// Sources and Javadoc JARs — required by Maven Central.
val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier = "sources"
    from(sourceSets.main.get().allSource)
}
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier = "javadoc"
    from(tasks.javadoc)
}

// Universal shadow JAR (all platforms bundled).
tasks.shadowJar {
    archiveClassifier = ""
    dependencies {
        exclude { it.moduleGroup.startsWith("org.seleniumhq") }
    }
    mergeServiceFiles()
}

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

// Builds the platform-specific JAR for the machine running the build — no hardcoding required.
// Equivalent to ./gradlew shadowJar-linux, shadowJar-mac-arm64, etc. for the current host.
tasks.register("shadowJarCurrentPlatform") {
    group = "shadow"
    description = "Assembles the platform-specific fat JAR for the current host OS/arch."
    val platform = currentPlatform()
    dependsOn("shadowJar-$platform")
    doLast { println("Built platform JAR for: $platform") }
}

// Make 'build' produce universal + all platform JARs.
tasks.build { dependsOn(tasks.shadowJar, platformJarTasks) }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "selenium-grid-playwright-trace"

            // Universal JAR as the primary artifact.
            artifact(tasks.shadowJar)
            artifact(sourcesJar)
            artifact(javadocJar)

            // Per-platform JARs as classified artifacts.
            playwrightPlatforms.forEach { platform ->
                artifact(tasks.named("shadowJar-$platform"))
            }

            pom {
                name = "selenium-grid-playwright-trace"
                description = "Playwright trace recorder extension for Selenium Grid nodes"
                url = "https://github.com/ndviet/selenium-grid-playwright-trace"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "ndviet"
                        name = "Viet Nguyen Duc"
                        email = "nguyenducviet4496@gmail.com"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/ndviet/selenium-grid-playwright-trace.git"
                    developerConnection = "scm:git:ssh://github.com/ndviet/selenium-grid-playwright-trace.git"
                    url = "https://github.com/ndviet/selenium-grid-playwright-trace"
                }
            }
        }
    }
}

signing {
    val gpgKey = System.getenv("GPG_PRIVATE_KEY") ?: findProperty("signing.key") as String?
    val gpgPassphrase = System.getenv("GPG_PASSPHRASE") ?: findProperty("signing.password") as String?
    if (gpgKey != null) useInMemoryPgpKeys(gpgKey, gpgPassphrase)
    else useGpgCmd() // local gpg agent — uses signing.keyId / signing.password from gradle.properties
    sign(publishing.publications["mavenJava"])
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            username = System.getenv("MAVEN_CENTRAL_USERNAME")
                ?: findProperty("ossrhUsername") as String?
            password = System.getenv("MAVEN_CENTRAL_PASSWORD")
                ?: findProperty("ossrhPassword") as String?
        }
    }
}
