package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.hibernate.UnitOfWork;
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
import org.metadatacenter.server.logging.query.LogQueryColumns;
import org.metadatacenter.server.logging.query.LogQuerySpec;
import org.metadatacenter.server.security.model.auth.CedarPermission;

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
public class LogQueryResource extends AbstractMonitorResource {

  private static final Duration DEFAULT_FACET_SPAN = Duration.ofHours(24);

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
  public Response query(LogQuerySpec spec) throws CedarException {
    authorize();
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
  public Response facet(@PathParam("column") String column,
                        @QueryParam("table") String table,
                        @QueryParam("from") String from,
                        @QueryParam("to") String to) throws CedarException {
    authorize();
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
  public Response coverage() throws CedarException {
    authorize();
    return Response.ok().entity(dao.coverage()).build();
  }

  private void authorize() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).have(CedarPermission.MONITOR_READ);
  }

  /** Spec validation failures are the caller's fault and the message names the offending field. */
  private static Response badRequest(IllegalArgumentException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(Map.of("error", e.getMessage() == null ? "Invalid query spec." : e.getMessage()))
        .build();
  }
}
