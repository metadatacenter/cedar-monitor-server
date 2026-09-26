package org.metadatacenter.cedar.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentSource;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarMySql;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monitor server's log listings, answered in CEDAR's body envelope, against a real log database.
 *
 * <p>The server boots on an in-process MariaDB, creates the log tables itself, and each test seeds
 * the rows it reads. Every paged route is walked over HTTP: the envelope fields, the order, the
 * links a client follows, the filters those links must carry, the refusals, and the ceiling the raw
 * logs count to.
 */
public class LogListingPagingTest {

  static {
    redirectEnvironment();
  }

  private static void redirectEnvironment() {
    Map<String, String> ports = new HashMap<>();
    ports.put("CEDAR_MONITOR_HTTP_PORT", "0");
    ports.put("CEDAR_MONITOR_ADMIN_PORT", "0");
    ports.put("CEDAR_MONITOR_STOP_PORT", "0");
    ports.put("CEDAR_REDIS_PERSISTENT_PORT", "1");
    ports.put("CEDAR_RESOURCE_SERVER_HOST", "127.0.0.1");
    ports.put("CEDAR_RESOURCE_HTTP_PORT", "1");
    ports.put("CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1");
    ports.put("CEDAR_ARTIFACT_HTTP_PORT", "1");
    ports.put("CEDAR_ARTIFACT_ADMIN_PORT", "1");
    EmbeddedCedarMySql.startAndRedirectEnvironment("CEDAR_LOG_MYSQL", ports);
  }

  private static final DropwizardTestSupport<MonitorServerConfiguration> SERVER =
      new DropwizardTestSupport<>(MonitorServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private static final Instant THIS_HOUR = NOW.truncatedTo(ChronoUnit.HOURS);
  private static final List<String> TABLES = List.of("log_request", "log_cypher", "agg_request_outlier",
      "agg_cypher_outlier", "agg_request_hourly", "agg_cypher_hourly", "agg_request_user_hourly");

  private static String adminAuthHeader;
  private static String jdbcUrl;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    // Another class in this JVM may have replaced the environment override; restore ours.
    redirectEnvironment();
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_MONITOR);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    adminAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
    Map<String, String> env = CedarEnvironmentSource.getAll();
    jdbcUrl = "jdbc:mysql://127.0.0.1:" + env.get("CEDAR_LOG_MYSQL_PORT") + "/" + env.get("CEDAR_LOG_MYSQL_DB");
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @BeforeEach
  public void emptyTheLogTables() throws Exception {
    try (Connection c = connect(); Statement s = c.createStatement()) {
      for (String table : TABLES) {
        s.execute("DELETE FROM " + table);
      }
    }
  }

  // ---- raw request log -------------------------------------------------------------------------

  @Test
  public void aRequestPageCarriesTheEnvelope() throws Exception {
    seedRequests(25, "/folders/", 0);

    JsonNode page = get("/logs/explorer/requests?limit=10");

    assertEquals(10, page.get("requests").size());
    assertEquals(25, page.get("totalCount").asLong());
    assertEquals(0, page.get("currentOffset").asLong());
    assertEquals(10, page.get("request").get("limit").asInt());
    assertEquals(0, page.get("request").get("offset").asInt());
    assertFalse(page.has("countCapped"));
    assertTrue(page.get("paging").has("first"));
    assertTrue(page.get("paging").has("next"));
    assertTrue(page.get("paging").has("last"));
    assertFalse(page.get("paging").has("prev"));
    assertEquals("20", param(page.get("paging").get("last").asText(), "offset"));
  }

  @Test
  public void aRequestPageDefaultsToOneHundredRows() throws Exception {
    seedRequests(120, "/folders/", 0);

    JsonNode page = get("/logs/explorer/requests");

    assertEquals(100, page.get("requests").size());
    assertEquals(100, page.get("request").get("limit").asInt());
    assertEquals(120, page.get("totalCount").asLong());
  }

  @Test
  public void followingNextVisitsEveryRequestOnceNewestFirstEvenWhenTimestampsTie() throws Exception {
    // Twelve rows share one instant, so only the identity tie-break keeps the pages disjoint.
    seedRequests(13, "/a/", 0);
    seedRequestsAt(12, "/tie/", NOW.minusSeconds(3600));

    List<JsonNode> rows = walk("/logs/explorer/requests?limit=4", "requests");

    assertEquals(25, rows.size());
    Set<String> ids = new HashSet<>();
    for (JsonNode row : rows) {
      assertTrue(ids.add(row.get("globalRequestId").asText()), "row seen twice: " + row);
    }
    for (int i = 1; i < rows.size(); i++) {
      Instant previous = Instant.parse(rows.get(i - 1).get("requestTime").asText());
      Instant current = Instant.parse(rows.get(i).get("requestTime").asText());
      assertFalse(current.isAfter(previous), "rows are not newest first at " + i);
    }
  }

