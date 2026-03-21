package org.openqa.selenium.grid.node.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;

class PlaywrightTraceRecorderTest {

  // ---- commandLabel (legacy) -----------------------------------------------

  @Test
  void commandLabel_extractsPathAfterSessionId() {
    HttpRequest req = new HttpRequest(HttpMethod.POST, "/session/abc123/element/xyz/click");
    assertThat(PlaywrightTraceRecorder.commandLabel(req)).isEqualTo("POST /element/xyz/click");
  }

  @Test
  void commandLabel_handlesNewSessionRequest() {
    HttpRequest req = new HttpRequest(HttpMethod.POST, "/session");
    assertThat(PlaywrightTraceRecorder.commandLabel(req)).isEqualTo("POST /session");
  }

  @Test
  void commandLabel_handlesDeleteSession() {
    HttpRequest req = new HttpRequest(HttpMethod.DELETE, "/session/abc123");
    assertThat(PlaywrightTraceRecorder.commandLabel(req)).isEqualTo("DELETE /session/abc123");
  }

  // ---- actionLabel — semantic names ----------------------------------------

  @Test
  void actionLabel_navigate() {
    HttpRequest req = post("/session/s1/url", "{\"url\":\"https://example.com\"}");
    assertThat(PlaywrightTraceRecorder.actionLabel(req))
        .isEqualTo("Navigate \u2014 https://example.com");
  }

  @Test
  void actionLabel_click() {
    HttpRequest req = new HttpRequest(HttpMethod.POST, "/session/s1/element/elem1/click");
    req.setContent(Contents.utf8String("{}"));
    assertThat(PlaywrightTraceRecorder.actionLabel(req)).isEqualTo("Click");
  }

  @Test
  void actionLabel_type() {
    HttpRequest req = post("/session/s1/element/elem1/value", "{\"text\":\"hello world\"}");
    assertThat(PlaywrightTraceRecorder.actionLabel(req)).isEqualTo("Type \u2014 'hello world'");
  }

  @Test
  void actionLabel_typeLongTextTruncated() {
    String text = "a".repeat(50);
    HttpRequest req = post("/session/s1/element/elem1/value", "{\"text\":\"" + text + "\"}");
    String label = PlaywrightTraceRecorder.actionLabel(req);
    assertThat(label).startsWith("Type \u2014 '");
    assertThat(label).endsWith("\u2026'");
    assertThat(label.length()).isLessThan(60);
  }

  @Test
  void actionLabel_findElement() {
    HttpRequest req =
        post("/session/s1/element", "{\"using\":\"css selector\",\"value\":\"#submit\"}");
    assertThat(PlaywrightTraceRecorder.actionLabel(req))
        .isEqualTo("FindElement \u2014 css selector: #submit");
  }

  @Test
  void actionLabel_executeScript() {
    HttpRequest req =
        post("/session/s1/execute/sync", "{\"script\":\"return document.title\",\"args\":[]}");
    assertThat(PlaywrightTraceRecorder.actionLabel(req))
        .isEqualTo("ExecuteScript \u2014 return document.title");
  }

  @Test
  void actionLabel_back() {
    HttpRequest req = new HttpRequest(HttpMethod.POST, "/session/s1/back");
    req.setContent(Contents.utf8String("{}"));
    assertThat(PlaywrightTraceRecorder.actionLabel(req)).isEqualTo("Back");
  }

  @Test
  void actionLabel_forward() {
    HttpRequest req = new HttpRequest(HttpMethod.POST, "/session/s1/forward");
    req.setContent(Contents.utf8String("{}"));
    assertThat(PlaywrightTraceRecorder.actionLabel(req)).isEqualTo("Forward");
  }

  @Test
  void actionLabel_screenshot() {
    HttpRequest req = new HttpRequest(HttpMethod.GET, "/session/s1/screenshot");
    assertThat(PlaywrightTraceRecorder.actionLabel(req)).isEqualTo("Screenshot");
  }

  @Test
  void actionLabel_closeWindow() {
    HttpRequest req = new HttpRequest(HttpMethod.DELETE, "/session/s1/window");
    assertThat(PlaywrightTraceRecorder.actionLabel(req)).isEqualTo("CloseWindow");
  }

  @Test
  void actionLabel_getAttribute() {
    HttpRequest req = new HttpRequest(HttpMethod.GET, "/session/s1/element/elem1/attribute/href");
    assertThat(PlaywrightTraceRecorder.actionLabel(req)).isEqualTo("GetAttribute");
  }

  @Test
  void actionLabel_fallsBackToRawLabelForUnknownCommand() {
    HttpRequest req = new HttpRequest(HttpMethod.GET, "/session/s1/some/unknown/path");
    assertThat(PlaywrightTraceRecorder.actionLabel(req)).isEqualTo("GET /some/unknown/path");
  }

