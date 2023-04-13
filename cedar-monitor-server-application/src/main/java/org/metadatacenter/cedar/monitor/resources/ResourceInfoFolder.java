package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.*;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.report.FolderServerArtifactReport;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.*;
import org.metadatacenter.server.neo4j.proxy.Neo4JProxies;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.security.model.auth.CedarNodeMaterializedPermissions;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.artifact.ArtifactReportUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/resource")
@Produces(MediaType.APPLICATION_JSON)
public class ResourceInfoFolder extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceInfoFolder.class);

  private static UserService userService;
  private static NodeSearchingService nodeSearchingService;

  public ResourceInfoFolder(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(UserService userService, NodeSearchingService nodeSearchingService) {
    ResourceInfoFolder.userService = userService;
    ResourceInfoFolder.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/folders/{id}")
  public Response getFolderInfo(@PathParam(PP_ID) String id) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CedarFolderId fid = CedarFolderId.build(id);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    Neo4JProxies proxies = CedarDataServices.getProxies();

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

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
