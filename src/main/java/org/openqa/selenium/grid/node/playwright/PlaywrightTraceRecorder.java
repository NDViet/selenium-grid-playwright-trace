package org.openqa.selenium.grid.node.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.data.SessionClosedData;
import org.openqa.selenium.grid.data.SessionClosedEvent;
import org.openqa.selenium.grid.data.SessionCreatedData;
import org.openqa.selenium.grid.data.SessionCreatedEvent;
import org.openqa.selenium.grid.node.NodeCommandInterceptor;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

/**
 * A {@link NodeCommandInterceptor} that records Playwright traces for each Chromium-based WebDriver
 * session, loadable via {@code --ext} on a Selenium Grid Node.
 *
 * <h2>How it works</h2>
 *
 * <ol>
 *   <li>The interceptor is always loaded by the ServiceLoader and stands by with zero overhead
 *       until a session that should be traced is created.
 *   <li>When a traced session starts and advertises {@code se:cdp}, the recorder stores the direct
 *       Node CDP endpoint but does not connect yet.
 *   <li>Each meaningful WebDriver command gets a named trace group with a semantic action label
 *       (e.g. {@code "Navigate — https://example.com"}, {@code "Click — #submit"}) and a
 *       lightweight {@code page.evaluate("1")} after the command so Playwright captures DOM
 *       snapshots. A single Playwright tracing context stays open for the session so Playwright
 *       writes the final trace zip and its resources natively.
 *   <li>When the session ends the trace is saved as {@code trace_<se:name>_<sessionId>.zip}, or
 *       {@code trace_<sessionId>.zip} when {@code se:name} is not set.
 * </ol>
 *
 * <h2>Activation</h2>
 *
 * <p>Two levels of control:
 *
 * <ol>
 *   <li><b>Global default</b> — environment variable {@code SE_RECORD_TRACE}:
 *       <ul>
 *         <li>{@code SE_RECORD_TRACE=true} → all Chromium sessions are traced by default.
 *         <li>Not set or {@code false} → no sessions are traced by default.
 *       </ul>
 *   <li><b>Per-session override</b> — WebDriver capability {@code se:recordTrace}:
 *       <ul>
 *         <li>{@code "se:recordTrace": true} → trace this session regardless of the global default.
 *         <li>{@code "se:recordTrace": false} → skip this session regardless of the global default.
 *         <li>Absent → fall back to the global default.
 *       </ul>
 * </ol>
 *
 * <h2>Configuration (node.toml)</h2>
 *
 * <pre>{@code
 * [playwright-trace]
 * output-dir = /tmp/playwright-traces   # default: {user.dir}/traces
 * screenshots = true                    # screenshots in trace (default: true)
 * snapshots = true                      # DOM snapshots in trace (default: true)
 * }</pre>
 *
 * <h2>Resource strategy</h2>
 *
 * <ul>
 *   <li><b>Thread safety:</b> a single dedicated thread owns the {@link Playwright} instance. All
 *       Playwright calls are posted to it; Playwright's thread-affinity requirement is met.
 *   <li><b>Lazy init:</b> Playwright is initialised on the first command that needs tracing, not at
 *       node startup. Nodes where no command is traced pay zero CDP overhead.
 *   <li><b>Native trace zip:</b> Playwright owns trace storage and writes the final zip on session
 *       close; the recorder does not split or merge trace chunks.
 *   <li><b>Lazy connection:</b> CDP is connected only after the first meaningful command and is
 *       closed after Playwright finishes writing the session trace.
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * # build the fat JAR
 * ./gradlew shadowJar
 *
 * # launch the node (SE_RECORD_TRACE enables tracing globally)
 * SE_RECORD_TRACE=true java -jar selenium-server.jar \
 *   --ext build/libs/selenium-playwright-trace-1.0.0-SNAPSHOT.jar \
 *   node --config node.toml
 * }</pre>
 */
public class PlaywrightTraceRecorder implements NodeCommandInterceptor {

  private static final Logger LOG = Logger.getLogger(PlaywrightTraceRecorder.class.getName());

  // Pre-compiled patterns — String.matches() compiles a fresh Pattern on every call.
  // These appear on the hot WebDriver command path, so static constants are important.
  private static final Pattern PAT_FIND_CHILD = Pattern.compile("/element/[^/]+/elements?");
  private static final Pattern PAT_ELEM_INTERACT =
      Pattern.compile("/element/[^/]+/(click|clear|tap|submit|value)");
  private static final Pattern PAT_ELEM_CLICK = Pattern.compile("/element/[^/]+/click");
  private static final Pattern PAT_ELEM_VALUE = Pattern.compile("/element/[^/]+/value");
  private static final Pattern PAT_ELEM_CLEAR = Pattern.compile("/element/[^/]+/clear");
  private static final Pattern PAT_ELEM_TAP = Pattern.compile("/element/[^/]+/tap");
  private static final Pattern PAT_ELEM_SUBMIT = Pattern.compile("/element/[^/]+/submit");
  private static final Pattern PAT_ELEM_ELEMENT = Pattern.compile("/element/[^/]+/element");
  private static final Pattern PAT_ELEM_ELEMENTS = Pattern.compile("/element/[^/]+/elements");
  private static final Pattern PAT_ELEM_SCREENSHOT = Pattern.compile("/element/[^/]+/screenshot");
  private static final Pattern PAT_ELEM_TEXT = Pattern.compile("/element/[^/]+/text");
  private static final Pattern PAT_ELEM_ATTR = Pattern.compile("/element/[^/]+/attribute/[^/]+");
  private static final Pattern PAT_ELEM_PROP = Pattern.compile("/element/[^/]+/property/[^/]+");
  private static final Pattern PAT_ELEM_CSS = Pattern.compile("/element/[^/]+/css/[^/]+");
  private static final Pattern PAT_ELEM_ENABLED = Pattern.compile("/element/[^/]+/enabled");
  private static final Pattern PAT_ELEM_DISPLAYED = Pattern.compile("/element/[^/]+/displayed");
  private static final Pattern PAT_ELEM_SELECTED = Pattern.compile("/element/[^/]+/selected");
  private static final Pattern PAT_ELEM_RECT = Pattern.compile("/element/[^/]+/rect");
  private static final Pattern PAT_COOKIE = Pattern.compile("/cookie/[^/]+");

