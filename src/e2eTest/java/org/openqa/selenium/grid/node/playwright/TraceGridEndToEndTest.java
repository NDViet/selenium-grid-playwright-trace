package org.openqa.selenium.grid.node.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

class TraceGridEndToEndTest {

  private final Path seleniumServerJar = requiredPathProperty("e2e.seleniumServerJar");
  private final Path extensionJar = requiredPathProperty("e2e.extensionJar");
  private final Path outputBase = requiredPathProperty("e2e.outputDir");

  private static final List<SiteScenario> RICH_HTML_SITES =
      List.of(
          new SiteScenario(
              "Katalon",
              URI.create("https://katalon.com/"),
              List.of("Platform", "Pricing", "Resources", "Contact", "Demo")),
          new SiteScenario(
              "Sauce Labs",
              URI.create("https://saucelabs.com/"),
              List.of("Platform", "Solutions", "Pricing", "Resources", "Demo")),
          new SiteScenario(
              "BrowserStack",
              URI.create("https://www.browserstack.com/"),
              List.of("Products", "Developers", "Pricing", "Resources", "Live")));

  private Process hub;
  private Process node;
  private Path runDir;
  private Path traceDir;
  private Path logsDir;

  @BeforeEach
  void setUp() throws IOException {
    String runId =
        Instant.now()
                .toString()
                .replace(":", "")
                .replace(".", "")
                .replace("Z", "")
                .toLowerCase(Locale.ROOT)
            + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    runDir = outputBase.resolve("runs").resolve(runId);
    traceDir = outputBase.resolve("traces").resolve(runId);
    logsDir = outputBase.resolve("logs").resolve(runId);
    Files.createDirectories(runDir);
    Files.createDirectories(traceDir);
    Files.createDirectories(logsDir);
  }

  @AfterEach
  void tearDown() {
    stop(node);
    stop(hub);
  }

  @Test
  void recordsTraceZipFromRealGridNodeExtension(TestInfo testInfo) throws Exception {
    assertThat(Files.isRegularFile(seleniumServerJar)).isTrue();
    assertThat(Files.isRegularFile(extensionJar)).isTrue();

    URI gridUrl = startGrid(Duration.ofSeconds(30));

    String testName = testName(testInfo);
    RemoteWebDriver driver = createSession(gridUrl, testName);
    String sessionId = driver.getSessionId().toString();
    try {
      exerciseComplexSites(driver);
    } finally {
      driver.quit();
    }

    Path traceZip = traceZip(testName, sessionId);
    waitUntil(
        "trace zip exists",
        Duration.ofSeconds(30),
        () -> Files.isRegularFile(traceZip) && traceZip.toFile().length() > 0);
    assertTraceZip(traceZip);

    System.out.println("Trace output: " + traceZip);
    System.out.println("Grid logs: " + logsDir);
  }

  @Test
  void recordsTraceZipWhenNodeSessionTimesOut(TestInfo testInfo) throws Exception {
    assertThat(Files.isRegularFile(seleniumServerJar)).isTrue();
    assertThat(Files.isRegularFile(extensionJar)).isTrue();

    Duration sessionTimeout = Duration.ofSeconds(6);
    URI gridUrl = startGrid(sessionTimeout);

    String testName = testName(testInfo);
    RemoteWebDriver driver = createSession(gridUrl, testName);
    String sessionId = driver.getSessionId().toString();

    driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
    driver.get(RICH_HTML_SITES.get(0).url().toString());
    WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    waitForDocumentReady(driver, wait);
    dismissCookieBanner(driver);
    clickNavigationLink(driver, RICH_HTML_SITES.get(0));
    waitForDocumentReady(driver, wait);

    Thread.sleep(sessionTimeout.plusSeconds(40).toMillis());

    assertThatSessionHasTimedOut(driver);

    Path traceZip = traceZip(testName, sessionId);
    waitUntil(
        "trace zip exists after session timeout",
        Duration.ofSeconds(30),
        () -> Files.isRegularFile(traceZip) && traceZip.toFile().length() > 0);
    assertTraceZip(traceZip);

    System.out.println("Timed-out session trace output: " + traceZip);
    System.out.println("Grid logs: " + logsDir);
  }

  @Test
  void recordsTraceZipForExplicitWaitElementConditions(TestInfo testInfo) throws Exception {
    assertThat(Files.isRegularFile(seleniumServerJar)).isTrue();
    assertThat(Files.isRegularFile(extensionJar)).isTrue();

    URI gridUrl = startGrid(Duration.ofSeconds(60));

    String testName = testName(testInfo);
    RemoteWebDriver driver = createSession(gridUrl, testName);
    String sessionId = driver.getSessionId().toString();
    try {
      exerciseDelayedToastExplicitWaits(driver);
    } finally {
      driver.quit();
    }

    Path traceZip = traceZip(testName, sessionId);
    waitUntil(
        "explicit wait trace zip exists",
        Duration.ofSeconds(30),
        () -> Files.isRegularFile(traceZip) && traceZip.toFile().length() > 0);
    assertTraceZip(traceZip);

    System.out.println("Explicit wait trace output: " + traceZip);
    System.out.println("Grid logs: " + logsDir);
  }

