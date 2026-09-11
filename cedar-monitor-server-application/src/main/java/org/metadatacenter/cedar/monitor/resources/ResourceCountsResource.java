package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.GroupServiceSession;
import org.metadatacenter.server.UserServiceSession;
import org.metadatacenter.server.neo4j.proxy.Neo4JProxyFilesystemResource;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.KeycloakUtilInfo;
import org.metadatacenter.server.security.KeycloakUtils;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.metadatacenter.exception.CedarDependencyUnavailableException;
import org.metadatacenter.util.http.ArtifactCounts;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.http.HttpTimeouts;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/resources")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Counts")
@SecurityRequirement(name = "api_key")
public class ResourceCountsResource extends AbstractMonitorResource {

  private final NodeSearchingService nodeSearchingService;

  public ResourceCountsResource(CedarConfig cedarConfig) {
    super(cedarConfig);

    IndexUtils indexUtils = new IndexUtils(cedarConfig);
    nodeSearchingService = indexUtils.getNodeSearchingService();
  }

  @GET
  @Timed
  @Path("/counts")
  @Operation(summary = "Compare counts across backing stores",
      description = "Report graph, document-store, search-index and Keycloak totals. Document counts are obtained through resource and artifact; a failed count does not become zero.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Counts by store and resource kind",
          content = @Content(schema = @Schema(ref = "#/components/schemas/StoreCounts"))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "A backing service is unavailable"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response resourceCounts() throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    String countsUrl = cedarConfig.getServers().getResource().getBase() + ArtifactCounts.PATH;
    try (ClassicHttpResponse upstream = ProxyUtil.proxyGet(countsUrl, c, HttpTimeouts.NO_REDIRECT_INTERACTIVE)) {
      r.put("mongo", ArtifactCounts.read(upstream));
    } catch (IOException e) {
      throw new CedarDependencyUnavailableException("Artifact counts are unavailable", e);
    }

    Map<String, Object> neo4j = new HashMap<>();
    r.put("neo4j", neo4j);

    UserServiceSession userSession = dataServices.getUserServiceSession(c);
    long userCount = userSession.getUserCount();
    neo4j.put("user", userCount);

    GroupServiceSession groupSession = dataServices.getGroupServiceSession(c);
    long groupCount = groupSession.getGroupCount();
    neo4j.put("group", groupCount);

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);
    long categoryCount = categorySession.getCategoryCount();
    neo4j.put("category", categoryCount);

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    long folderCount = folderSession.getFolderCount();
    neo4j.put("folder", folderCount);

    Neo4JProxyFilesystemResource fsNeo4JProxy = dataServices.getProxies().filesystemResource();
    long fieldTotalCount = fsNeo4JProxy.getTotalCount(CedarResourceType.FIELD);
    neo4j.put("field", fieldTotalCount);
    long elementTotalCount = fsNeo4JProxy.getTotalCount(CedarResourceType.ELEMENT);
    neo4j.put("element", elementTotalCount);
    long templateTotalCount = fsNeo4JProxy.getTotalCount(CedarResourceType.TEMPLATE);
    neo4j.put("template", templateTotalCount);
    long instanceTotalCount = fsNeo4JProxy.getTotalCount(CedarResourceType.INSTANCE);
    neo4j.put("instance", instanceTotalCount);

    Map<String, Object> opensearch = new HashMap<>();
    r.put("opensearch", opensearch);

    opensearch.put("field", nodeSearchingService.getTotalCount(CedarResourceType.FIELD));
    opensearch.put("element", nodeSearchingService.getTotalCount(CedarResourceType.ELEMENT));
    opensearch.put("template", nodeSearchingService.getTotalCount(CedarResourceType.TEMPLATE));
    opensearch.put("instance", nodeSearchingService.getTotalCount(CedarResourceType.INSTANCE));
    opensearch.put("folder", nodeSearchingService.getTotalCount(CedarResourceType.FOLDER));

    Map<String, Object> keycloak = new HashMap<>();
    r.put("keycloak", keycloak);

    try {
      KeycloakUtilInfo kcInfo = KeycloakUtils.initKeycloak(cedarConfig);
      Keycloak kc = KeycloakUtils.buildKeycloak(kcInfo);
      RealmResource realm = kc.realm(kcInfo.getKeycloakRealmName());
      keycloak.put("user", realm.users().count());
    } catch (Exception e) {
      r.put("errorPack", new CedarProcessingException(e).getMessage());
    }

    return Response.ok().entity(r).build();
  }

}
