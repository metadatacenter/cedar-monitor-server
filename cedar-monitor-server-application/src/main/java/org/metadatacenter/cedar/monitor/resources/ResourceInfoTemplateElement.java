package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.report.FolderServerArtifactReport;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.security.model.auth.CedarNodeMaterializedPermissions;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.artifact.ArtifactReportUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/resource")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Diagnostics")
@SecurityRequirement(name = "api_key")
public class ResourceInfoTemplateElement extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceInfoTemplateElement.class);

  private static NodeSearchingService nodeSearchingService;

  public ResourceInfoTemplateElement(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(NodeSearchingService nodeSearchingService) {
    ResourceInfoTemplateElement.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/template-elements")
  @Operation(summary = "Get everything CEDAR knows about a template element",
      description = "Gather what each store holds about one template element into a single answer: the workspace graph's record of it and its path, the computed report and permissions, and the OpenSearch document. "
          + "Written for diagnosis rather than for an application: the point is to see the stores "
          + "side by side, since a template element that behaves oddly usually has one store disagreeing with "
          + "another. A store that cannot be reached leaves its section null rather than failing the "
          + "request, and an identifier nothing knows returns an empty answer with 200.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "What each store holds about the template element"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateElementInfo(
      @Parameter(description = "Identifier of the template element to report on.", required = true)
      @QueryParam(PP_ID) String id) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(id);

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);
    ResourcePermissionServiceSession permissionSession = dataServices.getResourcePermissionServiceSession(c);

    FolderServerArtifact artifact = folderSession.findArtifactById(aid);
    readArtifactInfo(c, r, aid, artifact,  folderSession, categorySession, permissionSession);

    return Response.ok().entity(r).build();
  }

  private void readArtifactInfo(CedarRequestContext c, Map<String, Object> r, CedarUntypedArtifactId aid,
                                FolderServerArtifact artifact,
                                FolderServiceSession folderSession, CategoryServiceSession categorySession,
                                ResourcePermissionServiceSession permissionSession) {
    r.put("resourceType", artifact.getType());

    Map<String, Object> neo4j = new HashMap<>();
    r.put("neo4j", neo4j);

    neo4j.put("artifact", artifact);

    folderSession.addPathAndParentId(artifact);

    artifact.setPathInfo(PathInfoBuilder.getResourcePathExtract(c, folderSession, permissionSession, artifact));

    Map<String, Object> computed = new HashMap<>();
    r.put("computed", computed);

    FolderServerArtifactReport resourceReport = ArtifactReportUtil.getArtifactReport(c, cedarConfig, artifact,
        folderSession, permissionSession,
        categorySession);

    computed.put("report", resourceReport);

    CedarNodePermissionsWithExtract resourcePermissions = permissionSession.getResourcePermissions(aid);
    CedarNodeMaterializedPermissions resourceMaterializedPermission =
        permissionSession.getResourceMaterializedPermission(aid);

    computed.put("permissions", resourcePermissions);
    computed.put("materializedPermissions", resourceMaterializedPermission);

    Map<String, Object> opensearch = new HashMap<>();
    r.put("opensearch", opensearch);

    Map<String, Object> document = null;
    try {
      document = nodeSearchingService.getDocumentByCedarId(aid);
    } catch (CedarProcessingException e) {
      log.error("Error while reading artifact from opensearch", e);
    }
    opensearch.put("document", document);
  }

}
