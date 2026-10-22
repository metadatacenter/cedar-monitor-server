package org.metadatacenter.cedar.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.metadatacenter.config.CacheServerPersistent;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentSource;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.server.logging.dao.query.LogQueryDAO;
import org.metadatacenter.server.logging.dao.query.LogQueryDAO.TableFreshness;
import org.metadatacenter.server.queue.util.EmbeddedRedis;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarMySql;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;
import redis.clients.jedis.Jedis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.server.queue.util.QueueService.APP_LOG_QUEUE_ID;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the verdict of {@code /worker/lag}, the page that makes a stopped log worker visible.
 *
 * <p>Application logs are drained asynchronously, so a dead worker leaves every health check green
 * and shows only as log tables that stop moving. The verdict is therefore the alarm: an empty table
 * must not hide a stale one, and the thresholds must hold at their boundaries. The log tables'
 * freshness comes from a mocked DAO, so each case states its lags exactly; the queue depth comes from
 * a real Redis, because the route reads it directly.
 */
@Tag("integration")
public class WorkerLagResourceTest {

  private static final LogQueryDAO LOG_QUERY_DAO = mock(LogQueryDAO.class);
  private static final EmbeddedRedis REDIS = EmbeddedRedis.start();

  static {
    // Must run before the test support boots the server, which reads the port env vars.
    Map<String, String> environment = new HashMap<>(CedarEnvironmentSource.getAll());
    environment.put("CEDAR_MONITOR_HTTP_PORT", "0");
    environment.put("CEDAR_MONITOR_ADMIN_PORT", "0");
    environment.put("CEDAR_MONITOR_STOP_PORT", "0");
    environment.put("CEDAR_REDIS_PERSISTENT_HOST", "127.0.0.1");
    environment.put("CEDAR_REDIS_PERSISTENT_PORT", String.valueOf(REDIS.port()));
    environment.put("CEDAR_OPENSEARCH_HOST", "127.0.0.1");
    environment.put("CEDAR_OPENSEARCH_REST_PORT", "1");
    CedarEnvironmentSource.setOverride(environment);
    EmbeddedCedarMySql.startAndRedirectEnvironment("CEDAR_LOG_MYSQL", Map.of());
    EmbeddedCedarNeo4j.startAndRedirectEnvironment();
  }

  private static final DropwizardTestSupport<MonitorServerConfiguration> SERVER =
      new DropwizardTestSupport<>(TestMonitorServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String adminUserAuthHeader;
  private static CacheServerPersistent cacheConfig;

  public static class TestMonitorServerApplication extends MonitorServerApplication {
    @Override
    protected LogQueryDAO createLogQueryDAO() {
      return LOG_QUERY_DAO;
    }
  }

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig = CedarConfig.getInstance(
        CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_MONITOR));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    adminUserAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
    cacheConfig = cedarConfig.getCacheConfig().getPersistent();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    REDIS.close();
  }

  @Test
  public void anEmptyTableDoesNotHideAStalledOne() throws Exception {
    String queue = cacheConfig.getQueueName(APP_LOG_QUEUE_ID);
    try (Jedis jedis = new Jedis("127.0.0.1", REDIS.port())) {
      jedis.del(queue);
      jedis.rpush(queue, "a", "b", "c");
    }
    freshness(table("app", 30L), table("cypher", null), table("request", 7_200L));

    JsonNode report = lag();

    Assertions.assertEquals(3, report.path("appLogQueueDepth").asLong(), report.toString());
    Assertions.assertEquals(7_200, report.path("worstLagSeconds").asLong(), report.toString());
    Assertions.assertEquals("STALLED", report.path("status").asText(), report.toString());
    Assertions.assertEquals(List.of("app", "cypher", "request"),
        report.path("tables").findValuesAsText("table"), report.toString());
    Assertions.assertTrue(report.path("tables").get(1).path("lagSeconds").isNull(), report.toString());
  }

  @Test
  public void onlyEmptyTablesAreUnknownRatherThanOk() throws Exception {
    freshness(table("app", null), table("request", null));

    JsonNode report = lag();

    Assertions.assertTrue(report.path("worstLagSeconds").isNull(), report.toString());
    Assertions.assertEquals("UNKNOWN", report.path("status").asText(), report.toString());
  }

  /** A negative lag is a clock disagreement, reported as it is but not as the worker falling behind. */
  @ParameterizedTest
  @CsvSource({"-5, OK", "299, OK", "300, LAGGING", "3599, LAGGING", "3600, STALLED"})
  public void theVerdictChangesAtFiveMinutesAndAtOneHour(long lagSeconds, String status) throws Exception {
    freshness(table("request", lagSeconds));

    JsonNode report = lag();

    Assertions.assertEquals(lagSeconds, report.path("worstLagSeconds").asLong(), report.toString());
    Assertions.assertEquals(status, report.path("status").asText(), report.toString());
  }

  private static TableFreshness table(String name, Long lagSeconds) {
    return new TableFreshness(name, "log_" + name, "time", lagSeconds == null ? null : "2026-10-21T00:00:00Z",
        lagSeconds);
  }

  private static void freshness(TableFreshness... tables) {
    when(LOG_QUERY_DAO.freshness()).thenReturn(List.of(tables));
  }

  private static JsonNode lag() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/worker/lag"))
        .header("Authorization", adminUserAuthHeader)
        .GET().build();
    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(200, response.statusCode(), response.body());
    return JsonMapper.STRICT_MAPPER.readTree(response.body());
  }
}
