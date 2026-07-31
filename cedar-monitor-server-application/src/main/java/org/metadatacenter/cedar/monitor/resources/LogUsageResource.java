package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.hibernate.UnitOfWork;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.logging.agg.AggQueryResults.CypherStat;
import org.metadatacenter.server.logging.agg.AggQueryResults.EndpointStat;
import org.metadatacenter.server.logging.agg.AggQueryResults.Insights;
import org.metadatacenter.server.logging.agg.AggQueryResults.UserStat;
import org.metadatacenter.server.logging.dao.agg.AggregationQueryDAO;
import org.metadatacenter.server.security.model.auth.CedarPermission;

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
  public Response summary(@QueryParam("from") String from, @QueryParam("to") String to) throws CedarException {
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
  public Response endpoints(@QueryParam("from") String from, @QueryParam("to") String to,
                            @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    Instant[] range = range(from, to);
    return Response.ok().entity(dao.endpointBreakdown(range[0], range[1], lim(limit))).build();
  }

  @GET
  @Timed
  @Path("/usage/cypher")
  @UnitOfWork
  public Response cypher(@QueryParam("from") String from, @QueryParam("to") String to,
                         @QueryParam("limit") Integer limit) throws CedarException {
    authorize(buildRequestContext());
    Instant[] range = range(from, to);
    return Response.ok().entity(dao.cypherBreakdown(range[0], range[1], lim(limit))).build();
  }

  @GET
  @Timed
  @Path("/usage/users")
  @UnitOfWork
  public Response users(@QueryParam("from") String from, @QueryParam("to") String to,
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
  public Response insights(@QueryParam("from") String from, @QueryParam("to") String to) throws CedarException {
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
