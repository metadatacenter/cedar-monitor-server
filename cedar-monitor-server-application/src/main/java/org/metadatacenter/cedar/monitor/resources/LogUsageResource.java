package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.logging.agg.AggQueryResults.CypherStat;
import org.metadatacenter.server.logging.agg.AggQueryResults.EndpointStat;
import org.metadatacenter.server.logging.agg.AggQueryResults.Insights;
import org.metadatacenter.server.logging.agg.AggQueryResults.UserStat;
import org.metadatacenter.server.logging.dao.agg.AggregationQueryDAO;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarError;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of the log aggregation: date-range usage + pattern detection over the {@code agg_*} rollups
 * (the "Usage &amp; Patterns" internals page). All endpoints are {@code MONITOR_READ}-gated and
 * {@code @UnitOfWork} (a log-DB session for the DAO). A range is any [from,to) in UTC — the caller
 * (timezone selector in the UI) converts a local day/week to UTC bounds.
 */
@Path("/logs")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Log usage")
@SecurityRequirement(name = "api_key")
public class LogUsageResource extends AbstractMonitorResource {

  private static final int DEFAULT_LIMIT = 50;
  private final AggregationQueryDAO dao;

  public LogUsageResource(CedarConfig cedarConfig, AggregationQueryDAO dao) {
    super(cedarConfig);
    this.dao = dao;
  }

