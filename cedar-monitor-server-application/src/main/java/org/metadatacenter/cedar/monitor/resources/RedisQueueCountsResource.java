package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.config.CacheServerPersistent;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.server.queue.util.QueueService.*;

@Path("/redis")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Queues")
@SecurityRequirement(name = "api_key")
public class RedisQueueCountsResource extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(RedisQueueCountsResource.class);

  public RedisQueueCountsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/queue-counts")
  @Operation(summary = "Get the depth of each work queue",
      description = "Report how many items are waiting in each of CEDAR's Redis queues: search permissions, NCBI submissions, application logs, value recommender, and instance cloning. A queue that keeps growing is the sign that whatever drains it has stopped.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A count per queue"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response queueCounts() throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CacheServerPersistent cacheConfig = cedarConfig.getCacheConfig().getPersistent();
    try (JedisPool pool = new JedisPool(new JedisPoolConfig(), cacheConfig.getConnection().getHost(),
        cacheConfig.getConnection().getPort(), cacheConfig.getConnection().getTimeout());
         Jedis blockingQueue = pool.getResource()) {
      r.put(SEARCH_PERMISSION_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(SEARCH_PERMISSION_QUEUE_ID)));
      r.put(NCBI_SUBMISSION_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(NCBI_SUBMISSION_QUEUE_ID)));
      r.put(APP_LOG_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(APP_LOG_QUEUE_ID)));
      r.put(VALUERECOMMENDER_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(VALUERECOMMENDER_QUEUE_ID)));
      r.put(CLONE_INSTANCES_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(CLONE_INSTANCES_QUEUE_ID)));
    }

    return Response.ok().entity(r).build();
  }

}
