package org.metadatacenter.cedar.monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentSource;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.test.EmbeddedCedarMySql;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Monitor's relay of another server's answer, against a resource server that answers.
 *
 * <p>The search index pages start a rebuild and read its progress through the resource server. The
 * Monitor forwards the caller's own credential, so the resource server decides who may rebuild, and
 * passes the resource server's status and body through unchanged. A relay that answered for the
 * resource server, dropped its refusal, or sent a credential of its own would change who may rebuild
 * the index, so each case here checks what reached the resource server as well as what came back.
 */
@Tag("integration")
public class MonitorRelayTest {

  private static final String REGENERATE = "/command/regenerate-search-index";
  private static final String JOB_STATUS = "/command/index-job-status";

  /** One request the resource server received. */
  private record Received(String method, String path, String authorization, String body) {}

  private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
  private static volatile int answerStatus;
  private static volatile String answerBody;
  private static final HttpServer RESOURCE_SERVER = startResourceServer();

  static {
    Map<String, String> environment = new HashMap<>(CedarEnvironmentSource.getAll());
    environment.put("CEDAR_MONITOR_HTTP_PORT", "0");
    environment.put("CEDAR_MONITOR_ADMIN_PORT", "0");
    environment.put("CEDAR_REDIS_PERSISTENT_PORT", "1");
    environment.put("CEDAR_OPENSEARCH_HOST", "127.0.0.1");
    environment.put("CEDAR_OPENSEARCH_REST_PORT", "1");
    environment.put("CEDAR_RESOURCE_SERVER_HOST", "127.0.0.1");
    environment.put("CEDAR_RESOURCE_HTTP_PORT", Integer.toString(RESOURCE_SERVER.getAddress().getPort()));
    environment.put("CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1");
    environment.put("CEDAR_ARTIFACT_HTTP_PORT", "1");
    environment.put("CEDAR_ARTIFACT_ADMIN_PORT", "1");
    CedarEnvironmentSource.setOverride(environment);
    EmbeddedCedarMySql.startAndRedirectEnvironment("CEDAR_LOG_MYSQL", Map.of());
    EmbeddedCedarNeo4j.startAndRedirectEnvironment();
  }

  private static final DropwizardTestSupport<MonitorServerConfiguration> SERVER =
      new DropwizardTestSupport<>(MonitorServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static String adminAuthHeader;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig =
        CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_MONITOR));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    adminAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    try {
      SERVER.after();
    } finally {
      RESOURCE_SERVER.stop(0);
    }
  }

  @BeforeEach
  void forgetEarlierRequests() {
    RECEIVED.clear();
  }

  /**
   * A rebuild is started with the caller's credential, and whatever the resource server answers, a
   * start, a refusal of the caller or a rebuild already running, reaches the caller as it was said.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "202 | {\"started\":true,\"jobId\":\"rebuild-1\"}",
      "403 | {\"errorKey\":\"noPermission\",\"message\":\"The caller may not rebuild the search index\"}",
      "409 | {\"errorKey\":\"alreadyRunning\",\"message\":\"A rebuild is already running\"}"})
  void aRebuildIsTheResourceServersToStartOrRefuse(int status, String body) throws Exception {
    answer(status, body);

    HttpResponse<String> response = send(HttpRequest.newBuilder(monitor("/search-index/regenerate"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}")));

    assertEquals(status, response.statusCode(), response.body());
    assertEquals(body, response.body());
    assertEquals(List.of(new Received("POST", REGENERATE, adminAuthHeader, "{\"force\":true}")), RECEIVED);
  }

  /** The progress of a rebuild is the resource server's answer, read with the caller's credential. */
  @Test
  void aRebuildsProgressIsTheResourceServersAnswer() throws Exception {
    String progress = "{\"state\":\"RUNNING\",\"indexed\":120,\"total\":400}";
    answer(200, progress);

    HttpResponse<String> response = send(HttpRequest.newBuilder(monitor("/search-index/job-status")).GET());

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(progress, response.body());
    assertEquals(List.of(new Received("GET", JOB_STATUS, adminAuthHeader, "")), RECEIVED);
  }

  private static void answer(int status, String body) {
    answerStatus = status;
    answerBody = body;
  }

  private static URI monitor(String path) {
    return URI.create("http://localhost:" + SERVER.getLocalPort() + path);
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return CLIENT.send(request.header("Authorization", adminAuthHeader).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // The resource server

  private static HttpServer startResourceServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", MonitorRelayTest::answer);
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub resource server", e);
    }
  }

  private static void answer(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    RECEIVED.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
        exchange.getRequestHeaders().getFirst("Authorization"), body));
    byte[] payload = answerBody.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(answerStatus, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