  private URI startGrid(Duration sessionTimeout) throws IOException, InterruptedException {
    int hubPort = freePort();
    int nodePort = freePort();
    int publishEventsPort = freePort();
    int subscribeEventsPort = freePort();
    URI hubUrl = URI.create("http://127.0.0.1:" + hubPort);
    URI gridUrl = hubUrl.resolve("/wd/hub");
    String publishEvents = "tcp://127.0.0.1:" + publishEventsPort;
    String subscribeEvents = "tcp://127.0.0.1:" + subscribeEventsPort;

    hub =
        startProcess(
            "hub.log",
            List.of(
                javaBin(),
                "-jar",
                seleniumServerJar.toString(),
                "hub",
                "--port",
                "" + hubPort,
                "--publish-events",
                publishEvents,
                "--subscribe-events",
                subscribeEvents),
            null);
    waitUntil("Grid Hub listening", Duration.ofSeconds(45), () -> gridListening(hubUrl));

    Path nodeConfig = writeNodeConfig(nodePort, traceDir);
    node =
        startProcess(
            "node.log",
            List.of(
                javaBin(),
                "-jar",
                seleniumServerJar.toString(),
                "--ext",
                extensionJar.toString(),
                "node",
                "--port",
                "" + nodePort,
                "--hub",
                hubUrl.toString(),
                "--publish-events",
                publishEvents,
                "--subscribe-events",
                subscribeEvents,
                "--config",
                nodeConfig.toString(),
                "--session-timeout",
                String.valueOf(sessionTimeout.toSeconds())),
            "true");
    return gridUrl;
  }