  @GET
  @Timed
  @Path("/usage/summary")
  @UnitOfWork
  @Operation(summary = "Get request volume and latency totals for a time range",
      description = "The totals over the range beside the hourly series they were summed from. Both come "
          + "from the hourly rollups, so the newest hour appears only once the aggregator has closed it. "
          + "Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Totals and the hourly series behind them",
          content = @Content(schema = @Schema(ref = "#/components/schemas/LogUsageSummary"))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read, or a bound was not an ISO-8601 instant")
  })
  public Response summary(
      @Parameter(description = "Inclusive lower bound, as an ISO-8601 instant in UTC. Defaults to seven days "
          + "before the upper bound.")
      @QueryParam("from") String from,
      @Parameter(description = "Exclusive upper bound, as an ISO-8601 instant in UTC. Defaults to now.")
      @QueryParam("to") String to) throws CedarException {
    authorize(buildRequestContext());
    Instant[] range = range(from, to);
    Map<String, Object> r = new HashMap<>();
    r.put("from", range[0].toString());
    r.put("to", range[1].toString());
    r.put("totals", dao.totals(range[0], range[1]));
    r.put("series", dao.volumeSeries(range[0], range[1]));
    return Response.ok().entity(r).build();
  }

  @GET
  @Timed
  @Path("/usage/endpoints")
  @UnitOfWork
  @Operation(summary = "Break a time range down by endpoint",
      description = "One row per component, handler class, handler method and HTTP method seen in the range, "
          + "busiest first. The percentiles come from the merged latency histogram of the covered hours; "
          + "`maxNanos` is the largest single handler duration any of them recorded. Requires the monitor "
          + "read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "One row per endpoint, busiest first",
          content = @Content(array = @ArraySchema(schema = @Schema(implementation = EndpointStat.class)))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read, or a bound was not an ISO-8601 instant")
  })
  public Response endpoints(
      @Parameter(description = "Inclusive lower bound, as an ISO-8601 instant in UTC. Defaults to seven days "
          + "before the upper bound.")
      @QueryParam("from") String from,
      @Parameter(description = "Exclusive upper bound, as an ISO-8601 instant in UTC. Defaults to now.")
      @QueryParam("to") String to,
      @Parameter(description = "How many rows to return. Defaults to 50 and is capped at 500.")
      @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    Instant[] range = range(from, to);
    return Response.ok().entity(dao.endpointBreakdown(range[0], range[1], lim(limit))).build();
  }

  @GET
  @Timed
  @Path("/usage/cypher")
  @UnitOfWork
  @Operation(summary = "Break a time range down by Cypher statement",
      description = "One row per distinct Neo4j statement executed in the range, identified by its operation "
          + "name and the hash of its text, most executed first. Each row carries how often the statement "
          + "ran and how long it took, plus a sample of its text from the query catalogue, truncated to two "
          + "thousand characters. This route reports statements CEDAR already ran. It does not accept or run "
          + "a query of its own. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "One row per distinct statement, most executed first",
          content = @Content(array = @ArraySchema(schema = @Schema(implementation = CypherStat.class)))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read, or a bound was not an ISO-8601 instant")
  })
  public Response cypher(
      @Parameter(description = "Inclusive lower bound, as an ISO-8601 instant in UTC. Defaults to seven days "
          + "before the upper bound.")
      @QueryParam("from") String from,
      @Parameter(description = "Exclusive upper bound, as an ISO-8601 instant in UTC. Defaults to now.")
      @QueryParam("to") String to,
      @Parameter(description = "How many rows to return. Defaults to 50 and is capped at 500.")
      @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    Instant[] range = range(from, to);
    return Response.ok().entity(dao.cypherBreakdown(range[0], range[1], lim(limit))).build();
  }

  @GET
  @Timed
  @Path("/usage/users")
  @UnitOfWork
  @Operation(summary = "Break a time range down by caller",
      description = "One row per user identifier, authentication source and API key hash seen in the range, "
          + "busiest first. A caller who used two keys appears once per key. Requires the monitor read "
          + "permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "One row per caller, busiest first",
          content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserStat.class)))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read, or a bound was not an ISO-8601 instant")
  })
  public Response users(
      @Parameter(description = "Inclusive lower bound, as an ISO-8601 instant in UTC. Defaults to seven days "
          + "before the upper bound.")
      @QueryParam("from") String from,
      @Parameter(description = "Exclusive upper bound, as an ISO-8601 instant in UTC. Defaults to now.")
      @QueryParam("to") String to,
      @Parameter(description = "How many rows to return. Defaults to 50 and is capped at 500.")
      @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    Instant[] range = range(from, to);
    return Response.ok().entity(dao.userBreakdown(range[0], range[1], lim(limit))).build();
  }

  /** Pattern detection: computed in Java from the breakdowns (they are tiny). */
  @GET
  @Timed
  @Path("/usage/insights")
  @UnitOfWork
  @Operation(summary = "Get the notable patterns in a time range",
      description = "Four short lists drawn from the same breakdowns the other usage routes serve, each cut "
          + "to five entries. Slow Cypher ranks the 95th percentile weighted by how often the statement ran, "
          + "so a slow statement nobody calls does not crowd out a common one. Slow endpoints ranks the 95th "
          + "percentile alone. Heaviest users are the busiest callers. Error hotspots rank the error rate "
          + "among endpoints with at least twenty requests, below which a rate says little. Requires the "
          + "monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The four pattern lists for the range",
          content = @Content(schema = @Schema(implementation = Insights.class))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read, or a bound was not an ISO-8601 instant")
  })
  public Response insights(
      @Parameter(description = "Inclusive lower bound, as an ISO-8601 instant in UTC. Defaults to seven days "
          + "before the upper bound.")
      @QueryParam("from") String from,
      @Parameter(description = "Exclusive upper bound, as an ISO-8601 instant in UTC. Defaults to now.")
      @QueryParam("to") String to) throws CedarException {
    authorize(buildRequestContext());
    Instant[] range = range(from, to);
    List<CypherStat> cy = dao.cypherBreakdown(range[0], range[1], 100);
    List<EndpointStat> ep = dao.endpointBreakdown(range[0], range[1], 200);
    List<UserStat> users = dao.userBreakdown(range[0], range[1], 20);

    List<CypherStat> slowCypher = cy.stream()
        .sorted(Comparator.comparingDouble(
            (CypherStat c) -> c.p95Nanos() * Math.log(Math.max(2, c.execCount()))).reversed())
        .limit(5).toList();
    List<EndpointStat> slowEndpoints = ep.stream()
        .sorted(Comparator.comparingLong(EndpointStat::p95Nanos).reversed())
        .limit(5).toList();
    List<UserStat> heaviest = users.stream().limit(5).toList();
    List<EndpointStat> errorHotspots = ep.stream()
        .filter(e -> e.reqCount() >= 20)
        .sorted(Comparator.comparingDouble((EndpointStat e) -> (double) e.errorCount() / e.reqCount()).reversed())
        .limit(5).toList();

    return Response.ok().entity(new Insights(slowCypher, slowEndpoints, heaviest, errorHotspots)).build();
  }

  // ---- helpers -----------------------------------------------------------------------------------

  /**
   * Authorize, and record the request against the CALLING endpoint.
   *
   * {@code CedarMicroserviceResource.buildRequestContext()} attributes the log row to
   * {@code Thread.currentThread().getStackTrace()[2]} — its immediate caller — so building the context
   * inside a shared private helper logged every endpoint of this class under that helper's name.
   * Every board that groups by handler lost per-endpoint resolution as a result. The context is now
   * built in the endpoint method and passed in.
   */
  private void authorize(CedarRequestContext c) throws CedarException {
    c.must(c.user()).have(CedarPermission.MONITOR_READ);
  }

  private static int lim(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, 500);
  }

  /** Parse from/to ISO-8601 instants; default to the last 7 days. */
  private static Instant[] range(String from, String to) {
    Instant toI = to == null || to.isBlank() ? Instant.now() : Instant.parse(to);
    Instant fromI = from == null || from.isBlank() ? toI.minus(7, ChronoUnit.DAYS) : Instant.parse(from);
    return new Instant[]{fromI, toI};
  }
}