  @Test
  public void theFilterNarrowsTheTotalAndTravelsInEveryLink() throws Exception {
    seedRequests(9, "/folders/", 0);
    seedRequests(6, "/templates/", 100);

    JsonNode page = get("/logs/explorer/requests?q=templates&limit=4&minDurationMs=0");

    assertEquals(6, page.get("totalCount").asLong());
    for (JsonNode row : page.get("requests")) {
      assertTrue(row.get("path").asText().contains("/templates/"));
    }
    String next = page.get("paging").get("next").asText();
    assertEquals("templates", param(next, "q"));
    assertEquals("0", param(next, "minDurationMs"));
    assertEquals("4", param(next, "offset"));
    assertEquals(2, get(pathOf(next)).get("requests").size());
  }

  @Test
  public void theDurationFilterCountsOnlySlowRequests() throws Exception {
    seedRequests(5, "/fast/", 0);
    seedRequestsWithDuration(3, "/slow/", 5_000_000_000L);

    JsonNode page = get("/logs/explorer/requests?minDurationMs=1000");

    assertEquals(3, page.get("totalCount").asLong());
    assertEquals(3, page.get("requests").size());
  }

  @Test
  public void anOffsetPastTheEndAnswersAnEmptyPage() throws Exception {
    seedRequests(5, "/folders/", 0);

    JsonNode page = get("/logs/explorer/requests?offset=50&limit=10");

    assertEquals(0, page.get("requests").size());
    assertEquals(5, page.get("totalCount").asLong());
    assertEquals(50, page.get("currentOffset").asLong());
    assertFalse(page.get("paging").has("next"));
  }

  @Test
  public void outOfRangePagingIsRefused() throws Exception {
    assertEquals(400, status("/logs/explorer/requests?limit=0"));
    assertEquals(400, status("/logs/explorer/requests?limit=501"));
    assertEquals(400, status("/logs/explorer/requests?offset=-1"));
    assertEquals(400, status("/logs/explorer/requests?offset=10001"));
    assertEquals(200, status("/logs/explorer/requests?offset=10000"));
    assertEquals(200, status("/logs/explorer/requests?limit=500"));
  }

  // ---- raw Cypher log --------------------------------------------------------------------------

  @Test
  public void aCypherPageNamesItsRowsStatements() throws Exception {
    seedCypher(7, "MATCH (n) RETURN n");

    JsonNode page = get("/logs/explorer/cypher?limit=5");

    assertEquals(5, page.get("statements").size());
    assertEquals(7, page.get("totalCount").asLong());
    assertEquals(2, walk("/logs/explorer/cypher?limit=5&offset=5", "statements").size());
  }

  @Test
  public void aRawLogStopsCountingAtItsCeilingAndSaysSo() throws Exception {
    seedCypher(10_600, "MATCH (n) RETURN n");

    JsonNode page = get("/logs/explorer/cypher?limit=100");

    assertEquals(10_500, page.get("totalCount").asLong());
    assertTrue(page.get("countCapped").asBoolean());
    assertFalse(page.get("paging").has("last"));
    assertTrue(page.get("paging").has("next"));

    JsonNode deepest = get("/logs/explorer/cypher?limit=100&offset=10000");
    assertEquals(100, deepest.get("statements").size());
    assertTrue(deepest.get("paging").has("next"), "the page at the deepest offset still links onward");
  }

  @Test
  public void aRawLogBelowItsCeilingReportsAnExactCount() throws Exception {
    seedCypher(10_499, "MATCH (n) RETURN n");

    JsonNode page = get("/logs/explorer/cypher?limit=100");

    assertEquals(10_499, page.get("totalCount").asLong());
    assertFalse(page.has("countCapped"));
    assertTrue(page.get("paging").has("last"));
  }

  // ---- retained outliers -----------------------------------------------------------------------

