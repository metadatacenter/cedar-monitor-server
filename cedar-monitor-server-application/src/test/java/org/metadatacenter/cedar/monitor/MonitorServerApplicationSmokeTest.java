package org.metadatacenter.cedar.monitor;

import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.metadatacenter.config.environment.CedarEnvironmentSource;
import org.metadatacenter.util.test.EmbeddedCedarMySql;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Boots the real application through the Dropwizard test support harness and exercises the wiring no
 * backend is needed for. This catches configuration and startup rot that a config-only test
 * cannot see.
 */
@Tag("integration")
public class MonitorServerApplicationSmokeTest {

  static {
    // Must run before the test support boots the server, which reads the port env vars.
    // OS-assigned server ports, so the test instance never collides with a running dev server.
    Map<String, String> environment = new HashMap<>(CedarEnvironmentSource.getAll());
    environment.put("CEDAR_MONITOR_HTTP_PORT", "0");
    environment.put("CEDAR_MONITOR_ADMIN_PORT", "0");
    environment.put("CEDAR_MONITOR_STOP_PORT", "0");
    // Startup opens the log database, so that is embedded. The graph and OpenSearch are dead ports:
    // nothing here should reach them, and if something starts to, it fails here rather than reading
    // whatever the developer has running.
    environment.put("CEDAR_NEO4J_HOST", "127.0.0.1");
    environment.put("CEDAR_NEO4J_BOLT_PORT", "1");
    environment.put("CEDAR_OPENSEARCH_HOST", "127.0.0.1");
    environment.put("CEDAR_OPENSEARCH_REST_PORT", "1");
    CedarEnvironmentSource.setOverride(environment);
    EmbeddedCedarMySql.startAndRedirectEnvironment("CEDAR_LOG_MYSQL", Map.of());
  }

  private static final DropwizardTestSupport<MonitorServerConfiguration> SERVER =
      new DropwizardTestSupport<>(MonitorServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  @BeforeAll
  public static void startServer() throws Exception {
    SERVER.before();
  }

  @AfterAll
  public static void stopServer() {
    SERVER.after();
  }

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .GET()
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void monitorDoesNotInitializeAnArtifactDocumentStore() {
    Assertions.assertEquals(org.metadatacenter.cedar.util.dw.CedarMicroserviceApplication.class,
        MonitorServerApplication.class.getSuperclass());
    Assertions.assertFalse(SERVER.getEnvironment().healthChecks().getNames().contains("mongo"));
  }

  @Test
  public void indexIsServed() throws Exception {
    HttpResponse<String> response = get("/");
    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertTrue(response.body().contains("name"));
  }

}
