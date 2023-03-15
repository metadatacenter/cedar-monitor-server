package org.metadatacenter.cedar.internals.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.*;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerGroup;
import org.metadatacenter.model.folderserver.report.FolderServerArtifactReport;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.*;
import org.metadatacenter.server.neo4j.proxy.Neo4JProxies;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.security.model.auth.CedarNodeMaterializedPermissions;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.artifact.ArtifactReportUtil;
import org.metadatacenter.util.http.CedarResponse;
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
public class ResourceInfoResource extends AbstractInternalsResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceInfoResource.class);

  private static UserService userService;
  private static NodeSearchingService nodeSearchingService;

  public ResourceInfoResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(UserService userService, NodeSearchingService nodeSearchingService) {
    ResourceInfoResource.userService = userService;
    ResourceInfoResource.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/info/{id}")
  public Response search(@PathParam(PP_ID) String id) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.INTERNALS_READ);

    Map<String, Object> r = new HashMap<>();

    CedarUserId uid = CedarUserId.build(id);
    CedarGroupId gid = CedarGroupId.build(id);
    CedarCategoryId cid = CedarCategoryId.build(id);
    CedarFolderId foid = CedarFolderId.build(id);

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(id);

    CedarFieldId fid = CedarFieldId.build(id);
    CedarElementId eid = CedarElementId.build(id);
    CedarTemplateId tid = CedarTemplateId.build(id);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    UserServiceSession userSession = CedarDataServices.getUserServiceSession(c);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    Neo4JProxies proxies = CedarDataServices.getProxies();

    GroupServiceSession groupSession = CedarDataServices.getGroupServiceSession(c);
    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

    CedarUser cedarUser = userService.findUser(uid);
    FolderServerGroup group = groupSession.findGroupById(gid);
    if (group != null) {
      // TODO: return group info
    } else {
      FolderServerCategory category = categorySession.getCategoryById(cid);
      if (category != null) {
        // TODO: return category info
      } else {
        FolderServerFolder folder = folderSession.findFolderById(foid);
        if (folder != null) {
          readFolderInfo(c, r, foid, folder, proxies, folderSession, categorySession, permissionSession);
        } else {
          FolderServerArtifact artifact = folderSession.findArtifactById(aid);
          if (artifact != null) {
            readArtifactInfo(c, r, aid, artifact, proxies, folderSession, categorySession, permissionSession);
          } else {
            return CedarResponse.notFound()
                .id(id)
                .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
                .errorMessage("There was no resource found with the given id:" + id)
                .build();
          }
        }
      }
    }

    return Response.ok().entity(r).build();
  }

  private void readArtifactInfo(CedarRequestContext c, Map<String, Object> r, CedarUntypedArtifactId aid,
                                FolderServerArtifact artifact, Neo4JProxies proxies,
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

    Map<String, Object> elasticsearch = new HashMap<>();
    r.put("elasticsearch", elasticsearch);

    Map<String, Object> document = null;
    try {
      document = nodeSearchingService.getDocumentByCedarId(aid);
    } catch (CedarProcessingException e) {
      log.error("Error while reading artifact from elasticsearch", e);
    }
    elasticsearch.put("document", document);
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

    Map<String, Object> elasticsearch = new HashMap<>();
    r.put("elasticsearch", elasticsearch);

    Map<String, Object> document = null;
    try {
      document = nodeSearchingService.getDocumentByCedarId(foid);
    } catch (CedarProcessingException e) {
      log.error("Error while reading folder from elasticsearch", e);
    }
    elasticsearch.put("document", document);
  }

}
