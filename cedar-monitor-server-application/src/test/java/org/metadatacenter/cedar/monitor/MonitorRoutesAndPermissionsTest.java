package org.metadatacenter.cedar.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.monitor.resources.CommandResource;
import org.metadatacenter.cedar.monitor.resources.HealthChecksResource;
import org.metadatacenter.cedar.monitor.resources.RedisQueueCountsResource;
import org.metadatacenter.cedar.monitor.resources.ResourceCountsOpenSearchResource;
import org.metadatacenter.cedar.monitor.resources.ResourceCountsResource;
import org.metadatacenter.cedar.monitor.resources.ResourceInfoFolder;
import org.metadatacenter.cedar.monitor.resources.ResourceInfoGroup;
import org.metadatacenter.cedar.monitor.resources.ResourceInfoTemplate;
import org.metadatacenter.cedar.monitor.resources.ResourceInfoTemplateElement;
import org.metadatacenter.cedar.monitor.resources.ResourceInfoTemplateField;
import org.metadatacenter.cedar.monitor.resources.ResourceInfoTemplateInstance;
import org.metadatacenter.cedar.monitor.resources.ResourceInfoUser;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentSource;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.cedar.util.dw.CedarServerInsightReportResource;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.RouteSurface;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Pins the monitor server's authorization contract. Every monitor endpoint is guarded twice — it
 * asserts {@code LoggedIn} and then requires the {@code MONITOR_READ} permission — and nothing
 * else in the suite covered that. These are administrative endpoints exposing counts, queue depths
 * and per-resource internals, so the gate silently disappearing is the regression worth catching.
 *
 * <p>Three cases per route:
 * <ul>
 *   <li>no credentials → 401,</li>
 *   <li>a normal user (default/template-creator/metadata-creator roles, no monitor role) → 403,</li>
 *   <li>the admin user (every role, so it holds MONITOR_READ) → past both gates.</li>
 * </ul>
 *
 * <p>The admin case deliberately asserts only that the request is neither 401 nor 403: monitor
 * endpoints read OpenSearch, Redis and the graph, none of which run in this backend-free test, so
 * the response after the gate is expected to be an error rather than 200.
 */
public class MonitorRoutesAndPermissionsTest {

  static {
    // Must run before the test support boots the server, which reads the port env vars. Ports are
    // distinct from the dev server and from every other booting test class. Redis goes to a dead
    // port: no live Redis is needed to boot, and no probe here gets far enough to need one.
    Map<String, String> environment = new HashMap<>(CedarEnvironmentSource.getAll());
    environment.put("CEDAR_MONITOR_HTTP_PORT", "19020");
    environment.put("CEDAR_MONITOR_ADMIN_PORT", "19120");
    environment.put("CEDAR_MONITOR_STOP_PORT", "19220");
    environment.put("CEDAR_REDIS_PERSISTENT_PORT", "1");
    environment.put("CEDAR_ARTIFACT_ADMIN_PORT", "1");
    CedarEnvironmentSource.setOverride(environment);
  }

  private static final DropwizardTestSupport<MonitorServerConfiguration> SERVER =
      new DropwizardTestSupport<>(MonitorServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  /** Every monitor resource class that declares endpoints (the index and abstract bases do not). */
  private static final List<Class<?>> RESOURCE_CLASSES = List.of(
      ResourceCountsResource.class,
      ResourceCountsOpenSearchResource.class,
      RedisQueueCountsResource.class,
      HealthChecksResource.class,
      CommandResource.class,
      ResourceInfoFolder.class,
      ResourceInfoGroup.class,
      ResourceInfoUser.class,
      ResourceInfoTemplate.class,
      ResourceInfoTemplateElement.class,
      ResourceInfoTemplateField.class,
      ResourceInfoTemplateInstance.class,
      CedarServerInsightReportResource.class);

  private static String normalUserAuthHeader;
  private static String adminUserAuthHeader;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_MONITOR);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    normalUserAuthHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    adminUserAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @Test
  public void everyRouteRejectsAnUnauthenticatedRequest() {
    RouteSurface.assertEveryRouteAnswers(
        "http://localhost:" + SERVER.getLocalPort(),
        RouteSurface.endpoints(RESOURCE_CLASSES),
        401);
  }

