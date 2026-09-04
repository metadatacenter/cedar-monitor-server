package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/resources")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Counts")
@SecurityRequirement(name = "api_key")
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
  @Operation(summary = "Count what the search index holds",
      description = "Report how many artifacts of each type OpenSearch has indexed. Read alongside the graph counts: the two disagreeing is how a half-finished reindex shows itself.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A count per artifact type, as the search index has them"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
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
