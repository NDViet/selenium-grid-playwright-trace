package org.openqa.selenium.grid.node.playwright;

import java.nio.file.Path;

/** Holds lightweight, connection-free state for a single active session being traced. */
class SessionTraceContext {

  private final String cdpWsUrl;
  private final Path traceDir;
  private final String sessionId;

  // Selector stored by a findElement call so the immediately following element action
  // (click, type, etc.) can include it in its label, e.g. "Click — #submit".
  // Accessed only from the WebDriver command thread (sequential per session), no sync needed.
  private String lastSelector;

  SessionTraceContext(String cdpWsUrl, Path traceDir, String sessionId) {
    this.cdpWsUrl = cdpWsUrl;
    this.traceDir = traceDir;
    this.sessionId = sessionId;
  }

  String cdpWsUrl() {
    return cdpWsUrl;
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
