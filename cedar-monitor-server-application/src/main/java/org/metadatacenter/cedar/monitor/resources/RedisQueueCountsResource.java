package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
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

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.server.queue.util.QueueService.*;

@Path("/redis")
@Produces(MediaType.APPLICATION_JSON)
public class RedisQueueCountsResource extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(RedisQueueCountsResource.class);

  public RedisQueueCountsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/queue-counts")
  public Response queueCounts() throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CacheServerPersistent cacheConfig = cedarConfig.getCacheConfig().getPersistent();
    JedisPool pool = new JedisPool(new JedisPoolConfig(), cacheConfig.getConnection().getHost(),
        cacheConfig.getConnection().getPort(), cacheConfig.getConnection().getTimeout());
    String queueName = "queue";
    Jedis blockingQueue = pool.getResource();
    r.put(SEARCH_PERMISSION_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(SEARCH_PERMISSION_QUEUE_ID)));
    r.put(NCBI_SUBMISSION_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(NCBI_SUBMISSION_QUEUE_ID)));
    r.put(APP_LOG_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(APP_LOG_QUEUE_ID)));
    r.put(VALUERECOMMENDER_QUEUE_ID, blockingQueue.llen(cacheConfig.getQueueName(VALUERECOMMENDER_QUEUE_ID)));
    blockingQueue.llen(queueName);
    blockingQueue.close();
    pool.close();

    return Response.ok().entity(r).build();
  }

}