  // ---- isSeleniumAtomScript ------------------------------------------------

  @Test
  void isSeleniumAtomScript_rawAtom() {
    HttpRequest req =
        post(
            "/session/s1/execute/sync",
            "{\"script\":\"return (function(){var a=1;return a;})()\",\"args\":[]}");
    assertThat(PlaywrightTraceRecorder.isSeleniumAtomScript(req)).isTrue();
  }

  @Test
  void isSeleniumAtomScript_namedAtom() {
    HttpRequest req =
        post(
            "/session/s1/execute/sync",
            "{\"script\":\"/* isDisplayed */return (function(){return true;})()\",\"args\":[]}");
    assertThat(PlaywrightTraceRecorder.isSeleniumAtomScript(req)).isTrue();
  }

  @Test
  void isSeleniumAtomScript_scrollIntoView() {
    HttpRequest req =
        post(
            "/session/s1/execute/sync",
            "{\"script\":\"arguments[0].scrollIntoView(true)\",\"args\":[{}]}");
    assertThat(PlaywrightTraceRecorder.isSeleniumAtomScript(req)).isTrue();
  }

  @Test
  void isSeleniumAtomScript_viewportWidthProbe() {
    HttpRequest req =
        post(
            "/session/s1/execute/sync",
            "{\"script\":\"return Math.max(document.documentElement.clientWidth, window.innerWidth || 0)\",\"args\":[]}");
    assertThat(PlaywrightTraceRecorder.isSeleniumAtomScript(req)).isTrue();
  }

  @Test
  void isSeleniumAtomScript_scrollPositionProbe() {
    HttpRequest req =
        post("/session/s1/execute/sync", "{\"script\":\"return window.pageYOffset\",\"args\":[]}");
    assertThat(PlaywrightTraceRecorder.isSeleniumAtomScript(req)).isTrue();
  }

  @Test
  void isSeleniumAtomScript_userScript() {
    HttpRequest req =
        post("/session/s1/execute/sync", "{\"script\":\"return document.title\",\"args\":[]}");
    assertThat(PlaywrightTraceRecorder.isSeleniumAtomScript(req)).isFalse();
  }

  @Test
  void isSeleniumAtomScript_userScriptWithVar() {
    // Multi-step scripts with variable declarations are never treated as probes.
    HttpRequest req =
        post(
            "/session/s1/execute/sync",
            "{\"script\":\"var x = window.pageYOffset; return x;\",\"args\":[]}");
    assertThat(PlaywrightTraceRecorder.isSeleniumAtomScript(req)).isFalse();
  }

  @Test
  void isSeleniumAtomScript_notExecuteSync() {
    HttpRequest req =
        post("/session/s1/execute/async", "{\"script\":\"return document.title\",\"args\":[]}");
    assertThat(PlaywrightTraceRecorder.isSeleniumAtomScript(req)).isFalse();
  }

  // ---- extractJsonField ----------------------------------------------------

  @Test
  void extractJsonField_simpleString() {
    assertThat(PlaywrightTraceRecorder.extractJsonField("{\"url\":\"https://x.com\"}", "url"))
        .isEqualTo("https://x.com");
  }

  @Test
  void extractJsonField_withSpacesAroundColon() {
    assertThat(PlaywrightTraceRecorder.extractJsonField("{\"key\" : \"val\"}", "key"))
        .isEqualTo("val");
  }

  @Test
  void extractJsonField_escapedQuotesInsideValue() {
    assertThat(
            PlaywrightTraceRecorder.extractJsonField(
                "{\"script\":\"return \\\"hi\\\"\"}", "script"))
        .isEqualTo("return \"hi\"");
  }

  @Test
  void extractJsonField_unicodeEscapeDecoded() {
    // \u002f is '/', so xpath value "\/\/*" stored as "\u002f\u002f*" should decode to "//*"
    assertThat(PlaywrightTraceRecorder.extractJsonField("{\"value\":\"\\u002f\\u002f*\"}", "value"))
        .isEqualTo("//*");
  }

  @Test
  void extractJsonField_escapedForwardSlash() {
    // \/ is a valid JSON escape for /
    assertThat(PlaywrightTraceRecorder.extractJsonField("{\"value\":\"\\/\\/*\"}", "value"))
        .isEqualTo("//*");
  }

  @Test
  void extractJsonField_missingKeyReturnsNull() {
    assertThat(PlaywrightTraceRecorder.extractJsonField("{\"other\":\"val\"}", "key")).isNull();
  }

  // ---- helpers -------------------------------------------------------------

  private static HttpRequest post(String uri, String body) {
    HttpRequest req = new HttpRequest(HttpMethod.POST, uri);
    req.setContent(Contents.utf8String(body));
    return req;
  }
}
