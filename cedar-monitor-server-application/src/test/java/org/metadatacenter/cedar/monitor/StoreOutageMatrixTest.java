package org.metadatacenter.cedar.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerElement;
import org.metadatacenter.model.folderserver.basic.FolderServerField;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarMySql;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Every Monitor answer that reads OpenSearch, with OpenSearch answering normally, answering with an
 * error, and not answering at all.
 *
 * <p>The diagnostic routes put each store's record of one subject side by side, and promised that a
 * store which could not be reached would leave its section null rather than fail the request. Null
 * is also what a store holding nothing gives, so an index that was down read as an artifact that had
 * never been indexed, and the Monitoring page had no way to tell the two apart. An index that
 * answered with an error, a missing one for instance, failed the whole answer with 500. The counts
 * routes, which must not answer in part, answered that error with 500 too, where an index that did
 * not answer was already a 503.
 *
 * <p>A diagnostic answer must now be 200 with the value null and the reason under the section's
 * {@code unavailable} whenever the index cannot say, and with no {@code unavailable} when it can. A
 * count must be 503 whenever the index cannot say. Keycloak never answers here, since its address
 * is on a host that does not resolve, so the user's Keycloak section must say so too.
 */
public class StoreOutageMatrixTest {

  /** How the stub OpenSearch answers. */
  private enum Index { ANSWERING, ERRORING, SILENT }

  private static final AtomicReference<Index> INDEX = new AtomicReference<>(Index.ANSWERING);
  private static final HttpServer BACKENDS = startBackends();

  static {
    Map<String, String> environment = new HashMap<>();
    environment.put("CEDAR_MONITOR_HTTP_PORT", "0");
    environment.put("CEDAR_MONITOR_ADMIN_PORT", "0");
    environment.put("CEDAR_MONITOR_STOP_PORT", "0");
    environment.put("CEDAR_REDIS_PERSISTENT_PORT", "1");
    // Keycloak's address is derived from the host, and a .test host never resolves, so no request
    // can reach a Keycloak running on this machine.
    environment.put("CEDAR_HOST", "metadatacenter.test");
    environment.put("CEDAR_OPENSEARCH_HOST", "127.0.0.1");
    environment.put("CEDAR_OPENSEARCH_REST_PORT", Integer.toString(BACKENDS.getAddress().getPort()));
    environment.put("CEDAR_RESOURCE_SERVER_HOST", "127.0.0.1");
    environment.put("CEDAR_RESOURCE_HTTP_PORT", Integer.toString(BACKENDS.getAddress().getPort()));
    environment.put("CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1");
    environment.put("CEDAR_ARTIFACT_HTTP_PORT", "1");
    environment.put("CEDAR_ARTIFACT_ADMIN_PORT", "1");
    EmbeddedCedarMySql.startAndRedirectEnvironment("CEDAR_LOG_MYSQL", Map.of());
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(environment);
  }

