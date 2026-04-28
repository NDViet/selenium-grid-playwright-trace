package org.openqa.selenium.grid.node.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Holds Playwright state for a single active session being traced. */
class SessionTraceContext {

  private final Path traceDir;
  private final String cdpUrl;
  private final String sessionId;
  private final String traceName;
  private Browser browser;
  private BrowserContext context;
  private boolean tracingStarted;

  // Selector stored by a findElement call so the immediately following element action
  // (click, type, etc.) can include it in its label, e.g. "Click — #submit".
  private String lastSelector;
  private final Map<String, String> elementSelectors = new HashMap<>();
  private final Map<String, String> verificationStates = new LinkedHashMap<>();
  private int documentReadyBoundary;
  private int recordedDocumentReadyBoundary;

  SessionTraceContext(Path traceDir, String cdpUrl, String sessionId, String traceName) {
    this.traceDir = traceDir;
    this.cdpUrl = cdpUrl;
    this.sessionId = sessionId;
    this.traceName = traceName;
  }

  Path traceDir() {
    return traceDir;
  }

  String cdpUrl() {
    return cdpUrl;
  }

  String sessionId() {
    return sessionId;
  }

  String traceName() {
    return traceName;
  }

  Browser browser() {
    return browser;
  }

  BrowserContext context() {
    return context;
  }

  boolean hasConnection() {
    return browser != null && context != null;
  }

  void setConnection(Browser browser, BrowserContext context) {
    this.browser = browser;
    this.context = context;
  }

  void clearConnection() {
    browser = null;
    context = null;
    tracingStarted = false;
  }

  boolean isTracingStarted() {
    return tracingStarted;
  }

  void setTracingStarted(boolean tracingStarted) {
    this.tracingStarted = tracingStarted;
  }

  synchronized void setLastSelector(String selector) {
    this.lastSelector = selector;
  }

  synchronized String peekLastSelector() {
    return lastSelector;
  }

  /** Returns the stored selector and clears it so it is only consumed once. */
  synchronized String consumeLastSelector() {
    String s = lastSelector;
    lastSelector = null;
    return s;
  }

  synchronized void rememberElementSelector(String elementId, String selector) {
    if (elementId != null && selector != null) {
      elementSelectors.put(elementId, selector);
    }
  }

  synchronized String selectorForElement(String elementId) {
    return elementId == null ? null : elementSelectors.get(elementId);
  }

  synchronized void markDocumentReadyBoundary() {
    documentReadyBoundary++;
  }

  synchronized String consumeDocumentReadyCheckpointKey() {
    if (documentReadyBoundary == recordedDocumentReadyBoundary) {
      return null;
    }
    recordedDocumentReadyBoundary = documentReadyBoundary;
    return "document-ready:" + documentReadyBoundary;
  }

  synchronized boolean shouldRecordVerificationState(String key, String state) {
    if (key == null || state == null || state.equals(verificationStates.get(key))) {
      return false;
    }
    verificationStates.put(key, state);
    while (verificationStates.size() > 200) {
      Iterator<String> keys = verificationStates.keySet().iterator();
      if (!keys.hasNext()) {
        break;
      }
      keys.next();
      keys.remove();
    }
    return true;
  }
}
