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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.logging.dao.query.LogQueryDAO;
import org.metadatacenter.server.logging.query.LogBoards;
import org.metadatacenter.server.logging.query.LogBoards.Board;
import org.metadatacenter.server.logging.query.LogQueryColumns;
import org.metadatacenter.server.logging.query.LogQueryResults.CoverageResult;
import org.metadatacenter.server.logging.query.LogQueryResults.FacetResult;
import org.metadatacenter.server.logging.query.LogQueryResults.QueryResult;
import org.metadatacenter.server.logging.query.LogQueryResults.TraceResult;
import org.metadatacenter.server.logging.query.LogQuerySpec;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarError;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Structured query engine over the log tables — the one backend the Explorer, the pivot view and every
 * Insight board are built on (see {@code cedar-development/ops/LOG-EXPLORER-UI-PLAN.md}).
 * <p>
 * Complements the two fixed-shape resources: {@link LogUsageResource} (rollups) and
 * {@link LogExplorerResource} (raw rows, fixed columns). This one takes a validated spec — filters,
 * groupBy, metrics, sort, keyset cursor — so new questions do not need new endpoints. No SQL crosses
 * the wire: column names are resolved against {@link LogQueryColumns} and values are bound.
 * MONITOR_READ-gated, {@code @UnitOfWork}.
 */
@Path("/logs")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Log query")
@SecurityRequirement(name = "api_key")
public class LogQueryResource extends AbstractMonitorResource {

  private static final Duration DEFAULT_FACET_SPAN = Duration.ofHours(24);
  /** A pathological request can issue hundreds of queries — enough to see the shape, capped to stay cheap. */
  private static final int DEFAULT_MAX_SPANS = 500;
  private static final int MAX_SPANS = 2000;

  private final LogQueryDAO dao;

  public LogQueryResource(CedarConfig cedarConfig, LogQueryDAO dao) {
    super(cedarConfig);
    this.dao = dao;
  }