  private RemoteWebDriver createSession(URI gridUrl, String testName) throws InterruptedException {
    ChromeOptions options = new ChromeOptions();
    options.addArguments(
        "--headless=new",
        "--disable-dev-shm-usage",
        "--no-sandbox",
        "--window-size=1440,1000",
        "--disable-notifications");
    options.setPageLoadStrategy(PageLoadStrategy.EAGER);
    options.setCapability(PlaywrightTraceRecorder.CAP_RECORD_TRACE, true);
    options.setCapability(PlaywrightTraceRecorder.CAP_SESSION_NAME, testName);

    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    RuntimeException lastError = null;
    while (Instant.now().isBefore(deadline)) {
      try {
        return new RemoteWebDriver(gridUrl.toURL(), options);
      } catch (WebDriverException e) {
        lastError = e;
        Thread.sleep(1000);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError(
        "Could not create a Chrome session through Grid. Check " + logsDir, lastError);
  }

  private Path traceZip(String testName, String sessionId) {
    return traceDir.resolve(PlaywrightTraceRecorder.traceFileName(testName, sessionId));
  }

  private static String testName(TestInfo testInfo) {
    return testInfo
        .getTestMethod()
        .map(method -> method.getName())
        .orElseGet(testInfo::getDisplayName);
  }

  private void exerciseComplexSites(RemoteWebDriver driver) {
    driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
    WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

    for (SiteScenario site : RICH_HTML_SITES) {
      driver.get(site.url().toString());
      waitForDocumentReady(driver, wait);
      dismissCookieBanner(driver);
      String beforeUrl = driver.getCurrentUrl();

      String clicked = clickNavigationLink(driver, site);
      waitForNavigationOrReady(driver, wait, beforeUrl);
      dismissCookieBanner(driver);

      assertThat(clicked).as("clicked navigation link for " + site.name()).isNotBlank();
      assertThat(driver.getTitle()).as("page title after " + site.name()).isNotBlank();
      assertThat(driver.getCurrentUrl()).as("current url after " + site.name()).startsWith("http");
      System.out.println(
          "Visited "
              + site.name()
              + ", clicked ["
              + clicked
              + "], landed at "
              + driver.getCurrentUrl());
    }
  }

  private void exerciseDelayedToastExplicitWaits(RemoteWebDriver driver) {
    By durationInput = By.xpath("//input[@data-test='delayed-notification-duration-input']");
    By delayedNotificationTrigger = By.cssSelector("[data-test='delayed-notification-trigger']");
    By completedText = By.xpath("//p[text()='Build step completed']");

    driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
    driver.get("https://base-component.aut.katalon.com/toast-delay-scenario");

    WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    waitForDocumentReady(driver, wait);

    WebElement input = wait.until(ExpectedConditions.elementToBeClickable(durationInput));
    input.clear();
    input.sendKeys("10");
    WebElement trigger =
        wait.until(ExpectedConditions.elementToBeClickable(delayedNotificationTrigger));
    trigger.click();

    Instant visibleCheckStart = Instant.now();
    sleep(Duration.ofSeconds(2));
    WebElement visibleCompletedText =
        wait.until(ExpectedConditions.visibilityOfElementLocated(completedText));
    assertThat(visibleCompletedText.isDisplayed()).isTrue();
    assertThat(Duration.between(visibleCheckStart, Instant.now()))
        .as("visibility check should happen after at least 2 seconds")
        .isGreaterThanOrEqualTo(Duration.ofSeconds(2));

    WebDriverWait disappearanceWait = new WebDriverWait(driver, Duration.ofSeconds(12));
    assertThat(
            disappearanceWait.until(ExpectedConditions.invisibilityOfElementLocated(completedText)))
        .as("Build step completed text should disappear after its configured duration")
        .isTrue();
    assertThat(driver.findElements(completedText))
        .as("Build step completed text should disappear after its configured duration")
        .isEmpty();
  }

  private String clickNavigationLink(RemoteWebDriver driver, SiteScenario site) {
    List<WebElement> links = driver.findElements(By.cssSelector("header a[href], nav a[href]"));
    if (links.isEmpty()) {
      links = driver.findElements(By.cssSelector("a[href]"));
    }

    for (String preferredText : site.preferredLinkTexts()) {
      String clicked = clickFirstMatchingLink(driver, links, preferredText);
      if (clicked != null) {
        return clicked;
      }
    }

    String clicked = clickFirstMatchingLink(driver, links, null);
    if (clicked != null) {
      return clicked;
    }
    throw new AssertionError("No clickable navigation link found for " + site.name());
  }

  private String clickFirstMatchingLink(
      RemoteWebDriver driver, List<WebElement> links, String preferredText) {
    for (WebElement link : links) {
      try {
        if (!link.isDisplayed() || !link.isEnabled()) {
          continue;
        }
        String label = linkLabel(link);
        String href = attribute(link, "href");
        if (!usableNavigationHref(href)) {
          continue;
        }
        if (preferredText != null
            && !label.toLowerCase(Locale.ROOT).contains(preferredText.toLowerCase(Locale.ROOT))) {
          continue;
        }
        clickElement(driver, link);
        return label.isBlank() ? href : label;
      } catch (WebDriverException ignored) {
        // Public marketing pages often hydrate/re-render during startup. Try the next candidate.
      }
    }
    return null;
  }

  private static String linkLabel(WebElement link) {
    String text = link.getText();
    if (text == null || text.isBlank()) {
      text = attribute(link, "aria-label");
    }
    if (text == null || text.isBlank()) {
      text = attribute(link, "title");
    }
    return text == null ? "" : text.replaceAll("\\s+", " ").trim();
  }

  private static boolean usableNavigationHref(String href) {
    if (href == null || href.isBlank()) {
      return false;
    }
    String lower = href.toLowerCase(Locale.ROOT);
    return lower.startsWith("http")
        && !lower.startsWith("mailto:")
        && !lower.startsWith("tel:")
        && !lower.contains("/privacy")
        && !lower.contains("/terms");
  }

  private static String attribute(WebElement element, String name) {
    String value = element.getAttribute(name);
    return value == null ? "" : value.trim();
  }

  private void clickElement(RemoteWebDriver driver, WebElement element) {
    try {
      ((JavascriptExecutor) driver)
          .executeScript(
              "arguments[0].scrollIntoView({block:'center', inline:'center'});", element);
      element.click();
    } catch (WebDriverException e) {
      ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
    }
  }

  private void dismissCookieBanner(RemoteWebDriver driver) {
    List<String> labels = List.of("Accept All", "Accept", "Agree", "Allow all", "Got it");
    for (String label : labels) {
      List<WebElement> buttons =
          driver.findElements(
              By.xpath(
                  "//*[self::button or @role='button' or self::a][contains("
                      + "translate(normalize-space(.), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ',"
                      + " 'abcdefghijklmnopqrstuvwxyz'), '"
                      + label.toLowerCase(Locale.ROOT)
                      + "')]"));
      for (WebElement button : buttons) {
        try {
          if (button.isDisplayed() && button.isEnabled()) {
            clickElement(driver, button);
            return;
          }
        } catch (WebDriverException ignored) {
          // Best-effort only.
        }
      }
    }
  }

  private void waitForNavigationOrReady(
      RemoteWebDriver driver, WebDriverWait wait, String beforeUrl) {
    try {
      wait.until(
          ignored ->
              !driver.getCurrentUrl().equals(beforeUrl)
                  || "complete".equals(executeString(driver, "return document.readyState")));
    } catch (TimeoutException ignored) {
      waitForDocumentReady(driver, wait);
    }
  }

  private void waitForDocumentReady(RemoteWebDriver driver, WebDriverWait wait) {
    wait.until(
        ignored -> {
          String state = executeString(driver, "return document.readyState");
          return "interactive".equals(state) || "complete".equals(state);
        });
  }

  private void assertThatSessionHasTimedOut(RemoteWebDriver driver) {
    try {
      driver.getTitle();
    } catch (WebDriverException expected) {
      return;
    }
    throw new AssertionError("Expected Grid session to be timed out and rejected");
  }

  private static String executeString(RemoteWebDriver driver, String script) {
    Object value = ((JavascriptExecutor) driver).executeScript(script);
    return value == null ? "" : value.toString();
  }

  private Process startProcess(String logName, List<String> command, String recordTrace)
      throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(runDir.toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logsDir.resolve(logName).toFile()));
    if (recordTrace != null) {
      builder.environment().put(PlaywrightTraceRecorder.ENV_RECORD_TRACE, recordTrace);
    }
    return builder.start();
  }