  @Test
  public void requestOutliersPageSlowestFirstAndFilterByKind() throws Exception {
    seedRequestOutliers(6, "SLOW");
    seedRequestOutliers(4, "ERROR");

    JsonNode all = get("/logs/explorer/outliers/requests?limit=3");
    assertEquals(10, all.get("totalCount").asLong());
    List<JsonNode> walked = walk("/logs/explorer/outliers/requests?limit=3", "requests");
    assertEquals(10, walked.size());
    for (int i = 1; i < walked.size(); i++) {
      assertTrue(walked.get(i - 1).get("durationNanos").asLong() >= walked.get(i).get("durationNanos").asLong());
    }

    JsonNode errors = get("/logs/explorer/outliers/requests?kind=error&limit=3");
    assertEquals(4, errors.get("totalCount").asLong());
    assertEquals("error", param(errors.get("paging").get("next").asText(), "kind"));
  }

  @Test
  public void cypherOutliersPage() throws Exception {
    seedCypherOutliers(8);

    JsonNode page = get("/logs/explorer/outliers/cypher?limit=5&offset=5");

    assertEquals(8, page.get("totalCount").asLong());
    assertEquals(3, page.get("statements").size());
    assertTrue(page.get("paging").has("prev"));
    assertFalse(page.get("paging").has("next"));
  }

  // ---- usage breakdowns ------------------------------------------------------------------------

  @Test
  public void endpointsPageByVolumeCountDistinctEndpointsAndKeepTheRange() throws Exception {
    // Seven endpoints in range, each split over two hours so a total must merge rows; one out of range.
    for (int e = 0; e < 7; e++) {
      seedRequestHourly(THIS_HOUR.minus(2, ChronoUnit.HOURS), "Resource" + e, 10 + e);
      seedRequestHourly(THIS_HOUR.minus(3, ChronoUnit.HOURS), "Resource" + e, 10);
    }
    seedRequestHourly(THIS_HOUR.minus(30, ChronoUnit.DAYS), "Ancient", 1_000);
    String range = "from=" + THIS_HOUR.minus(1, ChronoUnit.DAYS) + "&to=" + THIS_HOUR;

    JsonNode first = get("/logs/usage/endpoints?limit=3&" + range);

    assertEquals(7, first.get("totalCount").asLong());
    assertEquals(3, first.get("endpoints").size());
    assertEquals("Resource6", first.get("endpoints").get(0).get("className").asText());
    assertEquals(26, first.get("endpoints").get(0).get("reqCount").asLong());
    String next = first.get("paging").get("next").asText();
    assertEquals(THIS_HOUR.minus(1, ChronoUnit.DAYS).toString(), param(next, "from"));
    assertEquals(THIS_HOUR.toString(), param(next, "to"));

    List<JsonNode> walked = walk("/logs/usage/endpoints?limit=3&" + range, "endpoints");
    assertEquals(7, walked.size());
    Set<String> names = new HashSet<>();
    walked.forEach(row -> names.add(row.get("className").asText()));
    assertEquals(7, names.size());
    assertFalse(names.contains("Ancient"));
  }

  @Test
  public void endpointsWithEqualVolumeStillPageDisjointly() throws Exception {
    for (int e = 0; e < 9; e++) {
      seedRequestHourly(THIS_HOUR.minus(2, ChronoUnit.HOURS), "Same" + e, 5);
    }
    String range = "from=" + THIS_HOUR.minus(1, ChronoUnit.DAYS) + "&to=" + THIS_HOUR;

    List<JsonNode> walked = walk("/logs/usage/endpoints?limit=2&" + range, "endpoints");

    Set<String> names = new HashSet<>();
    walked.forEach(row -> assertTrue(names.add(row.get("className").asText()), "seen twice: " + row));
    assertEquals(9, names.size());
  }

  @Test
  public void usageDefaultsToFiftyRows() throws Exception {
    for (int e = 0; e < 55; e++) {
      seedRequestHourly(THIS_HOUR.minus(2, ChronoUnit.HOURS), "Resource" + e, 1);
    }
    String range = "from=" + THIS_HOUR.minus(1, ChronoUnit.DAYS) + "&to=" + THIS_HOUR;

    JsonNode page = get("/logs/usage/endpoints?" + range);

    assertEquals(50, page.get("endpoints").size());
    assertEquals(55, page.get("totalCount").asLong());
  }