  static final String SECTION = "playwright-trace";
  private static final String OPT_OUTPUT_DIR = "output-dir";
  private static final String OPT_SCREENSHOTS = "screenshots";
  private static final String OPT_SNAPSHOTS = "snapshots";

  /** Environment variable that sets the global trace-recording default. */
  static final String ENV_RECORD_TRACE = "SE_RECORD_TRACE";

  /** WebDriver capability that overrides the global default per session. */
  static final String CAP_RECORD_TRACE = "se:recordTrace";

  /** WebDriver capability used to name the trace file. */
  static final String CAP_SESSION_NAME = "se:name";

  // All Playwright operations must run on this single thread (thread-affinity requirement).
  private final ExecutorService playwrightThread =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "playwright-trace-worker");
            t.setDaemon(true);
            return t;
          });

  private final ConcurrentHashMap<SessionId, SessionTraceContext> sessions =
      new ConcurrentHashMap<>();

  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean playwrightInitialized = new AtomicBoolean(false);

  private volatile Playwright playwright;

  // Populated in initialize().
  private volatile boolean globalEnabled;
  private Path outputDir;
  private boolean screenshots;
  private boolean snapshots;

  // ---- SPI ------------------------------------------------------------------

  /**
   * Always returns {@code true} — the interceptor is always wired into the node command chain and
   * stands by with negligible overhead. Actual tracing is activated at runtime by the {@code
   * SE_RECORD_TRACE} environment variable and/or the {@code se:recordTrace} capability.
   */
  @Override
  public boolean isEnabled(Config config) {
    return true;
  }

  @Override
  public void initialize(Config config, EventBus bus) {
    globalEnabled = "true".equalsIgnoreCase(System.getenv(ENV_RECORD_TRACE));

    String rawOutputDir =
        config.get(SECTION, OPT_OUTPUT_DIR).orElse(System.getProperty("user.dir") + "/traces");
    outputDir = Paths.get(rawOutputDir);
    screenshots = config.getBool(SECTION, OPT_SCREENSHOTS).orElse(true);
    snapshots = config.getBool(SECTION, OPT_SNAPSHOTS).orElse(true);

    try {
      Files.createDirectories(outputDir);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create trace output directory: " + outputDir, e);
    }

    LOG.info(
        String.format(
            "Playwright trace recorder standing by — SE_RECORD_TRACE=%s, output: %s,"
                + " screenshots: %s, snapshots: %s",
            globalEnabled, outputDir, screenshots, snapshots));

    bus.addListener(SessionCreatedEvent.listener(this::onSessionCreated));
    bus.addListener(SessionClosedEvent.listener(this::onSessionClosed));

    // Playwright is initialised lazily on the first session that needs tracing.
    // Fallback shutdown hook: a no-op when LocalNode already called close().
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    close();
                  } catch (IOException ignored) {
                  }
                },
                "playwright-trace-shutdown"));
  }

  /**
   * Saves any remaining active traces and shuts down the Playwright worker thread. Called by {@code
   * LocalNode} at node shutdown; also reachable from the JVM shutdown hook as a fallback.
   * Idempotent — safe to call more than once.
   */
  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return; // already closed
    }
    // Snapshot to avoid ConcurrentModificationException — completeSessionTrace removes from map.
    List<SessionId> remaining = new ArrayList<>(sessions.keySet());
    if (!remaining.isEmpty()) {
      LOG.info("Node shutting down; saving " + remaining.size() + " active trace(s)");
      for (SessionId sessionId : remaining) {
        playwrightThread.submit(() -> completeSessionTrace(sessionId));
      }
    }
    playwrightThread.submit(this::closePlaywright);
    playwrightThread.shutdown();
    try {
      if (!playwrightThread.awaitTermination(30, TimeUnit.SECONDS)) {
        LOG.warning("Playwright worker thread did not terminate within 30s; forcing shutdown");
        playwrightThread.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      playwrightThread.shutdownNow();
    }
  }

  @Override
  public HttpResponse intercept(SessionId id, HttpRequest req, Callable<HttpResponse> next)
      throws Exception {
    SessionTraceContext ctx = sessions.get(id);
    if (ctx == null) {
      // Session is not traced (not Chromium, or CDP connect failed).
      return next.call();
    }

    if (isDeleteSession(req, id)) {
      try {
        playwrightThread.submit(() -> completeSessionTrace(id)).get(15, TimeUnit.SECONDS);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Trace save before session delete failed for " + id, e);
      }
      return next.call();
    }

    boolean commandTraceStarted = false;
    String selector = null;
    try {
      if (isFindElement(req)) {
        // Silent — capture selector for label enrichment on the next element action.
        selector = extractSelector(req);
        if (selector != null) ctx.setLastSelector(selector);
      } else if (shouldTrace(req) && !isSeleniumAtomScript(req)) {
        String label = buildLabel(req, ctx);
        try {
          commandTraceStarted =
              playwrightThread
                  .submit(() -> startCommandTrace(id, ctx, label))
                  .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
          LOG.log(Level.FINE, "Trace pre-command failed for session " + id, e);
        }
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Trace pre-command failed for session " + id, e);
    }

    HttpResponse response = null;
    try {
      response = next.call();
      return response;
    } finally {
      if (commandTraceStarted) {
        try {
          playwrightThread.submit(() -> finishCommandTrace(id, ctx)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
          LOG.log(Level.FINE, "Trace post-command failed for session " + id, e);
        }
      } else if (response != null) {
        cachePendingVerification(id, ctx, req, response, selector);
      }
    }
  }

  // ---- Session lifecycle (called from EventBus listener threads) -----------

  private void onSessionCreated(SessionCreatedData data) {
    // Resolve whether this session should be traced.
    // Priority: per-session cap > global SE_RECORD_TRACE env var.
    Object cap = data.getCapabilities().getCapability(CAP_RECORD_TRACE);
    boolean trace;
    if (cap instanceof Boolean) {
      trace = (Boolean) cap;
    } else if (cap != null) {
      trace = Boolean.parseBoolean(cap.toString());
    } else {
      trace = globalEnabled;
    }

    if (!trace) {
      LOG.fine(
          "Trace recording disabled for session "
              + data.getSessionId()
              + " (SE_RECORD_TRACE="
              + globalEnabled
              + ", se:recordTrace="
              + cap
              + ")");
      return;
    }

    SessionId sessionId = data.getSessionId();
    String traceName =
        traceFileName(data.getCapabilities().getCapability(CAP_SESSION_NAME), sessionId.toString());

    Object cdpCap = data.getCapabilities().getCapability("se:cdp");
    if (cdpCap == null) {
      LOG.info("No se:cdp capability for session " + sessionId + "; skipping trace");
      return;
    }

    // Build a direct Node WebSocket URL for CDP, bypassing any Grid hub/router.
    // The se:cdp capability routes through the hub (e.g. ws://hub:4444/...) which hangs
    // Playwright's connectOverCDP handshake. We connect straight to the Node instead.
    URI nodeUri = data.getNodeUri();
    String wsScheme = "https".equals(nodeUri.getScheme()) ? "wss" : "ws";
    int port = nodeUri.getPort();
    if (port == -1) port = "https".equals(nodeUri.getScheme()) ? 443 : 80;
    String subPath = nodeUri.getRawPath() == null ? "" : nodeUri.getRawPath().replaceAll("/$", "");
    String cdpWsUrl =
        String.format(
            "%s://%s:%d%s/session/%s/se/cdp",
            wsScheme, nodeUri.getHost(), port, subPath, sessionId);

    try {
      Path sessionTraceDir =
          Files.createTempDirectory(outputDir, "trace_" + sessionId.toString() + "_");
      sessions.put(
          sessionId,
          new SessionTraceContext(sessionTraceDir, cdpWsUrl, sessionId.toString(), traceName));
      LOG.info(
          String.format(
              "Playwright trace recorder armed for session %s (%s)",
              sessionId, data.getCapabilities().getBrowserName()));
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Cannot create trace workspace for session " + sessionId, e);
    }
  }

  private void onSessionClosed(SessionClosedData data) {
    SessionId sessionId = data.getSessionId();
    if (!sessions.containsKey(sessionId)) {
      return;
    }
    playwrightThread.submit(() -> completeSessionTrace(sessionId));
  }

  // ---- Playwright operations (must run on playwrightThread) ----------------

  /**
   * Initialises the Playwright runtime on the first call; subsequent calls are no-ops. Must run on
   * {@code playwrightThread}.
   */
  private void ensurePlaywrightInitialized() {
    if (playwrightInitialized.compareAndSet(false, true)) {
      initPlaywright();
    }
  }

  private void initPlaywright() {
    try {
      // We only use connectOverCDP — Playwright never needs to launch its own browsers.
      // PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 prevents the bundled Node.js subprocess from
      // downloading Chromium, Firefox, or WebKit on startup.
      playwright =
          Playwright.create(
              new Playwright.CreateOptions()
                  .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to initialise Playwright", e);
    }
  }

  private boolean startCommandTrace(SessionId sessionId, SessionTraceContext ctx, String label) {
    ensurePlaywrightInitialized();
    if (playwright == null) {
      LOG.warning("Playwright not initialised; skipping trace for session " + sessionId);
      return false;
    }
    try {
      ensureConnected(sessionId, ctx);
      if (!ctx.hasConnection()) {
        return false;
      }
      if (!ctx.isTracingStarted()) {
        ctx.context()
            .tracing()
            .start(
                new Tracing.StartOptions()
                    .setScreenshots(screenshots)
                    .setSnapshots(snapshots)
                    .setSources(false));
        ctx.setTracingStarted(true);
      }
      ctx.context().tracing().group(label);
      return true;
    } catch (Exception e) {
      LOG.log(
          Level.WARNING,
          "Failed to connect Playwright for session " + sessionId + " at " + ctx.cdpUrl(),
          e);
      closeBrowserConnection(sessionId, ctx);
      return false;
    }
  }

  private void ensureConnected(SessionId sessionId, SessionTraceContext ctx) {
    if (ctx.hasConnection()) {
      return;
    }
    Browser browser =
        playwright
            .chromium()
            .connectOverCDP(
                ctx.cdpUrl(),
                new com.microsoft.playwright.BrowserType.ConnectOverCDPOptions()
                    .setTimeout(10_000));

    List<BrowserContext> contexts = browser.contexts();
    if (contexts.isEmpty()) {
      LOG.warning("No browser contexts for session " + sessionId + "; skipping trace");
      browser.close();
      return;
    }
    ctx.setConnection(browser, contexts.get(0));
  }

  private void finishCommandTrace(SessionId sessionId, SessionTraceContext ctx) {
    try {
      if (!ctx.hasConnection() || !ctx.isTracingStarted()) {
        return;
      }
      if (snapshots) {
        safeDomSnapshot(ctx, ctx.context());
      }
      safeGroupClose(ctx, ctx.context());
    } catch (Exception e) {
      LOG.log(Level.FINE, "Command trace close failed for session " + sessionId, e);
    }
  }

  private void completeSessionTrace(SessionId sessionId) {
    SessionTraceContext ctx = sessions.remove(sessionId);
    if (ctx == null) {
      LOG.info("No active trace for session " + sessionId + " — nothing to save");
      return;
    }
    Path tracePath = outputDir.resolve(ctx.traceName());
    Path tempTracePath =
        ctx.traceDir()
            .resolve(
                "trace_"
                    + sessionId
                    + "_"
                    + UUID.randomUUID().toString().replace("-", "")
                    + ".tmp.zip");
    try {
      if (saveNativeTrace(sessionId, ctx, tempTracePath)) {
        moveAtomically(tempTracePath, tracePath);
        LOG.info("Playwright trace saved for session " + sessionId + ": " + tracePath);
        deleteRecursively(ctx.traceDir());
      } else {
        LOG.info("No Playwright trace recorded for session " + sessionId);
        deleteRecursively(ctx.traceDir());
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Error saving trace for session " + sessionId, e);
    } finally {
      try {
        Files.deleteIfExists(tempTracePath);
      } catch (IOException ignored) {
      }
      closeBrowserConnection(sessionId, ctx);
    }
  }

  private boolean saveNativeTrace(
      SessionId sessionId, SessionTraceContext ctx, Path tempTracePath) {
    if (!ctx.hasConnection() || !ctx.isTracingStarted()) {
      return false;
    }
    try {
      ctx.context().tracing().stop(new Tracing.StopOptions().setPath(tempTracePath));
      ctx.setTracingStarted(false);
      if (!isValidTraceZip(tempTracePath)) {
        LOG.warning(
            "Ignoring invalid Playwright trace for session " + sessionId + ": " + tempTracePath);
        return false;
      }
      return true;
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to stop Playwright tracing for session " + sessionId, e);
      return false;
    }
  }

  static String traceFileName(Object rawName, String sessionId) {
    String safeName = sanitizeTraceName(rawName);
    if (safeName == null) {
      return "trace_" + sessionId + ".zip";
    }
    return "trace_" + safeName + "_" + sessionId + ".zip";
  }

  private static String sanitizeTraceName(Object rawName) {
    if (rawName == null) {
      return null;
    }
    String normalized =
        rawName.toString().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
    if (normalized.isEmpty()) {
      return null;
    }
    return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
  }

  private void closeBrowserConnection(SessionId sessionId, SessionTraceContext ctx) {
    Browser browser = ctx.browser();
    if (browser == null) {
      return;
    }
    try {
      if (ctx.isTracingStarted()) {
        try {
          ctx.context().tracing().stop();
        } catch (Exception e) {
          LOG.log(Level.FINE, "Error stopping Playwright tracing for session " + sessionId, e);
        }
      }
      browser.close();
      LOG.fine("Playwright CDP connection released for session " + sessionId);
    } catch (Exception e) {
      LOG.log(Level.FINE, "Error disconnecting browser for session " + sessionId, e);
    } finally {
      ctx.clearConnection();
    }
  }

  private static boolean isValidTraceZip(Path path) {
    if (!Files.isRegularFile(path)) {
      return false;
    }
    try {
      if (Files.size(path) == 0) {
        return false;
      }
      try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(path))) {
        ZipEntry entry;
        boolean hasTrace = false;
        while ((entry = zin.getNextEntry()) != null) {
          if (isTraceEntry(entry.getName())) {
            hasTrace = true;
          }
          zin.closeEntry();
        }
        return hasTrace;
      }
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isTraceEntry(String name) {
    return "trace.json".equals(name) || "trace.trace".equals(name);
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
              });
    } catch (IOException ignored) {
    }
  }

  // ---- Playwright helpers (run on playwrightThread) ------------------------

  private void safeGroupClose(SessionTraceContext ctx, BrowserContext context) {
    try {
      context.tracing().groupEnd();
    } catch (Exception e) {
      LOG.log(Level.FINE, "tracing.groupEnd failed for " + ctx.sessionId(), e);
    }
  }

  /**
   * Fires a lightweight {@code page.evaluate("1")} to trigger Playwright's DOM snapshot machinery.
   * Because {@code evaluate()} is a Playwright action, the tracer automatically captures a DOM
   * snapshot immediately before and after it runs — this is what the {@code trace.playwright.dev}
   * viewer shows as the "DOM preview" for an action.
   */
  private void safeDomSnapshot(SessionTraceContext ctx, BrowserContext context) {
    try {
      List<Page> pages = context.pages();
      if (!pages.isEmpty()) {
        pages.get(0).evaluate("1");
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "DOM snapshot failed for " + ctx.sessionId(), e);
    }
  }

  private void cachePendingVerification(
      SessionId sessionId,
      SessionTraceContext ctx,
      HttpRequest req,
      HttpResponse response,
      String selector) {
    VerificationCheckpoint checkpoint = verificationCheckpoint(ctx, req, response, selector);
    if (checkpoint == null
        || !ctx.shouldRecordVerificationState(checkpoint.key(), checkpoint.state())) {
      return;
    }
    try {
      playwrightThread
          .submit(
              () -> {
                if (startCommandTrace(sessionId, ctx, checkpoint.label())) {
                  finishCommandTrace(sessionId, ctx);
                }
              })
          .get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.log(Level.FINE, "Verification trace failed for session " + sessionId, e);
    }
  }

  static String verificationLabel(
      SessionTraceContext ctx, HttpRequest req, HttpResponse response, String selector) {
    VerificationCheckpoint checkpoint = verificationCheckpoint(ctx, req, response, selector);
    return checkpoint == null ? null : checkpoint.label();
  }

  private static VerificationCheckpoint verificationCheckpoint(
      SessionTraceContext ctx, HttpRequest req, HttpResponse response, String selector) {
    String path = strippedPath(req.getUri());
    if (isFindElement(req)) {
      String readableSelector = formatSelector(selector);
      if (readableSelector == null) {
        return null;
      }

      String body = responseBody(response);
      if (response.isSuccessful()) {
        String elementId = extractElementId(body);
        ctx.rememberElementSelector(elementId, selector);
        if (isFindElementsPath(path)) {
          if (isEmptyElementList(body)) {
            return verification(
                "elements:" + readableSelector,
                "absent",
                "Verify elements absent \u2014 " + readableSelector);
          }
          return verification(
              "elements:" + readableSelector,
              "present",
              "Verify elements present \u2014 " + readableSelector);
        }
        return verification(
            "element:" + readableSelector,
            "present",
            "Verify element present \u2014 " + readableSelector);
      }

      if (response.getStatus() == 404) {
        return verification(
            "element:" + readableSelector,
            "absent",
            "Verify element absent \u2014 " + readableSelector);
      }
      return null;
    }

    if ("/execute/sync".equals(path) && isSeleniumAtomScript(req)) {
      return seleniumAtomVerificationLabel(ctx, req, response);
    }

    if (!"GET".equals(req.getMethod().toString())) {
      return null;
    }

    String elementId = elementIdFromPath(path);
    String readableSelector = formatSelector(ctx.selectorForElement(elementId));
    if (readableSelector == null) {
      readableSelector = formatSelector(ctx.peekLastSelector());
    }
    if (readableSelector == null) {
      return null;
    }

    if (PAT_ELEM_DISPLAYED.matcher(path).matches()) {
      Boolean displayed = extractJsonBoolean(responseBody(response), "value");
      if (displayed == null) {
        return null;
      }
      return verification(
          "visibility:" + readableSelector,
          displayed ? "visible" : "hidden",
          (displayed ? "Verify visible \u2014 " : "Verify hidden \u2014 ") + readableSelector);
    }

    if (PAT_ELEM_ENABLED.matcher(path).matches()) {
      Boolean enabled = extractJsonBoolean(responseBody(response), "value");
      if (enabled == null) {
        return null;
      }
      return verification(
          "enabled:" + readableSelector,
          enabled ? "enabled" : "disabled",
          (enabled ? "Verify enabled \u2014 " : "Verify disabled \u2014 ") + readableSelector);
    }

    if (PAT_ELEM_SELECTED.matcher(path).matches()) {
      Boolean selected = extractJsonBoolean(responseBody(response), "value");
      if (selected == null) {
        return null;
      }
      return verification(
          "selected:" + readableSelector,
          selected ? "selected" : "not-selected",
          (selected ? "Verify selected \u2014 " : "Verify not selected \u2014 ")
              + readableSelector);
    }

    return null;
  }

  private static VerificationCheckpoint seleniumAtomVerificationLabel(
      SessionTraceContext ctx, HttpRequest req, HttpResponse response) {
    String body = readBody(req);
    if (body == null) {
      return null;
    }
    String script = extractJsonField(body, "script");
    if (script == null || !script.stripLeading().startsWith("/* isDisplayed */")) {
      return null;
    }

    String readableSelector = formatSelector(ctx.selectorForElement(extractElementId(body)));
    if (readableSelector == null) {
      readableSelector = formatSelector(ctx.peekLastSelector());
    }
    if (readableSelector == null) {
      return null;
    }

    Boolean displayed = extractJsonBoolean(responseBody(response), "value");
    if (displayed == null) {
      return null;
    }
    return verification(
        "visibility:" + readableSelector,
        displayed ? "visible" : "hidden",
        (displayed ? "Verify visible \u2014 " : "Verify hidden \u2014 ") + readableSelector);
  }

  private static VerificationCheckpoint verification(String key, String state, String label) {
    return new VerificationCheckpoint(key, state, label);
  }

  private record VerificationCheckpoint(String key, String state, String label) {}

  private static String responseBody(HttpResponse response) {
    try {
      return response.contentAsString();
    } catch (Exception e) {
      return null;
    }
  }

  private static String extractElementId(String json) {
    if (json == null) {
      return null;
    }
    String elementId = extractJsonField(json, "element-6066-11e4-a52e-4f735466cecf");
    return elementId != null ? elementId : extractJsonField(json, "ELEMENT");
  }

  private static boolean isEmptyElementList(String json) {
    if (json == null) {
      return false;
    }
    return json.replaceAll("\\s+", "").contains("\"value\":[]");
  }

  private static Boolean extractJsonBoolean(String json, String key) {
    if (json == null) {
      return null;
    }
    String needle = "\"" + key + "\"";
    int idx = json.indexOf(needle);
    if (idx < 0) {
      return null;
    }
    idx += needle.length();
    while (idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == ':')) {
      idx++;
    }
    if (json.startsWith("true", idx)) {
      return true;
    }
    if (json.startsWith("false", idx)) {
      return false;
    }
    return null;
  }

  private static String elementIdFromPath(String path) {
    String prefix = "/element/";
    if (!path.startsWith(prefix)) {
      return null;
    }
    int start = prefix.length();
    int end = path.indexOf('/', start);
    return end > start ? path.substring(start, end) : null;
  }

  private static boolean isFindElementsPath(String path) {
    return "/elements".equals(path) || path.endsWith("/elements");
  }

  private void closePlaywright() {
    if (playwright != null) {
      try {
        playwright.close();
      } catch (Exception e) {
        LOG.log(Level.FINE, "Error closing Playwright", e);
      }
    }
  }

  // ---- Command classification ----------------------------------------------

  /**
   * Returns {@code true} if this is an {@code execute/sync} call whose script body is
   * framework-internal infrastructure rather than user-authored code. Two categories:
   *
   * <ol>
   *   <li><b>Selenium atoms</b> — compiled anonymous function closures injected by WebDriver for
   *       isDisplayed, getAttribute, scrollIntoView, etc.
   *       <ul>
   *         <li>Raw: {@code return (function(){var ...})()}
   *         <li>Named: {@code /* isDisplayed *}{@code /return (function(){...})()}
   *       </ul>
   *   <li><b>Viewport / geometry probes</b> — single-line {@code return <expr>} scripts that test
   *       frameworks (Selenide, Actions helpers, custom utilities) inject repetitively before
   *       interactions to read scroll positions, viewport dimensions, or element offsets. They
   *       carry no user intent and produce nothing but timeline noise.
   * </ol>
   */
  static boolean isSeleniumAtomScript(HttpRequest req) {
    if (!"/execute/sync".equals(strippedPath(req.getUri()))) return false;
    try {
      String body = readBody(req);
      if (body == null) return false;
      String script = extractJsonField(body, "script");
      if (script == null) return false;
      String t = script.stripLeading();
      // Compiled atom closure: "return (function(){..."
      if (t.startsWith("return (function(")) return true;
      // Named atom with comment header: "/* isDisplayed */return (function(){..."
      if (t.startsWith("/*")) {
        int end = t.indexOf("*/");
        if (end >= 0 && t.substring(end + 2).stripLeading().startsWith("return (function(")) {
          return true;
        }
      }
      // Common one-liner internal helpers
      if (t.startsWith("arguments[0].scrollIntoView")) return true;
      if (t.startsWith("arguments[0].click()")) return true;
      // Viewport/geometry probes injected by test frameworks before interactions.
      if (isViewportProbeScript(t)) return true;
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Returns {@code true} for single-line read-only scripts that probe browser geometry.
   *
   * <p>Test frameworks (Selenide, Actions utilities, etc.) repeatedly inject scripts like {@code
   * return Math.max(document.documentElement.clientWidth, window.innerWidth || 0)} to compute
   * scroll offsets and viewport bounds before pointer interactions. These scripts appear dozens of
   * times in a typical test and carry no user-visible meaning.
   *
   * <p>The heuristic is conservative: the script must be a single {@code return <expr>} line (no
   * newlines, no {@code function}, no variable declarations) whose expression starts with a known
   * geometry-read prefix. Short user scripts like {@code return document.title} or {@code return
   * window.location.href} are deliberately left unfiltered.
   */
  private static boolean isViewportProbeScript(String script) {
    if (!script.startsWith("return ")) return false;
    // Must be a single-line expression — multi-step scripts are likely user-authored.
    if (script.contains("\n")
        || script.contains("function ")
        || script.contains("var ")
        || script.contains("let ")
        || script.contains("const ")) return false;
    String expr = script.substring("return ".length()).stripLeading();
    // Viewport dimension reads (e.g. Selenide viewport width probe)
    return expr.startsWith("Math.max(document.documentElement.clientWidth")
        || expr.startsWith("Math.max(document.documentElement.clientHeight")
        // Scroll position reads
        || expr.startsWith("window.pageYOffset")
        || expr.startsWith("window.pageXOffset")
        || expr.startsWith("window.scrollY")
        || expr.startsWith("window.scrollX")
        || expr.startsWith("document.documentElement.scrollTop")
        || expr.startsWith("document.documentElement.scrollLeft")
        || expr.startsWith("document.body.scrollTop")
        || expr.startsWith("document.body.scrollLeft");
  }

  /**
   * Returns {@code true} if this request is the DELETE /session/{id} command that quits the
   * browser. We use this to stop the trace synchronously before the browser is closed.
   */
  private static boolean isDeleteSession(HttpRequest req, SessionId id) {
    return "DELETE".equals(req.getMethod().toString()) && req.getUri().endsWith("/session/" + id);
  }

  /**
   * Returns {@code true} for findElement / findElements commands (including child-element
   * variants). These are silently swallowed — the selector is captured for label enrichment but no
   * trace group is created, eliminating the biggest source of timeline noise.
   */
  private static boolean isFindElement(HttpRequest req) {
    if (!"POST".equals(req.getMethod().toString())) return false;
    String path = strippedPath(req.getUri());
    return "/element".equals(path)
        || "/elements".equals(path)
        || PAT_FIND_CHILD.matcher(path).matches();
  }

  /**
   * Returns {@code true} for commands that represent a meaningful user action worth recording in
   * the trace: navigation, element interactions, script execution, alert handling, and frame/window
   * management.
   *
   * <p>Excluded (no trace group created):
   *
   * <ul>
   *   <li>All GET read-only probes (getAttribute, getText, isDisplayed, title, url, …)
   *   <li>findElement / findElements (handled separately by {@link #isFindElement})
   *   <li>execute/async — almost exclusively framework-internal wait scripts (Angular sync,
   *       page-load strategies, FluentWait); produces nothing but noise in the trace timeline
   *   <li>Session/cookie/timeout metadata commands
   *   <li>Window geometry (maximize, minimize, fullscreen)
   * </ul>
   */
  private static boolean shouldTrace(HttpRequest req) {
    String method = req.getMethod().toString();
    if (!"POST".equals(method) && !"DELETE".equals(method)) return false;
    String path = strippedPath(req.getUri());
    switch (path) {
      case "/url": // navigate
      case "/back":
      case "/forward":
      case "/refresh":
      case "/execute/sync":
      // execute/async is intentionally excluded: it is almost exclusively used by framework-
      // internal wait mechanisms (Angular sync, page-load strategies, FluentWait conditions)
      // and produces nothing but noise in the trace timeline.
      case "/actions":
      case "/alert/accept":
      case "/alert/dismiss":
      case "/alert/text":
      case "/frame":
      case "/frame/parent":
      case "/window": // POST = switch to handle, DELETE = close window
      case "/window/new":
        return true;
      default:
        break;
    }
    // Element-bound interaction commands (excludes findElement — handled by isFindElement)
    return PAT_ELEM_CLICK.matcher(path).matches()
        || PAT_ELEM_VALUE.matcher(path).matches()
        || PAT_ELEM_CLEAR.matcher(path).matches()
        || PAT_ELEM_TAP.matcher(path).matches()
        || PAT_ELEM_SUBMIT.matcher(path).matches();
  }

  /**
   * Builds the trace label for a command that passed {@link #shouldTrace}. For element-bound
   * actions (click, type, clear, …) the selector stored by the most recent findElement call is
   * appended, e.g.:
   *
   * <ul>
   *   <li>{@code "Click — #submit"}
   *   <li>{@code "Type — 'hello world' (#email)"}
   *   <li>{@code "Clear — input[name='q']"}
   * </ul>
   */
  private static String buildLabel(HttpRequest req, SessionTraceContext ctx) {
    String base = actionLabel(req);
    String path = strippedPath(req.getUri());
    if (PAT_ELEM_INTERACT.matcher(path).matches()) {
      String sel = formatSelector(ctx.selectorForElement(elementIdFromPath(path)));
      if (sel == null) {
        sel = formatSelector(ctx.consumeLastSelector());
      }
      if (sel != null) {
        // If the base label already contains detail (em dash), append selector in parens.
        // Otherwise use em dash so the format is consistent with other labels.
        return base.contains("\u2014") ? base + " (" + sel + ")" : base + " \u2014 " + sel;
      }
    }
    return base;
  }

  /**
   * Extracts the locator from a findElement request body as a human-readable string, e.g. {@code
   * "css selector: #submit"} → {@code "#submit"}, {@code "xpath: //button"} → {@code "xpath:
   * //button"}.
   */
  private static String extractSelector(HttpRequest req) {
    try {
      String body = readBody(req);
      if (body == null) return null;
      String using = extractJsonField(body, "using");
      String value = extractJsonField(body, "value");
      if (using == null || value == null) return null;
      return using + ": " + value;
    } catch (Exception ignored) {
      return null;
    }
  }

  /** Strips the verbose {@code "css selector: "} prefix; leaves other strategies intact. */
  private static String formatSelector(String raw) {
    if (raw == null) return null;
    return raw.startsWith("css selector: ") ? raw.substring("css selector: ".length()) : raw;
  }

  // ---- Action label construction -------------------------------------------

  /**
   * Returns a human-readable action label for the given WebDriver request, combining a semantic
   * action name (e.g. {@code "Navigate"}, {@code "Click"}) with key metadata extracted from the
   * request body (e.g. the target URL, CSS selector, or typed text).
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "Navigate — https://example.com"}
   *   <li>{@code "Click"}
   *   <li>{@code "Type — 'hello world'"}
   *   <li>{@code "FindElement — css selector: #submit"}
   *   <li>{@code "ExecuteScript — return document.title"}
   * </ul>
   */
  static String actionLabel(HttpRequest req) {
    String method = req.getMethod().toString();
    String path = strippedPath(req.getUri());
    String name = semanticName(method, path);
    String detail = bodyDetail(method, path, req);
    return detail.isEmpty() ? name : name + " \u2014 " + detail;
  }

  /** Strips the {@code /session/{id}} prefix, returning just the command path segment. */
  private static String strippedPath(String uri) {
    int sessionIdx = uri.indexOf("/session/");
    if (sessionIdx < 0) return uri;
    int afterId = uri.indexOf('/', sessionIdx + "/session/".length());
    return afterId > 0 ? uri.substring(afterId) : uri;
  }

  /**
   * Maps a WebDriver (method, path) pair to a human-readable action name. Falls back to the raw
   * "{@code METHOD /path}" if no mapping exists.
   */
  private static String semanticName(String method, String path) {
    switch (method + " " + path) {
      case "POST /url":
        return "Navigate";
      case "POST /forward":
        return "Forward";
      case "POST /back":
        return "Back";
      case "POST /refresh":
        return "Refresh";
      case "POST /element":
        return "FindElement";
      case "POST /elements":
        return "FindElements";
      case "POST /execute/sync":
        return "ExecuteScript";
      case "POST /execute/async":
        return "ExecuteAsyncScript";
      case "POST /actions":
        return "PerformActions";
      case "DELETE /actions":
        return "ReleaseActions";
      case "POST /window":
        return "SwitchWindow";
      case "POST /window/maximize":
        return "Maximize";
      case "POST /window/minimize":
        return "Minimize";
      case "POST /window/fullscreen":
        return "Fullscreen";
      case "POST /window/new":
        return "NewWindow";
      case "DELETE /window":
        return "CloseWindow";
      case "POST /frame":
        return "SwitchFrame";
      case "POST /frame/parent":
        return "SwitchParentFrame";
      case "POST /alert/accept":
        return "AcceptAlert";
      case "POST /alert/dismiss":
        return "DismissAlert";
      case "POST /alert/text":
        return "TypeAlert";
      case "GET /alert/text":
        return "GetAlertText";
      case "GET /screenshot":
        return "Screenshot";
      case "GET /title":
        return "GetTitle";
      case "GET /url":
        return "GetCurrentUrl";
      case "GET /source":
        return "GetPageSource";
      case "POST /cookie":
        return "AddCookie";
      case "DELETE /cookie":
        return "DeleteAllCookies";
      case "GET /cookie":
        return "GetCookies";
      case "POST /timeouts":
        return "SetTimeouts";
      case "GET /timeouts":
        return "GetTimeouts";
      default:
        break;
    }
    // Element-bound commands whose path contains an opaque element ID segment.
    if (PAT_ELEM_CLICK.matcher(path).matches()) return "Click";
    if (PAT_ELEM_CLEAR.matcher(path).matches()) return "Clear";
    if (PAT_ELEM_VALUE.matcher(path).matches()) return "Type";
    if (PAT_ELEM_ELEMENT.matcher(path).matches()) return "FindChildElement";
    if (PAT_ELEM_ELEMENTS.matcher(path).matches()) return "FindChildElements";
    if (PAT_ELEM_SCREENSHOT.matcher(path).matches()) return "ElementScreenshot";
    if (PAT_ELEM_SUBMIT.matcher(path).matches()) return "Submit";
    if (PAT_ELEM_TAP.matcher(path).matches()) return "Tap";
    if (PAT_ELEM_TEXT.matcher(path).matches()) return "GetText";
    if (PAT_ELEM_ATTR.matcher(path).matches()) return "GetAttribute";
    if (PAT_ELEM_PROP.matcher(path).matches()) return "GetProperty";
    if (PAT_ELEM_CSS.matcher(path).matches()) return "GetCssValue";
    if (PAT_ELEM_ENABLED.matcher(path).matches()) return "IsEnabled";
    if (PAT_ELEM_DISPLAYED.matcher(path).matches()) return "IsDisplayed";
    if (PAT_ELEM_SELECTED.matcher(path).matches()) return "IsSelected";
    if (PAT_ELEM_RECT.matcher(path).matches()) return "GetRect";
    if (PAT_COOKIE.matcher(path).matches() && "DELETE".equals(method)) return "DeleteCookie";
    if (PAT_COOKIE.matcher(path).matches() && "GET".equals(method)) return "GetCookie";
    // Fallback to raw label.
    return method + " " + path;
  }

  /**
   * Reads the request body and extracts metadata relevant to the given path. Returns an empty
   * string if no useful detail can be extracted.
   */
  private static String bodyDetail(String method, String path, HttpRequest req) {
    if (!"POST".equals(method) && !"PUT".equals(method)) return "";
    try {
      String body = readBody(req);
      if (body == null || body.isBlank()) return "";

      switch (path) {
        case "/url":
          {
            String url = extractJsonField(body, "url");
            return url != null ? url : "";
          }
        case "/element":
        case "/elements":
          {
            String using = extractJsonField(body, "using");
            String value = extractJsonField(body, "value");
            return (using != null && value != null) ? using + ": " + value : "";
          }
        case "/execute/sync":
        case "/execute/async":
          {
            String script = extractJsonField(body, "script");
            if (script == null) return "";
            // Trim to a single line for readability in the trace viewer.
            String line =
                script
                    .lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .findFirst()
                    .orElse(script);
            return line.length() > 60 ? line.substring(0, 57) + "\u2026" : line;
          }
        case "/window":
          {
            String handle = extractJsonField(body, "handle");
            return handle != null ? handle : "";
          }
        case "/frame":
          {
            String id = extractJsonField(body, "id");
            return id != null ? id : "";
          }
        default:
          break;
      }

      // Type / SendKeys
      if (PAT_ELEM_VALUE.matcher(path).matches()) {
        String text = extractJsonField(body, "text");
        if (text == null) return "";
        return text.length() > 40 ? "'" + text.substring(0, 37) + "\u2026'" : "'" + text + "'";
      }
      // Child element find
      if (PAT_FIND_CHILD.matcher(path).matches()) {
        String using = extractJsonField(body, "using");
        String value = extractJsonField(body, "value");
        return (using != null && value != null) ? using + ": " + value : "";
      }
    } catch (Exception ignored) {
      // Body detail is best-effort; never fail the trace for this.
    }
    return "";
  }

  /**
   * Reads the request body as a UTF-8 string. Returns {@code null} if the body is absent or
   * unreadable. The underlying {@code Supplier<InputStream>} is re-entrant (backed by a byte[]) so
   * reading here does not consume the body for subsequent processing.
   */
  private static String readBody(HttpRequest req) {
    try {
      InputStream is = req.getContent().get();
      if (is == null) return null;
      try (InputStream stream = is) {
        byte[] bytes = stream.readAllBytes();
        return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
      }
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Extracts a JSON string-field value using a simple hand-rolled parser. Not a full JSON parser,
   * but robust enough for the well-structured WebDriver request bodies we care about.
   */
  static String extractJsonField(String json, String key) {
    String needle = "\"" + key + "\"";
    int idx = json.indexOf(needle);
    if (idx < 0) return null;
    idx += needle.length();
    // Skip whitespace and colon.
    while (idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == ':')) idx++;
    if (idx >= json.length() || json.charAt(idx) != '"') return null;
    idx++; // opening quote
    StringBuilder sb = new StringBuilder();
    while (idx < json.length()) {
      char c = json.charAt(idx++);
      if (c == '"') break;
      if (c == '\\' && idx < json.length()) {
        char esc = json.charAt(idx++);
        switch (esc) {
          case '"':
            sb.append('"');
            break;
          case '\\':
            sb.append('\\');
            break;
          case '/':
            sb.append('/');
            break; // \/ is a valid JSON escape for /
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'u': // JSON Unicode escape sequence (4 hex digits)
            if (idx + 4 <= json.length()) {
              try {
                sb.append((char) Integer.parseInt(json, idx, idx + 4, 16));
                idx += 4;
              } catch (NumberFormatException e) {
                sb.append('u'); // malformed — emit literally
              }
            } else {
              sb.append('u');
            }
            break;
          default:
            sb.append(esc);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  // ---- Legacy helper (used by unit tests) ----------------------------------

  /**
   * Returns a short raw label for the given WebDriver HTTP request, e.g. {@code "POST
   * /element/x/click"}. The session ID prefix is stripped to keep trace group names readable.
   *
   * @deprecated Prefer {@link #actionLabel(HttpRequest)} for richer, semantic labels.
   */
  @Deprecated
  static String commandLabel(HttpRequest req) {
    String uri = req.getUri();
    int sessionIdx = uri.indexOf("/session/");
    if (sessionIdx >= 0) {
      int afterId = uri.indexOf('/', sessionIdx + "/session/".length());
      if (afterId > 0) {
        return req.getMethod() + " " + uri.substring(afterId);
      }
    }
    return req.getMethod() + " " + uri;
  }
}
