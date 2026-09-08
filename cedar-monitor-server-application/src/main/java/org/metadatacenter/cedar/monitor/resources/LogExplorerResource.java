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
import org.metadatacenter.server.logging.agg.LogExplorerResults.CypherRow;
import org.metadatacenter.server.logging.agg.LogExplorerResults.RequestRow;
import org.metadatacenter.server.logging.dao.agg.LogExplorerDAO;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarError;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Live Log Explorer — row-level forensic queries over the RAW log tables (last ≤30 days, before the
 * prune), the complement to the aggregated {@link LogUsageResource}. MONITOR_READ-gated, {@code @UnitOfWork}.
 * Unlike the rollups, this reflects actual recent traffic in real time.
 */
@Path("/logs/explorer")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Log explorer")
@SecurityRequirement(name = "api_key")
public class LogExplorerResource extends AbstractMonitorResource {

  private static final int DEFAULT_LIMIT = 100;
  private final LogExplorerDAO dao;

  public LogExplorerResource(CedarConfig cedarConfig, LogExplorerDAO dao) {
    super(cedarConfig);
    this.dao = dao;
  }

  @GET
  @Timed
  @Path("/requests")
  @UnitOfWork
  @Operation(summary = "List recent HTTP requests, newest first",
      description = "One row per request the raw request log still holds, which covers roughly the last "
          + "thirty days. Each row carries the caller, the handler that served it, the status and the "
          + "handler duration in nanoseconds. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Matching request rows, newest first",
          content = @Content(array = @ArraySchema(schema = @Schema(implementation = RequestRow.class)))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response requests(
      @Parameter(description = "Free-text filter, matched as a substring against the path, the user "
          + "identifier, the handler class and the global request identifier. Omit to match every row.")
      @QueryParam("q") String q,
      @Parameter(description = "Keep only requests whose handler took at least this many milliseconds. "
          + "Zero and negative values are treated as no filter.")
      @QueryParam("minDurationMs") Long minDurationMs,
      @Parameter(description = "How many rows to return. Defaults to 100 and is capped at 500.")
      @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    return Response.ok().entity(dao.recentRequests(q, nanos(minDurationMs), lim(limit))).build();
  }

  @GET
  @Timed
  @Path("/cypher")
  @UnitOfWork
  @Operation(summary = "List recently executed Cypher statements, newest first",
      description = "One row per Neo4j statement the raw Cypher log still holds, which covers roughly the "
          + "last thirty days. This route reports statements CEDAR already ran, with their text, their "
          + "bound parameters and how long each took. It does not accept or run a query of its own. "
          + "Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Matching Cypher rows, newest first",
          content = @Content(array = @ArraySchema(schema = @Schema(implementation = CypherRow.class)))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response cypher(
      @Parameter(description = "Free-text filter, matched as a substring against the operation name, the "
          + "statement hash, the handler class and the statement text. Omit to match every row.")
      @QueryParam("q") String q,
      @Parameter(description = "Keep only statements that took at least this many milliseconds. Zero and "
          + "negative values are treated as no filter.")
      @QueryParam("minDurationMs") Long minDurationMs,
      @Parameter(description = "How many rows to return. Defaults to 100 and is capped at 500.")
      @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    return Response.ok().entity(dao.recentCypher(q, nanos(minDurationMs), lim(limit))).build();
  }

  /** Retained slowest/error request instances (survive the prune). kind = SLOW | ERROR (optional). */
  @GET
  @Timed
  @Path("/outliers/requests")
  @UnitOfWork
  @Operation(summary = "List the retained slowest and failed requests, slowest first",
      description = "The request instances the aggregator keeps past the prune, so a slow call from months "
          + "ago is still readable. The rows carry no global request identifier, because the raw row they "
          + "were copied from is gone. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Retained outlier request rows, slowest first",
          content = @Content(array = @ArraySchema(schema = @Schema(implementation = RequestRow.class)))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response requestOutliers(
      @Parameter(description = "Which kind of outlier to return, `SLOW` or `ERROR`. Matched case-insensitively. "
          + "Omit to return both.", schema = @Schema(allowableValues = {"SLOW", "ERROR"}))
      @QueryParam("kind") String kind,
      @Parameter(description = "How many rows to return. Defaults to 100 and is capped at 500.")
      @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    return Response.ok().entity(dao.requestOutliers(kind, lim(limit))).build();
  }

  /** Retained slowest Cypher instances with full text + params (survive the prune). */
  @GET
  @Timed
  @Path("/outliers/cypher")
  @UnitOfWork
  @Operation(summary = "List the retained slowest Cypher statements, slowest first",
      description = "The Cypher instances the aggregator keeps past the prune, each with its full statement "
          + "text and bound parameters. This route reports statements CEDAR already ran; it does not accept "
          + "or run a query of its own. Requires the monitor read permission.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Retained outlier Cypher rows, slowest first",
          content = @Content(array = @ArraySchema(schema = @Schema(implementation = CypherRow.class)))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response cypherOutliers(
      @Parameter(description = "How many rows to return. Defaults to 100 and is capped at 500.")
      @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    return Response.ok().entity(dao.cypherOutliers(lim(limit))).build();
  }

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

  private static long nanos(Long ms) {
    return (ms == null || ms <= 0) ? 0L : ms * 1_000_000L;
  }

  private static int lim(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, 500);
  }
}