  @Test
  public void cypherUsagePagesDistinctStatements() throws Exception {
    for (int i = 0; i < 5; i++) {
      seedCypherHourly(THIS_HOUR.minus(2, ChronoUnit.HOURS), "hash" + i, 100 - i);
    }
    String range = "from=" + THIS_HOUR.minus(1, ChronoUnit.DAYS) + "&to=" + THIS_HOUR;

    JsonNode page = get("/logs/usage/cypher?limit=2&offset=2&" + range);

    assertEquals(5, page.get("totalCount").asLong());
    assertEquals(2, page.get("statements").size());
    assertEquals("hash2", page.get("statements").get(0).get("runnableHash").asText());
  }

  @Test
  public void userUsagePagesDistinctCallers() throws Exception {
    for (int i = 0; i < 4; i++) {
      seedUserHourly(THIS_HOUR.minus(2, ChronoUnit.HOURS), "user-" + i, 40 - i);
    }
    String range = "from=" + THIS_HOUR.minus(1, ChronoUnit.DAYS) + "&to=" + THIS_HOUR;

    JsonNode page = get("/logs/usage/users?limit=3&" + range);

    assertEquals(4, page.get("totalCount").asLong());
    assertEquals(3, page.get("users").size());
    assertEquals("user-0", page.get("users").get(0).get("userId").asText());
    assertEquals(1, get(pathOf(page.get("paging").get("next").asText())).get("users").size());
  }

  @Test
  public void usageRefusesOutOfRangePaging() throws Exception {
    assertEquals(400, status("/logs/usage/endpoints?limit=501"));
    assertEquals(400, status("/logs/usage/cypher?limit=0"));
    assertEquals(400, status("/logs/usage/users?offset=-5"));
  }

  @Test
  public void insightsStillReadTheBreakdowns() throws Exception {
    for (int e = 0; e < 3; e++) {
      seedRequestHourly(THIS_HOUR.minus(2, ChronoUnit.HOURS), "Resource" + e, 30);
    }
    String range = "from=" + THIS_HOUR.minus(1, ChronoUnit.DAYS) + "&to=" + THIS_HOUR;

    JsonNode insights = get("/logs/usage/insights?" + range);

    assertEquals(3, insights.get("slowestEndpoints").size());
  }

  // ---- HTTP ------------------------------------------------------------------------------------

  private static JsonNode get(String pathAndQuery) throws Exception {
    HttpResponse<String> response = send(pathAndQuery);
    assertEquals(200, response.statusCode(), pathAndQuery + " -> " + response.body());
    return JsonMapper.STRICT_MAPPER.readTree(response.body());
  }

  private static int status(String pathAndQuery) throws Exception {
    return send(pathAndQuery).statusCode();
  }