  @Test
  public void everyRouteDeniesAUserWithoutTheMonitorPermission() {
    StringBuilder failures = new StringBuilder();
    List<RouteSurface.Endpoint> endpoints = RouteSurface.endpoints(RESOURCE_CLASSES);
    // Guard against a vacuous pass: these assertions live inside a loop, so an empty surface would
    // silently assert nothing.
    Assertions.assertFalse(endpoints.isEmpty(), "No monitor endpoints found by reflection");
    for (RouteSurface.Endpoint endpoint : endpoints) {
      int status = statusWithAuth(endpoint, normalUserAuthHeader, failures);
      if (status > 0 && status != 403) {
        failures.append(endpoint.key()).append(": expected 403 for a user without MONITOR_READ but got ")
            .append(status).append('\n');
      }
    }
    Assertions.assertEquals(0, failures.length(),
        "Monitor endpoints did not deny a user lacking MONITOR_READ:\n" + failures);
  }

  @Test
  public void everyRouteAdmitsTheAdminUserPastBothGates() {
    StringBuilder failures = new StringBuilder();
    List<RouteSurface.Endpoint> endpoints = RouteSurface.endpoints(RESOURCE_CLASSES);
    Assertions.assertFalse(endpoints.isEmpty(), "No monitor endpoints found by reflection");
    for (RouteSurface.Endpoint endpoint : endpoints) {
      int status = statusWithAuth(endpoint, adminUserAuthHeader, failures);
      if (status == 401 || status == 403) {
        failures.append(endpoint.key()).append(": admin holding MONITOR_READ was rejected with ")
            .append(status).append('\n');
      } else if (status == 404 || status == 405) {
        failures.append(endpoint.key()).append(": got ").append(status)
            .append(" - the route vanished or changed verb\n");
      }
    }
    Assertions.assertEquals(0, failures.length(),
        "Monitor endpoints did not admit an authorized admin:\n" + failures);
  }

  @Test
  public void threadDetailsReturnsDistinctDetailsForEachThread() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/insight/thread-details"))
        .header("Authorization", adminUserAuthHeader)
        .GET()
        .build();
    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, response.statusCode());
    JsonNode threads = JsonMapper.MAPPER.readTree(response.body());
    Assertions.assertTrue(threads.size() > 1, "Expected details for more than one live thread");
    HashSet<Long> threadIds = new HashSet<>();
    threads.fields().forEachRemaining(entry -> {
      Assertions.assertEquals(entry.getKey(), entry.getValue().get("name").asText());
      threadIds.add(entry.getValue().get("id").asLong());
    });
    Assertions.assertTrue(threadIds.size() > 1, "Every thread entry reused the same detail map");
  }

  /**
   * An authorized health probe is an inter-service proxy request. A stopped target is a temporary
   * dependency outage, so the monitor must answer 503 rather than leaking the proxy's transport
   * exception through the generic 500 mapper.
   */
  @Test
  public void stoppedServiceHealthCheckIsServiceUnavailable() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/health-check/artifact"))
        .header("Authorization", adminUserAuthHeader)
        .GET()
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(503, response.statusCode(), response.body());
    JsonNode error = JsonMapper.MAPPER.readTree(response.body());
    Assertions.assertEquals("SERVICE_UNAVAILABLE", error.path("status").asText(), response.body());
    Assertions.assertEquals("Downstream service is unavailable", error.path("message").asText(), response.body());
    Assertions.assertTrue(error.path("originalException").isMissingNode()
            || error.path("originalException").isNull(),
        "The response must not serialize the transport exception: " + response.body());
    Assertions.assertTrue(error.path("sourceException").isMissingNode()
            || error.path("sourceException").isNull(),
        "The response must not serialize the transport stack: " + response.body());
    Assertions.assertFalse(response.body().contains("127.0.0.1"),
        "The client-facing outage response must not expose the downstream URL: " + response.body());
  }

  /** Sends the endpoint's request with an Authorization header; records transport errors. */
  private int statusWithAuth(RouteSurface.Endpoint endpoint, String authHeader, StringBuilder failures) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          // resolvedPath, not fullPath: health-check declares /{server}, and an unsubstituted
          // template variable would not match the route.
          .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + RouteSurface.resolvedPath(endpoint)))
          .header("Authorization", authHeader);
      if (endpoint.verb.equals("POST") || endpoint.verb.equals("PUT")) {
        builder.header("Content-Type", RouteSurface.contentTypeFor(endpoint));
        builder.method(endpoint.verb, HttpRequest.BodyPublishers.ofString("{}"));
      } else {
        builder.method(endpoint.verb, HttpRequest.BodyPublishers.noBody());
      }
      HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return response.statusCode();
    } catch (Exception e) {
      failures.append(endpoint.key()).append(": request failed - ").append(e).append('\n');
      return -1;
    }
  }

}