  private static final DropwizardTestSupport<MonitorServerConfiguration> SERVER =
      new DropwizardTestSupport<>(MonitorServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static String adminAuthHeader;
  private static final Map<String, String> SUBJECTS = new HashMap<>();

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_MONITOR));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    adminAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    CedarRequestContext user1 = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    FolderServiceSession folders = CedarDataServices.getInstance().getFolderServiceSession(user1);
    CedarFolderId home = folders.findHomeFolderOf().getResourceId();
    SUBJECTS.put("folders", home.getId());
    SUBJECTS.put("templates", create(cedarConfig, folders, home, new FolderServerTemplate(), CedarResourceType.TEMPLATE));
    SUBJECTS.put("template-elements", create(cedarConfig, folders, home, new FolderServerElement(), CedarResourceType.ELEMENT));
    SUBJECTS.put("template-fields", create(cedarConfig, folders, home, new FolderServerField(), CedarResourceType.FIELD));
    SUBJECTS.put("template-instances", create(cedarConfig, folders, home, new FolderServerInstance(), CedarResourceType.INSTANCE));
    SUBJECTS.put("groups", CedarDataServices.getInstance().getGroupServiceSession(user1).findGroups().get(0).getId());
    SUBJECTS.put("users", TestAuthUtil.getTestUser1(cedarConfig).getId());
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    BACKENDS.stop(0);
  }

  private static String create(CedarConfig cedarConfig, FolderServiceSession folders, CedarFolderId home,
                               FolderServerArtifact artifact, CedarResourceType type) {
    artifact.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(type));
    artifact.setName("Store outage matrix " + type.getValue());
    artifact.setDescription("");
    if (artifact instanceof FolderServerSchemaArtifact schema) {
      schema.setVersion("0.0.1");
      schema.setPublicationStatus("bibo:draft");
      schema.setLatestVersion(true);
      schema.setLatestDraftVersion(true);
      schema.setLatestPublishedVersion(false);
    }
    return folders.createResourceAsChildOfId(artifact, home).getId();
  }

  /** For each diagnostic route, where its OpenSearch value sits and what it is when the index cannot say. */
  private record Diagnostic(String route, String section, String value) {
    @Override
    public String toString() {
      return "/resource/" + route;
    }
  }

  private static final List<Diagnostic> DIAGNOSTICS = List.of(
      new Diagnostic("templates", "opensearch", "document"),
      new Diagnostic("template-elements", "opensearch", "document"),
      new Diagnostic("template-fields", "opensearch", "document"),
      new Diagnostic("template-instances", "opensearch", "document"),
      new Diagnostic("folders", "opensearch", "document"),
      new Diagnostic("groups", "opensearch", null),
      new Diagnostic("users", "opensearch", null));

  static Stream<Arguments> diagnostics() {
    List<Arguments> cases = new ArrayList<>();
    for (Diagnostic diagnostic : DIAGNOSTICS) {
      for (Index index : Index.values()) {
        cases.add(Arguments.of(diagnostic, index));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}, with OpenSearch {1}")
  @MethodSource("diagnostics")
  public void aDiagnosticSaysWhenAStoreCouldNotBeRead(Diagnostic diagnostic, Index index) throws Exception {
    HttpResponse<String> response = get("/resource/" + diagnostic.route() + "?id="
        + URLEncoder.encode(SUBJECTS.get(diagnostic.route()), StandardCharsets.UTF_8), index);

    Assertions.assertEquals(200, response.statusCode(), response.body());
    JsonNode answer = JsonMapper.STRICT_MAPPER.readTree(response.body());
    Assertions.assertFalse(answer.isEmpty(), "the subject is in the graph, so there is a record: " + response.body());
    JsonNode section = answer.path(diagnostic.section());
    boolean couldNotSay = index != Index.ANSWERING;
    Assertions.assertEquals(couldNotSay, section.hasNonNull("unavailable"),
        "the section says whether OpenSearch could be read: " + section);
    if (diagnostic.value() != null) {
      Assertions.assertTrue(section.path(diagnostic.value()).isNull(), "nothing to show: " + section);
    }
    switch (diagnostic.route()) {
      case "groups" -> Assertions.assertEquals(couldNotSay, answer.path("searchCedarIds").isNull(),
          "the identifiers are null exactly when the index could not say: " + answer.path("searchCedarIds"));
      case "users" -> {
        long viewerFields = section.path("viewerCount").path("field").asLong();
        Assertions.assertEquals(couldNotSay ? -1 : 0, viewerFields, "a count the index could not give is -1");
        JsonNode keycloak = answer.path("keycloak");
        Assertions.assertTrue(keycloak.hasNonNull("unavailable"), "Keycloak could not be read, and says so: " + keycloak);
        Assertions.assertTrue(keycloak.path("user").isNull());
      }
      default -> {
      }
    }
  }

  static Stream<Arguments> counts() {
    List<Arguments> cases = new ArrayList<>();
    for (String route : List.of("/resources/counts/opensearch", "/resources/counts")) {
      for (Index index : Index.values()) {
        cases.add(Arguments.of(route, index));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}, with OpenSearch {1}")
  @MethodSource("counts")
  public void aCountIsWholeOrUnavailable(String route, Index index) throws Exception {
    HttpResponse<String> response = get(route, index);

    Assertions.assertEquals(index == Index.ANSWERING ? 200 : 503, response.statusCode(), response.body());
    if (index == Index.ANSWERING) {
      JsonNode opensearch = JsonMapper.STRICT_MAPPER.readTree(response.body()).path("opensearch");
      Assertions.assertEquals(0, opensearch.path("template").asLong(-1), "the index's own count: " + opensearch);
    }
  }

  private static HttpResponse<String> get(String path, Index index) throws Exception {
    INDEX.set(index);
    try {
      return CLIENT.send(HttpRequest.newBuilder()
          .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
          .header("Authorization", adminAuthHeader).GET().build(), HttpResponse.BodyHandlers.ofString());
    } finally {
      INDEX.set(Index.ANSWERING);
    }
  }

  // The stub OpenSearch, and the resource server's artifact counts

  private static HttpServer startBackends() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", StoreOutageMatrixTest::answer);
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub backends", e);
    }
  }

  private static final String EMPTY_SEARCH = "{\"_scroll_id\":\"scroll\",\"took\":1,\"timed_out\":false,"
      + "\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0},"
      + "\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"max_score\":null,\"hits\":[]}}";

  private static void answer(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    String path = exchange.getRequestURI().getPath();
    if (path.endsWith("/artifact-counts")) {
      respond(exchange, 200, "{\"field\":0,\"element\":0,\"template\":0,\"instance\":0}");
      return;
    }
    switch (INDEX.get()) {
      case SILENT -> exchange.close();
      case ERRORING -> respond(exchange, 500, "{\"error\":{\"type\":\"index_not_found_exception\","
          + "\"reason\":\"no such index\"},\"status\":500}");
      default -> {
        if (path.contains("_count")) {
          respond(exchange, 200, "{\"count\":0,\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0}}");
        } else if ("DELETE".equals(exchange.getRequestMethod())) {
          respond(exchange, 200, "{\"succeeded\":true,\"num_freed\":1}");
        } else {
          respond(exchange, 200, EMPTY_SEARCH);
        }
      }
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
