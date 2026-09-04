package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.HibernateConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * Report what CEDAR's MySQL databases hold.
 *
 * <p>CEDAR configures two MySQL datasources, {@code dbLogging} and {@code messagingServer}. Rather
 * than name the tables it expects, this asks each connection's {@code information_schema} what is
 * actually there, for every schema that connection's user can see. A host carrying frozen history
 * beside the live tables — {@code log_request_pre284} and {@code log_cypher_pre284}, renamed on
 * 2026-07-28 and still awaiting their backfill — reports both without this needing to know they
 * exist, and reports neither on a host where they were never created or have since been dropped.
 *
 * <p>Row counts come from {@code information_schema.tables}, which for InnoDB is an estimate from
 * the optimizer's sampling and can be off by a wide margin on a large table. That is the right
 * default for a page someone refreshes: it is one query per connection whatever the row count.
 * {@code ?exact=true} adds a real {@code COUNT(*)} per table, which on the log tables means a full
 * index scan of years of rows, so it is asked for rather than assumed.
 *
 * <p>Keycloak's own MySQL schema is deliberately not here. It is not one of CEDAR's configured
 * datasources — nothing in {@code cedar-main.yml} holds credentials for it — and the counts that
 * matter from it already have a home in the Keycloak section of the resource counts.
 */
