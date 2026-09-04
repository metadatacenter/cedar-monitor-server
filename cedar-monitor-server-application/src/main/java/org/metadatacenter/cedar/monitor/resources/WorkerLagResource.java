package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.config.CacheServerPersistent;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.logging.dao.query.LogQueryDAO;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.server.queue.util.QueueService.APP_LOG_QUEUE_ID;

/**
 * Whether the log pipeline is keeping up.
 *
 * <p>The queue-counts page already shows how deep each Redis queue is, and a depth on its own does
 * not say much: a queue with ten thousand items in it is failing if nothing is draining it and fine
 * if something is draining twelve thousand a minute. What settles it is the other end — the
 * timestamp of the newest row the worker actually wrote. Depth and freshness together separate a
 * busy pipeline from a stopped one, and this route reports both with the verdict they imply.
 *
 * <p>This matters more here than the shape of the numbers suggests. Application logs are enqueued
 * best-effort and drained asynchronously, so the worker can be down for a day with every health
 * check green and every user request served normally; the only visible symptom is that the log
 * pages quietly stop moving. That is the failure this page exists to make loud.
 */
@Path("/worker")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Worker")
@SecurityRequirement(name = "api_key")
public class WorkerLagResource extends AbstractMonitorResource {

  /**
   * Above this, the pipeline is behind. The worker drains continuously, so on a healthy system the
   * newest row is seconds old; five minutes is slack for a quiet period and a restart, not for a
   * backlog.
   */
  private static final Duration LAGGING_AFTER = Duration.ofMinutes(5);

  /** Above this, nothing is draining. An hour is longer than any restart or migration window. */
  private static final Duration STALLED_AFTER = Duration.ofHours(1);

  private final LogQueryDAO dao;

  public WorkerLagResource(CedarConfig cedarConfig, LogQueryDAO dao) {
    super(cedarConfig);
    this.dao = dao;
  }

  @GET
  @Timed
  @Path("/lag")
  @UnitOfWork
  @Operation(summary = "Get the log pipeline's queue depth and write lag",
      description = "The depth of the application log queue in Redis together with the age of the newest row "
          + "in each log table, and the verdict those two imply: OK, LAGGING or STALLED. Reported together "
          + "because neither number means anything alone.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Queue depth, write lag and verdict"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", description = "Redis or the log database could not be read")
  })
  public Response lag() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Instant now = Instant.now();

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("observedAt", now.toString());
    report.put("appLogQueueDepth", appLogQueueDepth());

    List<Map<String, Object>> tables = new ArrayList<>();
    Long worstLagSeconds = null;
    for (LogQueryDAO.TableFreshness freshness : dao.freshness()) {
      Long lagSeconds = freshness.lagSeconds();
      Map<String, Object> table = new LinkedHashMap<>();
      table.put("table", freshness.table());
      table.put("sqlTable", freshness.sqlTable());
      table.put("newestAt", freshness.newestAt());
      table.put("lagSeconds", lagSeconds);
      tables.add(table);
      if (lagSeconds != null && (worstLagSeconds == null || lagSeconds > worstLagSeconds)) {
        worstLagSeconds = lagSeconds;
      }
    }
    report.put("tables", tables);
    report.put("worstLagSeconds", worstLagSeconds);
    report.put("status", verdict(worstLagSeconds));
    report.put("laggingAfterSeconds", LAGGING_AFTER.toSeconds());
    report.put("stalledAfterSeconds", STALLED_AFTER.toSeconds());
    return Response.ok(report).build();
  }

  private long appLogQueueDepth() {
    CacheServerPersistent cacheConfig = cedarConfig.getCacheConfig().getPersistent();
    try (JedisPool pool = new JedisPool(new JedisPoolConfig(), cacheConfig.getConnection().getHost(),
        cacheConfig.getConnection().getPort(), cacheConfig.getConnection().getTimeout());
         Jedis queue = pool.getResource()) {
      return queue.llen(cacheConfig.getQueueName(APP_LOG_QUEUE_ID));
    }
  }

  /**
   * The verdict the two numbers imply.
   *
   * <p>UNKNOWN where nothing has been written at all — an empty table is not the same as an
   * up-to-date one. A negative lag is left as OK: it means the log database's clock and the row's
   * timestamp disagree, which is worth seeing on the page but is not the worker falling behind.
   */
  private static String verdict(Long worstLagSeconds) {
    if (worstLagSeconds == null) {
      return "UNKNOWN";
    }
    if (worstLagSeconds >= STALLED_AFTER.toSeconds()) {
      return "STALLED";
    }
    if (worstLagSeconds >= LAGGING_AFTER.toSeconds()) {
      return "LAGGING";
    }
    return "OK";
  }
}