  /**
   * POST rather than GET because a spec with several filters plus a metric list exceeds a sane URL
   * length; the shareable/bookmarkable state lives in the Angular route, not in this URL.
   */
  @POST
  @Timed
  @Path("/query")
  @Consumes(MediaType.APPLICATION_JSON)
  @UnitOfWork
  @Operation(summary = "Run a structured query over the log tables",
      description = "The one query surface the Explorer, the pivot view and every Insight board are built "
          + "on. The body is a structured spec rather than SQL text: column names are resolved against an "
          + "allowlist and values are bound, so a spec naming a column the engine does not know is rejected "
          + "rather than run. An empty `groupBy` returns raw rows newest first, paged by the cursor the "
          + "previous result handed back; a non-empty one returns an aggregate. The result carries its own "
          + "provenance, so a caller can tell an exact answer from a histogram-approximate one and a "
          + "complete result from a capped one. It is POST rather than GET because a spec with several "
          + "filters and a metric list outgrows a URL. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The columns, the rows and the result's provenance",
          content = @Content(schema = @Schema(implementation = QueryResult.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(ref = "#/components/schemas/LogQueryError")),
          description = "The spec named an unknown table, column, metric or operator, or its cursor could not be read"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response query(
      @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
          description = "The query to run. Every field is optional and is normalized before use: `table` "
              + "defaults to the request log, `source` selects the raw log tables or the hourly rollups, and "
              + "`limit` is capped by the engine.",
          content = @Content(schema = @Schema(implementation = LogQuerySpec.class)))
      LogQuerySpec spec) throws CedarException {
    authorize(buildRequestContext());
    try {
      return Response.ok().entity(dao.query(spec)).build();
    } catch (IllegalArgumentException e) {
      return badRequest(e);
    }
  }

  /** Distinct values + counts for one dimension, for the filter dropdowns. */
  @GET
  @Timed
  @Path("/facets/{column}")
  @UnitOfWork
  @Operation(summary = "List the distinct values of one column, with their counts",
      description = "What a filter dropdown needs: every value the column took over the range, most frequent "
          + "first, with how often each occurred. Only columns the engine marks facetable can be asked for. "
          + "The result says whether the value list was capped. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The column's distinct values and their counts",
          content = @Content(schema = @Schema(implementation = FacetResult.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(ref = "#/components/schemas/LogQueryError")),
          description = "The table or column is not known or not facetable, a bound was not an ISO-8601 instant, or `from` was not before `to`"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response facet(
      @Parameter(description = "The column to enumerate, named as the coverage route names it.", required = true)
      @PathParam("column") String column,
      @Parameter(description = "Which log table to read. Defaults to the request log.")
      @QueryParam("table") String table,
      @Parameter(description = "Inclusive lower bound, as an ISO-8601 instant in UTC. Defaults to 24 hours "
          + "before the upper bound.")
      @QueryParam("from") String from,
      @Parameter(description = "Exclusive upper bound, as an ISO-8601 instant in UTC. Defaults to now.")
      @QueryParam("to") String to) throws CedarException {
    authorize(buildRequestContext());
    try {
      Instant toI = to == null ? Instant.now() : Instant.parse(to);
      Instant fromI = from == null ? toI.minus(DEFAULT_FACET_SPAN) : Instant.parse(from);
      if (!fromI.isBefore(toI)) {
        throw new IllegalArgumentException("'from' must be before 'to'.");
      }
      String tableKey = table == null ? LogQueryColumns.T_REQUEST : table;
      return Response.ok().entity(dao.facet(tableKey, column, fromI, toI)).build();
    } catch (DateTimeParseException e) {
      return badRequest(new IllegalArgumentException(
          "from/to must be ISO-8601 instants (e.g. 2026-07-31T12:00:00Z): " + e.getParsedString()));
    } catch (IllegalArgumentException e) {
      return badRequest(e);
    }
  }

  /**
   * What is queryable and what is actually present — the queryable column surface per table plus row
   * counts and the real time span. The UI reads this to state its own caveats (status and apiKeyHash
   * only exist for recent rows) rather than showing columns that look broken.
   */
  @GET
  @Timed
  @Path("/coverage")
  @UnitOfWork
  @Operation(summary = "Describe what is queryable and what is actually present",
      description = "Per log table: the columns a query may name, which of them can be grouped and which "
          + "aggregated, and the rows and time window the table actually holds. Some columns were added "
          + "after logging began and so carry values only for recent rows; the notes on a column say so. "
          + "A client reads this to state its own caveats rather than showing a column that looks broken. "
          + "Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The queryable surface and the data behind it",
          content = @Content(schema = @Schema(implementation = CoverageResult.class))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response coverage() throws CedarException {
    authorize(buildRequestContext());
    return Response.ok().entity(dao.coverage()).build();
  }

  /**
   * One globalRequestId resolved into a distributed trace — every component that handled the request
   * plus every Cypher query underneath it, on a shared timeline with a DB-time share.
   * <p>
   * This is the one question the generic engine cannot express: it spans both tables and computes
   * cross-table totals. globalRequestId is deliberately non-unique in log_request (a browser request
   * fans out across microservices), and that fan-out is the point of the view.
   */
  @GET
  @Timed
  @Path("/trace/{globalRequestId}")
  @UnitOfWork
  @Operation(summary = "Resolve one global request identifier into a distributed trace",
      description = "One browser request fans out across microservices, so the identifier appears on several "
          + "rows, and that fan-out is what this route shows. It returns one span per component that handled "
          + "the request and one per Cypher statement underneath it, on a shared timeline, with the summed "
          + "handler time, the summed database time and the share the second is of the first. A handler that "
          + "is slow with a low share is slow for reasons other than the database. Spans overlap, so the "
          + "summed times are not wall time; `spanMs` is. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The spans of the request and their cross-table totals",
          content = @Content(schema = @Schema(implementation = TraceResult.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(ref = "#/components/schemas/LogQueryError")),
          description = "The identifier was blank"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response trace(
      @Parameter(description = "The global request identifier to resolve. An identifier no log row carries "
          + "answers 200 with no spans.", required = true)
      @PathParam("globalRequestId") String globalRequestId,
      @Parameter(description = "How many spans to return per log table. Defaults to 500 and is capped at "
          + "2000. Passing the cap is how a pathological request stays cheap to look at.")
      @QueryParam("maxSpans") Integer maxSpans) throws CedarException {
    authorize(buildRequestContext());
    try {
      int cap = (maxSpans == null || maxSpans <= 0) ? DEFAULT_MAX_SPANS : Math.min(maxSpans, MAX_SPANS);
      return Response.ok().entity(dao.trace(globalRequestId, cap)).build();
    } catch (IllegalArgumentException e) {
      return badRequest(e);
    }
  }

  /**
   * Per handler: total handler time vs the Cypher time underneath it. Returns the same
   * {@code QueryResult} shape as {@code /logs/query}, so the page renders it with the same table —
   * it needs its own endpoint only because it joins the two log tables, which the spec deliberately
   * does not express.
   */
  @GET
  @Timed
  @Path("/db-share")
  @UnitOfWork
  @Operation(summary = "Compare each handler's total time against the database time underneath it",
      description = "One row per handler and component over the range, slowest in total first, with the "
          + "request count, the summed handler time, the summed Cypher time and the share the second is of "
          + "the first. The answer comes back in the same shape as a structured query, so a client renders "
          + "it with the same table. It needs a route of its own only because it joins the request log to "
          + "the Cypher log, which a spec cannot express. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "One row per handler, slowest in total first",
          content = @Content(schema = @Schema(implementation = QueryResult.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(ref = "#/components/schemas/LogQueryError")),
          description = "A bound was not an ISO-8601 instant, or `from` was not before `to`"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response dbShare(
      @Parameter(description = "Inclusive lower bound, as an ISO-8601 instant in UTC. Defaults to seven days "
          + "before the upper bound.")
      @QueryParam("from") String from,
      @Parameter(description = "Exclusive upper bound, as an ISO-8601 instant in UTC. Defaults to now.")
      @QueryParam("to") String to,
      @Parameter(description = "How many rows to return. Defaults to 100 and is capped at 500.")
      @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    try {
      Instant toI = to == null ? Instant.now() : Instant.parse(to);
      Instant fromI = from == null ? toI.minus(Duration.ofDays(7)) : Instant.parse(from);
      if (!fromI.isBefore(toI)) {
        throw new IllegalArgumentException("'from' must be before 'to'.");
      }
      int cap = (limit == null || limit <= 0) ? 100 : Math.min(limit, 500);
      return Response.ok().entity(dao.dbTimeShare(fromI, toI, cap)).build();
    } catch (DateTimeParseException e) {
      return badRequest(new IllegalArgumentException(
          "from/to must be ISO-8601 instants: " + e.getParsedString()));
    } catch (IllegalArgumentException e) {
      return badRequest(e);
    }
  }

  /**
   * The Insight board catalog: the pre-defined questions, each one a saved query spec. Served from
   * the backend so the UI's list cannot drift from what the engine supports — the page loads a
   * board's spec into the same controls, which is what keeps a board editable rather than a dead end.
   * Specs carry no from/to; the page supplies the range.
   */
  @GET
  @Timed
  @Path("/boards")
  @Operation(summary = "List the predefined Insight boards",
      description = "The catalogue of predefined questions, each one a saved query spec a client can load "
          + "into the same controls and edit. The catalogue is served from here so a client's list cannot "
          + "drift from what the query engine supports. Specs carry no time range: the caller supplies one, "
          + "and `defaultRangeMinutes` is only a suggested starting window. A few boards join the two log "
          + "tables and so cannot be a spec; those name a route in `endpoint`, which the caller reads "
          + "instead of posting `spec`. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The board catalogue",
          content = @Content(array = @ArraySchema(schema = @Schema(implementation = Board.class)))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission")
  })
  public Response boards() throws CedarException {
    authorize(buildRequestContext());
    return Response.ok().entity(LogBoards.all()).build();
  }

  /**
   * Authorize, and record the request against the CALLING endpoint.
   *
   * {@code CedarMicroserviceResource.buildRequestContext()} attributes the log row to
   * {@code Thread.currentThread().getStackTrace()[2]} — its immediate caller. Calling it from a
   * shared private helper therefore logs every endpoint of this class as that helper (they all showed
   * up as "LogQueryResource.authorize"), which destroys per-endpoint resolution in exactly the boards
   * that group by handler. So the context is built in the endpoint method and passed in here.
   * <p>
   * The sibling resources (LogUsageResource, LogExplorerResource) still have the helper pattern and
   * so still mis-attribute — noted as a follow-up; fixing it centrally means touching the shared
   * capture in cedar-server-utils-dropwizard-library and rebuilding every service.
   */
  private void authorize(CedarRequestContext c) throws CedarException {
    c.must(c.user()).have(CedarPermission.MONITOR_READ);
  }

  /** Spec validation failures are the caller's fault and the message names the offending field. */
  private static Response badRequest(IllegalArgumentException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(Map.of("error", e.getMessage() == null ? "Invalid query spec." : e.getMessage()))
        .build();
  }
}
