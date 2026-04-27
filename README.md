# selenium-grid-playwright-trace

A [Selenium Grid Node](https://www.selenium.dev/documentation/grid/) extension that automatically records [Playwright traces](https://playwright.dev/docs/trace-viewer) for every Chromium-based WebDriver session — with zero changes to your tests.

Traces are viewable at **[trace.playwright.dev](https://trace.playwright.dev)** and include screenshots, DOM snapshots, and semantic action labels per WebDriver command (e.g. `Navigate — https://example.com`, `Click — #submit`).

## Quick Start

**1. Fetch the extension JAR via Coursier**

```bash
cs fetch --cache . \
  "org.ndviet:selenium-grid-playwright-trace:4.43.0" \
  --artifact-type jar
```

This downloads `selenium-grid-playwright-trace-4.43.0.jar` (universal, all platforms) into the current directory.

**2. Create `node.toml`**

```toml
[node]
port = 4444
max-sessions = 4
session-timeout = 300

[playwright-trace]
# output-dir = "/tmp/playwright-traces"   # default: {user.dir}/traces
screenshots = true
snapshots = true
```

**3. Start Selenium Grid in standalone mode**

```bash
SE_RECORD_TRACE=true java -jar selenium-server.jar \
  --ext selenium-grid-playwright-trace-4.43.0.jar \
  standalone \
  --config node.toml
```

Grid is now available at `http://localhost:4444`. Every Chromium session produces a `trace_<sessionId>.zip` in your configured `output-dir`.

---

## Maven Central

```xml
<dependency>
    <groupId>org.ndviet</groupId>
    <artifactId>selenium-grid-playwright-trace</artifactId>
    <version>4.43.0</version>
</dependency>
```

```kotlin
// Gradle (Kotlin DSL)
implementation("org.ndviet:selenium-grid-playwright-trace:4.43.0")
```

Platform-specific JARs (smaller footprint — only the Playwright driver for your OS/arch) are also published. The classifier matches Playwright's own platform naming and can be resolved automatically without hardcoding:

| Classifier | OS | Architecture |
|---|---|---|
| `linux` | Linux | x86-64 |
| `linux-arm64` | Linux | ARM64 |
| `mac` | macOS | x86-64 |
| `mac-arm64` | macOS | Apple Silicon |
| `win32_x64` | Windows | x86-64 |

**Coursier** — auto-detect at fetch time:

```bash
PLATFORM=$(
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64)  echo linux       ;;
    Linux/aarch64) echo linux-arm64 ;;
    Darwin/x86_64) echo mac         ;;
    Darwin/arm64)  echo mac-arm64   ;;
    MINGW*|MSYS*)  echo win32_x64   ;;
  esac
)
cs fetch "org.ndviet:selenium-grid-playwright-trace:4.43.0,classifier=$PLATFORM"
```

**Maven** — hardcode or resolve via a build property:

```xml
<dependency>
    <groupId>org.ndviet</groupId>
    <artifactId>selenium-grid-playwright-trace</artifactId>
    <version>4.43.0</version>
    <classifier>${os.detected.classifier}</classifier>
</dependency>
```

## Requirements

- Java 17+
- Selenium Grid Node 4.42.0+

## Usage

### 1. Download the JAR

Download the fat JAR from [Maven Central](https://central.sonatype.com/artifact/org.ndviet/selenium-grid-playwright-trace) or build it locally:

```bash
./gradlew shadowJar
# → build/libs/selenium-grid-playwright-trace-<version>.jar
```

### 2. Configure the node (node.toml)

```toml
[node]
port = 5555
max-sessions = 4
session-timeout = 300

[playwright-trace]
# output-dir = "/tmp/playwright-traces"   # default: {user.dir}/traces
screenshots = true
snapshots = true
```

### 3. Launch the node

Pass the JAR via `--ext` **before** the `node` subcommand:

```bash
SE_RECORD_TRACE=true java -jar selenium-server.jar \
  --ext selenium-grid-playwright-trace-<version>.jar \
  node \
  --config node.toml
```

## Activation

Tracing is controlled at two levels:

| Level | Mechanism | Values |
|---|---|---|
| Global default | `SE_RECORD_TRACE` env var | `true` — trace all Chromium sessions |
| Per-session override | `se:recordTrace` capability | `true` / `false` — overrides the global default |

```java
// Force tracing on for this session (regardless of SE_RECORD_TRACE)
ChromeOptions options = new ChromeOptions();
options.setCapability("se:recordTrace", true);

// Opt out for this session
options.setCapability("se:recordTrace", false);
```

## Output

Each session produces a `trace_<sessionId>.zip` in the configured `output-dir`.

Open it at [trace.playwright.dev](https://trace.playwright.dev) or with the Playwright CLI:

```bash
npx playwright show-trace trace_<sessionId>.zip
```

## How it works

1. The interceptor is loaded via Java `ServiceLoader` — it stands by with zero overhead until a session that should be traced is created.
2. When a traced session starts and advertises `se:cdp`, the extension stores the direct Node CDP endpoint and connects lazily on the first meaningful command.
3. A single Playwright trace is started for the session. Each meaningful WebDriver command is wrapped in a named trace group (e.g. `Click — #submit`) and a lightweight `page.evaluate("1")` after the command so Playwright captures DOM snapshots.
4. Playwright owns trace resource storage for the whole session; the recorder no longer splits command chunks or merges trace zip files.
5. When the session ends (`DELETE /session`, timeout, or node shutdown), Playwright writes `trace_<sessionId>.zip` directly.

## Building from source

```bash
# Universal + all platform JARs (what CI publishes)
./gradlew build

# Universal fat JAR only (all platforms bundled)
./gradlew shadowJar

# Platform-specific JAR — auto-detects current OS/arch, no hardcoding required
./gradlew shadowJarCurrentPlatform

# Or target a specific platform explicitly
./gradlew shadowJar-linux
./gradlew shadowJar-mac-arm64
# etc.

# Run tests
./gradlew test

# Build against a specific Selenium version
./gradlew shadowJar -PseleniumVersion=4.42.0

# Build against nightly Selenium
./gradlew shadowJar -PseleniumVersion=4.43.0-SNAPSHOT
```

## End-to-End Trace Evaluation

Run the Grid-backed e2e harness:

```bash
./gradlew e2eTest --rerun-tasks
```

The task downloads `selenium-server-4.43.0.jar` from the Selenium GitHub release, builds the
current-platform extension JAR, starts a Hub and Node with `--ext`, drives Selenium Chrome through
rich public sites (`katalon.com`, `saucelabs.com`, and `browserstack.com`), clicks navigation links,
and verifies a Playwright trace zip was created.

Artifacts are left under:

- `build/e2e/traces/<run-id>/trace_<sessionId>.zip`
- `build/e2e/logs/<run-id>/hub.log`
- `build/e2e/logs/<run-id>/node.log`