  private Path writeNodeConfig(int nodePort, Path traces) throws IOException {
    Path config = runDir.resolve("node.toml");
    String toml =
        """
        [node]
        port = %d
        max-sessions = 1

        [playwright-trace]
        output-dir = "%s"
        screenshots = true
        snapshots = true
        """
            .formatted(nodePort, tomlPath(traces));
    Files.writeString(config, toml, StandardCharsets.UTF_8);
    return config;
  }

  private boolean gridListening(URI hubUrl) {
    try {
      HttpURLConnection connection =
          (HttpURLConnection) hubUrl.resolve("/status").toURL().openConnection();
      connection.setConnectTimeout(1000);
      connection.setReadTimeout(1000);
      if (connection.getResponseCode() != 200) {
        return false;
      }
      String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return body.contains("\"value\"");
    } catch (IOException e) {
      return false;
    }
  }

  private void assertTraceZip(Path traceZip) throws IOException {
    boolean hasTrace = false;
    boolean hasNetwork = false;
    boolean hasResources = false;
    boolean hasTimelineScreenshots = false;
    boolean hasDomSnapshots = false;
    boolean hasCssResourceSnapshots = false;
    boolean hasCssResources = false;
    boolean hasTraceGroups = false;
    boolean hasSnapshotProbeActions = false;
    try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(traceZip))) {
      ZipEntry entry;
      while ((entry = zin.getNextEntry()) != null) {
        if ("trace.json".equals(entry.getName()) || "trace.trace".equals(entry.getName())) {
          hasTrace = true;
          String trace = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
          hasTimelineScreenshots = trace.contains("\"type\":\"screencast-frame\"");
          hasDomSnapshots = trace.contains("\"type\":\"frame-snapshot\"");
          hasTraceGroups = trace.contains("\"method\":\"tracingGroup\"");
          hasSnapshotProbeActions = trace.contains("\"method\":\"evaluateExpression\"");
        } else if ("network.json".equals(entry.getName())
            || "trace.network".equals(entry.getName())) {
          hasNetwork = true;
          String network = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
          hasCssResourceSnapshots =
              network.contains("\"type\":\"resource-snapshot\"")
                  && network.contains("\"mimeType\":\"text/css\"");
        } else if (entry.getName().startsWith("resources/")) {
          hasResources = true;
          hasCssResources = hasCssResources || entry.getName().endsWith(".css");
        }
        zin.closeEntry();
      }
    }
    assertThat(hasTrace).as("trace.json present in " + traceZip).isTrue();
    assertThat(hasNetwork).as("network.json present in " + traceZip).isTrue();
    assertThat(hasResources).as("resources present in " + traceZip).isTrue();
    assertThat(hasTimelineScreenshots).as("timeline screenshots present in " + traceZip).isTrue();
    assertThat(hasDomSnapshots).as("DOM snapshots present in " + traceZip).isTrue();
    assertThat(hasCssResourceSnapshots)
        .as("CSS resource snapshots present in " + traceZip)
        .isTrue();
    assertThat(hasCssResources).as("CSS resource files present in " + traceZip).isTrue();
    assertThat(hasTraceGroups).as("WebDriver trace groups present in " + traceZip).isTrue();
    assertThat(hasSnapshotProbeActions)
        .as("snapshot probe actions present in " + traceZip)
        .isTrue();
  }

  private static void waitUntil(String label, Duration timeout, BooleanSupplier condition)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("Timed out waiting for " + label);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while sleeping", e);
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private static void stop(Process process) {
    if (process == null || !process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private static String javaBin() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String tomlPath(Path path) {
    return path.toAbsolutePath().toString().replace("\\", "\\\\");
  }

  private static Path requiredPathProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required system property: " + name);
    }
    return Path.of(value);
  }

  private record SiteScenario(String name, URI url, List<String> preferredLinkTexts) {}
}
