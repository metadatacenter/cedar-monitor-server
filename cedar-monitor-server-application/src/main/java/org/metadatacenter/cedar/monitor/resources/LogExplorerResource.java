package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.cedar.monitor.paging.LogPages.CypherRowPage;
import org.metadatacenter.cedar.monitor.paging.LogPages.RequestRowPage;
import org.metadatacenter.server.logging.dao.agg.LogExplorerDAO;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.util.http.PagedQuery;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

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
  private static final int MAX_LIMIT = 500;
  /**
   * The deepest offset the raw logs serve. An offset is a scan past every row before it, and the raw
   * tables hold tens of millions of rows.
   */
  static final int RAW_MAX_OFFSET = 10_000;
  /** Counting a raw log stops here: far enough that every reachable page has a correct next link. */
  static final long RAW_COUNT_CAP = RAW_MAX_OFFSET + MAX_LIMIT;
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
      @ApiResponse(responseCode = "200", description = "A page of matching request rows, newest first. Counting stops at 10,500 matches, "
          + "and countCapped then reports that totalCount is a lower bound",
          content = @Content(schema = @Schema(implementation = RequestRowPage.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The limit or offset is out of range"),
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
      @Parameter(description = "How many rows to return, from 1 to 500. Defaults to 100.")
      @QueryParam("limit") Optional<Integer> limit,
      @Parameter(description = "How many matching rows to skip, from 0 to 10000. Defaults to 0.")
      @QueryParam("offset") Optional<Integer> offset) throws CedarException {
    authorize(buildRequestContext());
    PagedQuery page = rawPage(limit, offset);
    long minNanos = nanos(minDurationMs);
    long total = dao.countRequests(q, minNanos, RAW_COUNT_CAP);
    return Response.ok().entity(new RequestRowPage(
        dao.recentRequests(q, minNanos, page.getLimit(), page.getOffset()),
        requestUrl(), total, page.getLimit(), page.getOffset(), total >= RAW_COUNT_CAP)).build();
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
      @ApiResponse(responseCode = "200", description = "A page of matching Cypher rows, newest first. Counting stops at 10,500 matches, "
          + "and countCapped then reports that totalCount is a lower bound",
          content = @Content(schema = @Schema(implementation = CypherRowPage.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The limit or offset is out of range"),
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
      @Parameter(description = "How many rows to return, from 1 to 500. Defaults to 100.")
      @QueryParam("limit") Optional<Integer> limit,
      @Parameter(description = "How many matching rows to skip, from 0 to 10000. Defaults to 0.")
      @QueryParam("offset") Optional<Integer> offset) throws CedarException {
    authorize(buildRequestContext());
    PagedQuery page = rawPage(limit, offset);
    long minNanos = nanos(minDurationMs);
    long total = dao.countCypher(q, minNanos, RAW_COUNT_CAP);
    return Response.ok().entity(new CypherRowPage(
        dao.recentCypher(q, minNanos, page.getLimit(), page.getOffset()),
        requestUrl(), total, page.getLimit(), page.getOffset(), total >= RAW_COUNT_CAP)).build();
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
      @ApiResponse(responseCode = "200", description = "A page of retained outlier request rows, slowest first",
          content = @Content(schema = @Schema(implementation = RequestRowPage.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The limit or offset is out of range"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response requestOutliers(
      @Parameter(description = "Which kind of outlier to return, `SLOW` or `ERROR`. Matched case-insensitively. "
          + "Omit to return both.", schema = @Schema(allowableValues = {"SLOW", "ERROR"}))
      @QueryParam("kind") String kind,
      @Parameter(description = "How many rows to return, from 1 to 500. Defaults to 100.")
      @QueryParam("limit") Optional<Integer> limit,
      @Parameter(description = "How many rows to skip. Defaults to 0.")
      @QueryParam("offset") Optional<Integer> offset) throws CedarException {
    authorize(buildRequestContext());
    PagedQuery page = page(limit, offset);
    return Response.ok().entity(new RequestRowPage(
        dao.requestOutliers(kind, page.getLimit(), page.getOffset()),
        requestUrl(), dao.countRequestOutliers(kind), page.getLimit(), page.getOffset(), false)).build();
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
      @ApiResponse(responseCode = "200", description = "A page of retained outlier Cypher rows, slowest first",
          content = @Content(schema = @Schema(implementation = CypherRowPage.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The limit or offset is out of range"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The log database could not be read")
  })
  public Response cypherOutliers(
      @Parameter(description = "How many rows to return, from 1 to 500. Defaults to 100.")
      @QueryParam("limit") Optional<Integer> limit,
      @Parameter(description = "How many rows to skip. Defaults to 0.")
      @QueryParam("offset") Optional<Integer> offset) throws CedarException {
    authorize(buildRequestContext());
    PagedQuery page = page(limit, offset);
    return Response.ok().entity(new CypherRowPage(
        dao.cypherOutliers(page.getLimit(), page.getOffset()),
        requestUrl(), dao.countCypherOutliers(), page.getLimit(), page.getOffset(), false)).build();
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

  private static PagedQuery page(Optional<Integer> limit, Optional<Integer> offset) throws CedarException {
    PagedQuery page = new PagedQuery(DEFAULT_LIMIT, MAX_LIMIT).limit(limit).offset(offset);
    page.validate();
    return page;
  }

  private static PagedQuery rawPage(Optional<Integer> limit, Optional<Integer> offset) throws CedarException {
    PagedQuery page = new PagedQuery(DEFAULT_LIMIT, MAX_LIMIT).limit(limit).offset(offset)
        .maxOffset(RAW_MAX_OFFSET);
    page.validate();
    return page;
  }

  private String requestUrl() {
    return uriInfo.getRequestUri().toString();
  }
}
