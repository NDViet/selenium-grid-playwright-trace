package org.openqa.selenium.grid.node.playwright;

import com.microsoft.playwright.BrowserContext;
import java.nio.file.Path;

/** Holds Playwright state for a single active session being traced. */
class SessionTraceContext {

  private final BrowserContext context;
  private final Path traceDir;
  private final String sessionId;

  // Selector stored by a findElement call so the immediately following element action
  // (click, type, etc.) can include it in its label, e.g. "Click — #submit".
  // Accessed only from the WebDriver command thread (sequential per session), no sync needed.
  private String lastSelector;

  SessionTraceContext(BrowserContext context, Path traceDir, String sessionId) {
    this.context = context;
    this.traceDir = traceDir;
    this.sessionId = sessionId;
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
