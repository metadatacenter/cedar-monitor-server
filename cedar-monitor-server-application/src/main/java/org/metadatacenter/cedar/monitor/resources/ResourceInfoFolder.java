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
import org.metadatacenter.id.*;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.*;
import org.metadatacenter.server.neo4j.proxy.Neo4JProxies;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.security.model.auth.CedarNodeMaterializedPermissions;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
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
public class ResourceInfoFolder extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceInfoFolder.class);

  private static NodeSearchingService nodeSearchingService;

  public ResourceInfoFolder(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(NodeSearchingService nodeSearchingService) {
    ResourceInfoFolder.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/folders")
  @Operation(summary = "Get everything CEDAR knows about a folder",
      description = "Gather what each store holds about one folder into a single answer: the workspace graph's record of it and its path, its computed permissions, and the OpenSearch document. "
          + "Written for diagnosis rather than for an application: the point is to see the stores "
          + "side by side, since a folder that behaves oddly usually has one store disagreeing with "
          + "another. A store that cannot be reached leaves its section null rather than failing the "
          + "request, and an identifier nothing knows returns an empty answer with 200.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "What each store holds about the folder"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getFolderInfo(
      @Parameter(description = "Identifier of the folder to report on.", required = true)
      @QueryParam(PP_ID) String id) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CedarFolderId fid = CedarFolderId.build(id);

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    Neo4JProxies proxies = dataServices.getProxies();

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);
    ResourcePermissionServiceSession permissionSession = dataServices.getResourcePermissionServiceSession(c);

    FolderServerFolder folder = folderSession.findFolderById(fid);
    if (folder != null) {
      readFolderInfo(c, r, fid, folder, proxies, folderSession, categorySession, permissionSession);
    }

    return Response.ok().entity(r).build();
  }

  private void readFolderInfo(CedarRequestContext c, Map<String, Object> r, CedarFolderId foid,
                              FolderServerFolder folder, Neo4JProxies proxies,
                              FolderServiceSession folderSession, CategoryServiceSession categorySession,
                              ResourcePermissionServiceSession permissionSession) {
    r.put("resourceType", folder.getType());

    Map<String, Object> neo4j = new HashMap<>();
    r.put("neo4j", neo4j);

    neo4j.put("folder", folder);

    folderSession.addPathAndParentId(folder);

    folder.setPathInfo(PathInfoBuilder.getResourcePathExtract(c, folderSession, permissionSession, folder));

    Map<String, Object> computed = new HashMap<>();
    r.put("computed", computed);

    CedarNodePermissionsWithExtract resourcePermissions = permissionSession.getResourcePermissions(foid);
    CedarNodeMaterializedPermissions resourceMaterializedPermission =
        permissionSession.getResourceMaterializedPermission(foid);

    computed.put("permissions", resourcePermissions);
    computed.put("materializedPermissions", resourceMaterializedPermission);

    Map<String, Object> opensearch = new HashMap<>();
    r.put("opensearch", opensearch);

    Map<String, Object> document = null;
    try {
      document = nodeSearchingService.getDocumentByCedarId(foid);
    } catch (CedarProcessingException e) {
      log.error("Error while reading folder from elasticsearch", e);
    }
    opensearch.put("document", document);
  }

}