@Path("/mysql")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Counts")
@SecurityRequirement(name = "api_key")
public class MySqlCountsResource extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(MySqlCountsResource.class);

  /**
   * The schemas MySQL keeps for itself, which say nothing about CEDAR's data.
   */
  private static final Set<String> SYSTEM_SCHEMAS =
      Set.of("mysql", "information_schema", "performance_schema", "sys");

  /**
   * Connection properties carried over from the datasource configuration.
   *
   * <p>The configuration mixes JDBC settings with Hibernate ones, and the driver rejects what it
   * does not recognize, so only these three are passed through. {@code createDatabaseIfNotExist} is
   * excluded on purpose as well as by omission: a read-only report is the last thing that should
   * bring a database into existence as a side effect of someone opening a page.
   */
  private static final Set<String> DRIVER_PROPERTIES =
      Set.of("useSSL", "allowPublicKeyRetrieval", "serverTimezone");

  /**
   * Seconds any one statement may run. A monitoring page that hangs is worse than one that reports
   * a timeout, and an exact count over the log tables is the statement most likely to need it.
   */
  private static final int QUERY_TIMEOUT_SECONDS = 30;

  private static final String TABLE_QUERY = """
      SELECT table_schema, table_name, engine, table_rows, avg_row_length,
             data_length, index_length, data_free, create_time, update_time
      FROM information_schema.tables
      WHERE table_type = 'BASE TABLE'
        AND table_schema NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
      """;

  public MySqlCountsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/counts")
  @Operation(summary = "Count and measure what MySQL holds",
      description = "Report every table in every MySQL schema CEDAR's configured datasources can " +
          "see — the application log database and the messaging database — with each table's row " +
          "count, engine, and the space its data, indexes and freed pages occupy. Row counts are " +
          "the optimizer's estimate unless exact=true is asked for, which counts the rows instead " +
          "and can take a while over the log tables.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A per-table report for each reachable database"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response mySqlCounts(
      @Parameter(description = "Count rows with COUNT(*) instead of reading the optimizer's estimate")
      @QueryParam("exact") @DefaultValue("false") boolean exact) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new LinkedHashMap<>();
    r.put("exactCounts", exact);

    List<Map<String, Object>> sources = new ArrayList<>();
    // Keyed by schema name: two datasources on one MySQL host can see the same schema, and it
    // should be reported once rather than once per connection that reached it.
    Map<String, Map<String, Object>> databases = new TreeMap<>();

    collect("log", "dbLogging", cedarConfig.getDBLoggingConfig(), exact, sources, databases);
    collect("messaging", "messagingServer", cedarConfig.getMessagingServerConfig(), exact, sources,
        databases);

    r.put("sources", sources);
    r.put("databases", new ArrayList<>(databases.values()));

    return Response.ok().entity(r).build();
  }

  /**
   * Read one datasource, recording what it reached and why it did not.
   *
   * <p>An unreachable database is reported rather than thrown: the point of the page is to say
   * which parts of CEDAR are answering, and one database being down is the thing worth seeing, not
   * a reason to show nothing.
   */
  private void collect(String id, String configKey, HibernateConfig config, boolean exact,
                       List<Map<String, Object>> sources,
                       Map<String, Map<String, Object>> databases) {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("id", id);
    source.put("configKey", configKey);
    sources.add(source);

    if (config == null || config.getUrl() == null) {
      source.put("configured", false);
      source.put("reachable", false);
      source.put("error", "No " + configKey + " datasource is configured");
      return;
    }
    source.put("configured", true);
    source.put("server", serverOf(config.getUrl()));
    source.put("schema", schemaOf(config.getUrl()));

    try (Connection connection = connect(config)) {
      // Nothing here writes. Saying so lets MySQL reject a write that slipped in rather than
      // trusting this class to keep not making one.
      connection.setReadOnly(true);
      List<String> reached = readTables(connection, id, exact, databases);
      source.put("reachable", true);
      source.put("error", null);
      source.put("schemas", reached);
    } catch (SQLException e) {
      // The message can carry the server and user, which the caller already holds the monitor
      // permission to see, but not the password: the driver does not put it in the message, and
      // the connection URL never contains it either.
      log.warn("Could not read MySQL {} datasource for the monitor report", configKey, e);
      source.put("reachable", false);
      source.put("error", e.getMessage());
    }
  }

  private Connection connect(HibernateConfig config) throws SQLException {
    Properties properties = new Properties();
    if (config.getUser() != null) {
      properties.setProperty("user", config.getUser());
    }
    if (config.getPassword() != null) {
      properties.setProperty("password", config.getPassword());
    }
    Map<String, String> configured = config.getProperties();
    if (configured != null) {
      configured.forEach((key, value) -> {
        if (DRIVER_PROPERTIES.contains(key) && value != null) {
          properties.setProperty(key, value);
        }
      });
    }
    return DriverManager.getConnection(config.getUrl(), properties);
  }

  private List<String> readTables(Connection connection, String sourceId, boolean exact,
                                  Map<String, Map<String, Object>> databases) throws SQLException {
    List<String> reached = new ArrayList<>();
    List<String[]> exactTargets = new ArrayList<>();

    try (PreparedStatement statement = connection.prepareStatement(TABLE_QUERY)) {
      statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String schema = rows.getString("table_schema");
          if (schema == null || SYSTEM_SCHEMAS.contains(schema)) {
            continue;
          }
          Map<String, Object> database = databases.computeIfAbsent(schema, name -> {
            Map<String, Object> fresh = new LinkedHashMap<>();
            fresh.put("name", name);
            fresh.put("source", sourceId);
            fresh.put("tables", new ArrayList<Map<String, Object>>());
            return fresh;
          });
          if (!reached.contains(schema)) {
            reached.add(schema);
          }

          Map<String, Object> table = new LinkedHashMap<>();
          String name = rows.getString("table_name");
          table.put("name", name);
          table.put("engine", rows.getString("engine"));
          // TABLE_ROWS is NULL for a table the optimizer has no statistics for, which is not the
          // same fact as a table holding no rows, so the null is kept rather than read as zero.
          table.put("rowsApproximate", nullableLong(rows, "table_rows"));
          table.put("rowsExact", null);
          long data = zeroIfNull(rows, "data_length");
          long index = zeroIfNull(rows, "index_length");
          table.put("dataBytes", data);
          table.put("indexBytes", index);
          table.put("totalBytes", data + index);
          // Pages the table has released but not returned to the filesystem. A large value beside a
          // small row count is what a table that was emptied but never rebuilt looks like.
          table.put("freeBytes", zeroIfNull(rows, "data_free"));
          table.put("averageRowBytes", nullableLong(rows, "avg_row_length"));
          table.put("createdAt", isoOrNull(rows.getTimestamp("create_time")));
          table.put("updatedAt", isoOrNull(rows.getTimestamp("update_time")));

          tablesOf(database).add(table);
          if (exact) {
            exactTargets.add(new String[]{schema, name});
          }
        }
      }
    }

    if (exact) {
      countExactly(connection, exactTargets, databases);
    }
    for (String schema : reached) {
      summarize(databases.get(schema));
    }
    return reached;
  }

  /**
   * Replace the estimate with a real count, per table.
   *
   * <p>A table that times out or disappears mid-report keeps its estimate and records why the exact
   * count is missing, so one slow table does not cost the caller the whole page.
   */
  private void countExactly(Connection connection, List<String[]> targets,
                            Map<String, Map<String, Object>> databases) {
    for (String[] target : targets) {
      String schema = target[0];
      String name = target[1];
      String sql = "SELECT COUNT(*) FROM " + quote(schema) + "." + quote(name);
      try (Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        try (ResultSet result = statement.executeQuery(sql)) {
          if (result.next()) {
            findTable(databases, schema, name).put("rowsExact", result.getLong(1));
          }
        }
      } catch (SQLException e) {
        log.warn("Could not count {}.{} exactly for the monitor report", schema, name, e);
        findTable(databases, schema, name).put("countError", e.getMessage());
      }
    }
  }

  /**
   * Total one database from the tables read for it, so the card has a figure per database without
   * the caller adding the columns up itself.
   */
  private void summarize(Map<String, Object> database) {
    if (database == null) {
      return;
    }
    List<Map<String, Object>> tables = tablesOf(database);
    tables.sort(Comparator.comparingLong(
        (Map<String, Object> table) -> (Long) table.get("totalBytes")).reversed()
        .thenComparing(table -> (String) table.get("name")));

    long data = 0;
    long index = 0;
    long free = 0;
    long approximate = 0;
    long exact = 0;
    boolean anyExact = false;
    for (Map<String, Object> table : tables) {
      data += (Long) table.get("dataBytes");
      index += (Long) table.get("indexBytes");
      free += (Long) table.get("freeBytes");
      Long rows = (Long) table.get("rowsApproximate");
      if (rows != null) {
        approximate += rows;
      }
      Long counted = (Long) table.get("rowsExact");
      if (counted != null) {
        anyExact = true;
        exact += counted;
      }
    }
    database.put("tableCount", tables.size());
    database.put("rowsApproximate", approximate);
    database.put("rowsExact", anyExact ? exact : null);
    database.put("dataBytes", data);
    database.put("indexBytes", index);
    database.put("totalBytes", data + index);
    database.put("freeBytes", free);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> tablesOf(Map<String, Object> database) {
    return (List<Map<String, Object>>) database.get("tables");
  }

  private Map<String, Object> findTable(Map<String, Map<String, Object>> databases, String schema,
                                        String name) {
    for (Map<String, Object> table : tablesOf(databases.get(schema))) {
      if (name.equals(table.get("name"))) {
        return table;
      }
    }
    throw new IllegalStateException("No table " + schema + "." + name + " was read for this report");
  }

  /**
   * Quote an identifier the way MySQL does, doubling any backtick inside it. These names come from
   * {@code information_schema} rather than from the caller, but they are still interpolated into a
   * statement, and a name is not a place to rely on where it came from.
   */
  private String quote(String identifier) {
    return "`" + identifier.replace("`", "``") + "`";
  }

  private Long nullableLong(ResultSet rows, String column) throws SQLException {
    long value = rows.getLong(column);
    return rows.wasNull() ? null : value;
  }

  private long zeroIfNull(ResultSet rows, String column) throws SQLException {
    long value = rows.getLong(column);
    return rows.wasNull() ? 0L : value;
  }

  private String isoOrNull(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant().toString();
  }

  /**
   * The {@code host:port} a JDBC URL points at, for a card that says which server it read. The URL
   * holds no password, so there is nothing in it to redact.
   */
  private String serverOf(String url) {
    int start = url.indexOf("//");
    if (start < 0) {
      return null;
    }
    String rest = url.substring(start + 2);
    int end = rest.indexOf('/');
    return end < 0 ? rest : rest.substring(0, end);
  }

  private String schemaOf(String url) {
    int start = url.indexOf("//");
    if (start < 0) {
      return null;
    }
    String rest = url.substring(start + 2);
    int slash = rest.indexOf('/');
    if (slash < 0) {
      return null;
    }
    String schema = rest.substring(slash + 1);
    int query = schema.indexOf('?');
    return query < 0 ? schema : schema.substring(0, query);
  }
}
