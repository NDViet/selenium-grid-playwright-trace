package org.openqa.selenium.grid.node.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds Playwright state for a single active session being traced. */
class SessionTraceContext {

  private final Browser browser;
  private final BrowserContext context;
  private final Path traceDir;
  private final String sessionId;

  // Chunk files written by stopChunk() after each traced command.
  // Accessed only from playwrightThread (single-threaded FIFO), no sync needed.
  private final List<Path> chunks = new ArrayList<>();
  private int chunkIndex = 0;

  // Selector stored by a findElement call so the immediately following element action
  // (click, type, etc.) can include it in its label, e.g. "Click — #submit".
  // Accessed only from the WebDriver command thread (sequential per session), no sync needed.
  private String lastSelector;

  SessionTraceContext(Browser browser, BrowserContext context, Path traceDir, String sessionId) {
    this.browser = browser;
    this.context = context;
    this.traceDir = traceDir;
    this.sessionId = sessionId;
  }

  Browser browser() {
    return browser;
  }

  BrowserContext context() {
    return context;
  }

  Path traceDir() {
    return traceDir;
  }

  String sessionId() {
    return sessionId;
  }

  /** Returns the path for the next chunk file and advances the counter. */
  Path nextChunkPath() {
    return traceDir.resolve("trace_" + sessionId + "_chunk_" + chunkIndex++ + ".zip");
  }

  void addChunk(Path chunk) {
    chunks.add(chunk);
  }

  List<Path> chunks() {
    return Collections.unmodifiableList(chunks);
  }

  void setLastSelector(String selector) {
    this.lastSelector = selector;
  }

  /** Returns the stored selector and clears it so it is only consumed once. */
  String consumeLastSelector() {
    String s = lastSelector;
    lastSelector = null;
    return s;
  }
}