  private static HttpResponse<String> send(String pathAndQuery) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + pathAndQuery))
        .header("Authorization", adminAuthHeader)
        .GET().build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** Follows next links from the first page and gathers every row, as a client paging to the end would. */
  private static List<JsonNode> walk(String first, String collection) throws Exception {
    List<JsonNode> rows = new ArrayList<>();
    String next = first;
    int pages = 0;
    while (next != null) {
      JsonNode page = get(next);
      page.get(collection).forEach(rows::add);
      next = page.get("paging").has("next") ? pathOf(page.get("paging").get("next").asText()) : null;
      assertTrue(++pages < 1_000, "the walk did not end");
    }
    return rows;
  }

  private static String pathOf(String link) {
    URI uri = URI.create(link);
    assertEquals(SERVER.getLocalPort(), uri.getPort(), "links point back at the server that served them");
    return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }

  private static String param(String link, String name) {
    for (String pair : URI.create(link).getRawQuery().split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv[0].equals(name)) {
        return URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  // ---- seeding ---------------------------------------------------------------------------------

  private static Connection connect() throws Exception {
    return DriverManager.getConnection(jdbcUrl, "root", "");
  }

  private static int requestSequence = 0;

  private static void seedRequests(int n, String pathPrefix, int secondsBack) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      rows.add(requestRow(pathPrefix, NOW.minusSeconds(secondsBack + i), 1_000_000L));
    }
    insert("log_request", rows);
  }

  private static void seedRequestsAt(int n, String pathPrefix, Instant at) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      rows.add(requestRow(pathPrefix, at, 1_000_000L));
    }
    insert("log_request", rows);
  }

  private static void seedRequestsWithDuration(int n, String pathPrefix, long nanos) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      rows.add(requestRow(pathPrefix, NOW.minusSeconds(i), nanos));
    }
    insert("log_request", rows);
  }

  private static Map<String, Object> requestRow(String pathPrefix, Instant at, long nanos) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("globalRequestId", "req-" + (++requestSequence));
    row.put("requestTime", Timestamp.from(at));
    row.put("systemComponentName", "RESOURCE");
    row.put("httpMethod", "GET");
    row.put("path", pathPrefix + requestSequence);
    row.put("className", "FolderResource");
    row.put("methodName", "get");
    row.put("status", 200);
    row.put("handlerDuration", nanos);
    return row;
  }

  private static void seedCypher(int n, String runnable) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("logTime", Timestamp.from(NOW.minusSeconds(i)));
      row.put("systemComponentName", "RESOURCE");
      row.put("operation", "op");
      row.put("runnableHash", "h" + i);
      row.put("runnable", runnable);
      row.put("duration", 1_000L);
      rows.add(row);
    }
    insert("log_cypher", rows);
  }

  private static void seedRequestOutliers(int n, String kind) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("requestTime", Timestamp.from(NOW.minusSeconds(i)));
      row.put("systemComponentName", "RESOURCE");
      row.put("path", "/" + kind + "/" + i);
      row.put("kind", kind);
      row.put("durationNanos", 1_000_000L * (i + 1));
      rows.add(row);
    }
    insert("agg_request_outlier", rows);
  }

  private static void seedCypherOutliers(int n) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("logTime", Timestamp.from(NOW.minusSeconds(i)));
      row.put("systemComponentName", "RESOURCE");
      row.put("operation", "op");
      row.put("runnableHash", "o" + i);
      row.put("durationNanos", 1_000L * (i + 1));
      rows.add(row);
    }
    insert("agg_cypher_outlier", rows);
  }

  private static void seedRequestHourly(Instant hour, String className, long reqCount) throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("hourUtc", Timestamp.from(hour));
    row.put("systemComponentName", "RESOURCE");
    row.put("className", className);
    row.put("methodName", "get");
    row.put("httpMethod", "GET");
    row.put("statusClass", "2xx");
    row.put("authSource", "keycloak");
    row.put("reqCount", reqCount);
    insert("agg_request_hourly", List.of(row));
  }

  private static void seedCypherHourly(Instant hour, String runnableHash, long execCount) throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("hourUtc", Timestamp.from(hour));
    row.put("systemComponentName", "RESOURCE");
    row.put("operation", "op");
    row.put("runnableHash", runnableHash);
    row.put("execCount", execCount);
    insert("agg_cypher_hourly", List.of(row));
  }

  private static void seedUserHourly(Instant hour, String userId, long reqCount) throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("hourUtc", Timestamp.from(hour));
    row.put("userId", userId);
    row.put("authSource", "keycloak");
    row.put("apiKeyHash", "");
    row.put("reqCount", reqCount);
    insert("agg_request_user_hourly", List.of(row));
  }

  /**
   * Inserts rows, filling every column the schema requires and the row leaves out with a zero or an
   * empty string. The tables carry many counters a listing never reads; naming them all here would
   * tie the test to columns it has no opinion about.
   */
  private static void insert(String table, List<Map<String, Object>> rows) throws Exception {
    if (rows.isEmpty()) {
      return;
    }
    try (Connection c = connect()) {
      Map<String, Object> required = requiredColumns(c, table);
      List<String> columns = new ArrayList<>(rows.get(0).keySet());
      for (String column : required.keySet()) {
        if (!columns.contains(column)) {
          columns.add(column);
        }
      }
      String placeholders = "(" + String.join(",", columns.stream().map(x -> "?").toList()) + ")";
      String sql = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES " + placeholders;
      c.setAutoCommit(false);
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        int batched = 0;
        for (Map<String, Object> row : rows) {
          for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            ps.setObject(i + 1, row.containsKey(column) ? row.get(column) : required.get(column));
          }
          ps.addBatch();
          if (++batched % 1_000 == 0) {
            ps.executeBatch();
          }
        }
        ps.executeBatch();
      }
      c.commit();
    }
  }

  private static Map<String, Object> requiredColumns(Connection c, String table) throws Exception {
    Map<String, Object> out = new LinkedHashMap<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
            + "AND TABLE_NAME = ? AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL AND EXTRA NOT LIKE '%auto_increment%'")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String type = rs.getString(2).toLowerCase();
          boolean text = type.contains("char") || type.contains("text");
          boolean time = type.contains("time") || type.contains("date");
          out.put(rs.getString(1), text ? "" : time ? Timestamp.from(NOW) : 0);
        }
      }
    }
    return out;
  }
}
