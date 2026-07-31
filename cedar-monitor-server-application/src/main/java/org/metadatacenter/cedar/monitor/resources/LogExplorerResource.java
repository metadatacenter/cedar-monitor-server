package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.hibernate.UnitOfWork;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.logging.dao.agg.LogExplorerDAO;
import org.metadatacenter.server.security.model.auth.CedarPermission;

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
  public Response requests(@QueryParam("q") String q,
                           @QueryParam("minDurationMs") Long minDurationMs,
                           @QueryParam("limit") Integer limit) throws CedarException {
    authorize();
    return Response.ok().entity(dao.recentRequests(q, nanos(minDurationMs), lim(limit))).build();
  }

  @GET
  @Timed
  @Path("/cypher")
  @UnitOfWork
  public Response cypher(@QueryParam("q") String q,
                         @QueryParam("minDurationMs") Long minDurationMs,
                         @QueryParam("limit") Integer limit) throws CedarException {
    authorize();
    return Response.ok().entity(dao.recentCypher(q, nanos(minDurationMs), lim(limit))).build();
  }

  private void authorize() throws CedarException {
    CedarRequestContext c = buildRequestContext();
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
