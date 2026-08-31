package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * What each store holds about one artifact, for all four artifact kinds.
 *
 * <p>The four routes differ only in the noun they report on: the graph record, the path, the
 * computed report and permissions, and the OpenSearch document are gathered the same way whichever
 * kind is asked for. They were four classes of the same hundred-odd lines, differing on the class
 * name, the path and the wording of their documentation, so a change to the diagnosis — a store
 * added, a failure handled differently — had to be made four times or be made inconsistent.
 *
 * <p>Each route keeps its own path and its own OpenAPI entry, because a caller diagnosing a template
 * instance should not have to know that templates share the code. Only the body is shared.
 */
@Path("/resource")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Diagnostics")
@SecurityRequirement(name = "api_key")
public class ResourceInfoArtifact extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceInfoArtifact.class);

  private static NodeSearchingService nodeSearchingService;

  public ResourceInfoArtifact(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(NodeSearchingService nodeSearchingService) {
    ResourceInfoArtifact.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/templates")
  @Operation(summary = "Get everything CEDAR knows about a template",
      description = "Gather what each store holds about one template into a single answer: the workspace graph's record of it and its path, the computed report and permissions, and the OpenSearch document. "
          + "Written for diagnosis rather than for an application: the point is to see the stores "
          + "side by side, since a template that behaves oddly usually has one store disagreeing with "
          + "another. A store that cannot be reached leaves its section null rather than failing the "
          + "request, and an identifier nothing knows returns an empty answer with 200.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "What each store holds about the template"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateInfo(
      @Parameter(description = "Identifier of the template to report on.", required = true)
      @QueryParam(PP_ID) String id) throws CedarException {
    return artifactInfo(id);
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
    return artifactInfo(id);
  }

  @GET
  @Timed
  @Path("/template-fields")
  @Operation(summary = "Get everything CEDAR knows about a template field",
      description = "Gather what each store holds about one template field into a single answer: the workspace graph's record of it and its path, the computed report and permissions, and the OpenSearch document. "
          + "Written for diagnosis rather than for an application: the point is to see the stores "
          + "side by side, since a template field that behaves oddly usually has one store disagreeing with "
          + "another. A store that cannot be reached leaves its section null rather than failing the "
          + "request, and an identifier nothing knows returns an empty answer with 200.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "What each store holds about the template field"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateFieldInfo(
      @Parameter(description = "Identifier of the template field to report on.", required = true)
      @QueryParam(PP_ID) String id) throws CedarException {
    return artifactInfo(id);
  }

  @GET
  @Timed
  @Path("/template-instances")
  @Operation(summary = "Get everything CEDAR knows about a template instance",
      description = "Gather what each store holds about one template instance into a single answer: the workspace graph's record of it and its path, the computed report and permissions, and the OpenSearch document. "
          + "Written for diagnosis rather than for an application: the point is to see the stores "
          + "side by side, since a template instance that behaves oddly usually has one store disagreeing with "
          + "another. A store that cannot be reached leaves its section null rather than failing the "
          + "request, and an identifier nothing knows returns an empty answer with 200.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "What each store holds about the template instance"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateInstanceInfo(
      @Parameter(description = "Identifier of the template instance to report on.", required = true)
      @QueryParam(PP_ID) String id) throws CedarException {
    return artifactInfo(id);
  }

  /**
   * The gate and the gather, shared by all four routes. The artifact kind is not needed: the
   * identifier is untyped and every store is asked the same question about it.
   */
  private Response artifactInfo(String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(id);

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);
    ResourcePermissionServiceSession permissionSession = dataServices.getResourcePermissionServiceSession(c);

    // An identifier the graph does not know is an ordinary answer for a diagnostic route: the point
    // is to report what each store holds, and "nothing" is a result. Reading on would dereference a
    // null artifact and answer 500, which is what this did — alone among the four ResourceInfo
    // resources, the other three having guarded their lookup all along.
    FolderServerArtifact artifact = folderSession.findArtifactById(aid);
    if (artifact != null) {
      readArtifactInfo(c, r, aid, artifact, folderSession, categorySession, permissionSession);
    }

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
