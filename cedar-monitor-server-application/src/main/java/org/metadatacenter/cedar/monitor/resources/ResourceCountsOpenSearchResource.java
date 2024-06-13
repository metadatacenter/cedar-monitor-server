package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/resources")
@Produces(MediaType.APPLICATION_JSON)
public class ResourceCountsOpenSearchResource extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceCountsOpenSearchResource.class);
  private static NodeSearchingService nodeSearchingService;

  public ResourceCountsOpenSearchResource(CedarConfig cedarConfig) {
    super(cedarConfig);

    IndexUtils indexUtils = new IndexUtils(cedarConfig);
    nodeSearchingService = indexUtils.getNodeSearchingService();
  }

  @GET
  @Timed
  @Path("/counts/opensearch")
  public Response openSearchCounts() throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    Map<String, Object> opensearch = new HashMap<>();
    r.put("opensearch", opensearch);

    opensearch.put("field", nodeSearchingService.getTotalCount(CedarResourceType.FIELD));
    opensearch.put("element", nodeSearchingService.getTotalCount(CedarResourceType.ELEMENT));
    opensearch.put("template", nodeSearchingService.getTotalCount(CedarResourceType.TEMPLATE));
    opensearch.put("instance", nodeSearchingService.getTotalCount(CedarResourceType.INSTANCE));
    opensearch.put("folder", nodeSearchingService.getTotalCount(CedarResourceType.FOLDER));

    opensearch.put("artifactTotal", nodeSearchingService.getTotalArtifactCount());
    opensearch.put("recommenderTotal", nodeSearchingService.getTotalRecommenderCount());

    return Response.ok().entity(r).build();
  }

}
