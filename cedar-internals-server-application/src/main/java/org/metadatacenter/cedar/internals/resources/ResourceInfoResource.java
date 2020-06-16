package org.metadatacenter.cedar.internals.resources;

import com.codahale.metrics.annotation.Timed;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.*;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.*;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.GroupServiceSession;
import org.metadatacenter.server.UserServiceSession;
import org.metadatacenter.server.neo4j.proxy.Neo4JProxies;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.security.KeycloakUtilInfo;
import org.metadatacenter.server.security.KeycloakUtils;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.permission.resource.FilesystemResourcePermission;
import org.metadatacenter.server.security.model.user.CedarGroupExtract;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.security.model.user.ResourcePublicationStatusFilter;
import org.metadatacenter.server.security.model.user.ResourceVersionFilter;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.CedarResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    CedarUser cedarUser = userService.findUser(uid);
    if (cedarUser != null) {
      readUserInfo(r, uid, cedarUser, proxies, userSession);
    } else {
      GroupServiceSession groupSession = CedarDataServices.getGroupServiceSession(c);
      FolderServerGroup group = groupSession.findGroupById(gid);
      if (group != null) {
        // TODO: return group info
      } else {
        CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
        FolderServerCategory category = categorySession.getCategoryById(cid);
        if (category != null) {
          // TODO: return category info
        } else {
          FolderServerFolder folder = folderSession.findFolderById(foid);
          if (folder != null) {
            // TODO: return folder info
          } else {
            FolderServerArtifact artifact = folderSession.findArtifactById(aid);
            if (artifact != null) {
              // TODO: return artifact info
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
    }

    return Response.ok().entity(r).build();
  }

  private void readUserInfo(Map<String, Object> r, CedarUserId uid, CedarUser cedarUser, Neo4JProxies proxies, UserServiceSession userSession) {
    r.put("resourceType", CedarResourceType.USER);
    r.put("cedarUser", cedarUser);

    Map<String, Object> neo4j = new HashMap<>();
    r.put("neo4j", neo4j);

    FolderServerUser folderServeUser = userSession.getUser(uid);
    neo4j.put("user", folderServeUser);

    Map<String, Object> counts1 = new HashMap<>();
    counts1.put("field", getAccessibleSearchIndexDocumentCount(proxies, CedarResourceType.FIELD, cedarUser));
    counts1.put("element", getAccessibleSearchIndexDocumentCount(proxies, CedarResourceType.ELEMENT, cedarUser));
    counts1.put("template", getAccessibleSearchIndexDocumentCount(proxies, CedarResourceType.TEMPLATE, cedarUser));
    counts1.put("templateInstance", getAccessibleSearchIndexDocumentCount(proxies, CedarResourceType.INSTANCE, cedarUser));
    counts1.put("folder", getAccessibleSearchIndexDocumentCount(proxies, CedarResourceType.FOLDER, cedarUser));

    neo4j.put("accessibleCount", counts1);

    List<FolderServerGroup> groupsOfMemberUser = proxies.group().findGroupsOfMemberUser(uid);
    List<FolderServerGroup> groupsOfAdministratorUser = proxies.group().findGroupsOfAdministratorUser(uid);

    List<CedarGroupExtract> memberGroups = new ArrayList<>();
    for (FolderServerGroup g : groupsOfMemberUser) {
      memberGroups.add(new CedarGroupExtract(g.getId(), g.getName()));
    }

    List<CedarGroupExtract> adminGroups = new ArrayList<>();
    for (FolderServerGroup g : groupsOfAdministratorUser) {
      adminGroups.add(new CedarGroupExtract(g.getId(), g.getName()));
    }

    neo4j.put("groupsWithMembership", memberGroups);
    neo4j.put("groupsWithAdministrator", adminGroups);

    Map<String, Object> elasticsearch = new HashMap<>();
    r.put("elasticsearch", elasticsearch);

    Map<String, Object> counts2 = new HashMap<>();
    counts2.put("field", getAccessibleSearchIndexDocumentCount(CedarResourceType.FIELD, cedarUser, FilesystemResourcePermission.READ));
    counts2.put("element", getAccessibleSearchIndexDocumentCount(CedarResourceType.ELEMENT, cedarUser, FilesystemResourcePermission.READ));
    counts2.put("template", getAccessibleSearchIndexDocumentCount(CedarResourceType.TEMPLATE, cedarUser, FilesystemResourcePermission.READ));
    counts2.put("templateInstance", getAccessibleSearchIndexDocumentCount(CedarResourceType.INSTANCE, cedarUser, FilesystemResourcePermission.READ));
    counts2.put("folder", getAccessibleSearchIndexDocumentCount(CedarResourceType.FOLDER, cedarUser, FilesystemResourcePermission.READ));

    elasticsearch.put("readableCount", counts2);

    Map<String, Object> counts3 = new HashMap<>();
    counts3.put("field", getAccessibleSearchIndexDocumentCount(CedarResourceType.FIELD, cedarUser, FilesystemResourcePermission.WRITE));
    counts3.put("element", getAccessibleSearchIndexDocumentCount(CedarResourceType.ELEMENT, cedarUser, FilesystemResourcePermission.WRITE));
    counts3.put("template", getAccessibleSearchIndexDocumentCount(CedarResourceType.TEMPLATE, cedarUser, FilesystemResourcePermission.WRITE));
    counts3.put("templateInstance", getAccessibleSearchIndexDocumentCount(CedarResourceType.INSTANCE, cedarUser, FilesystemResourcePermission.WRITE));
    counts3.put("folder", getAccessibleSearchIndexDocumentCount(CedarResourceType.FOLDER, cedarUser, FilesystemResourcePermission.WRITE));

    elasticsearch.put("writeableCount", counts3);


    Map<String, Object> keycloak = new HashMap<>();
    r.put("keycloak", keycloak);

    try {
      KeycloakUtilInfo kcInfo = KeycloakUtils.initKeycloak(cedarConfig);

      Keycloak kc = KeycloakUtils.buildKeycloak(kcInfo);
      String userUUID = linkedDataUtil.getUUID(uid.getId(), CedarResourceType.USER);
      UserResource userResource = kc.realm(kcInfo.getKeycloakRealmName()).users().get(userUUID);
      UserRepresentation userRepresentation = userResource.toRepresentation();
      List<RoleRepresentation> roleRepresentations = userResource.roles().realmLevel().listEffective();
      List<String> realmRoles = new ArrayList<>();
      for (RoleRepresentation rr : roleRepresentations) {
        realmRoles.add(rr.getName());
      }
      userRepresentation.setRealmRoles(realmRoles);
      keycloak.put("user", userRepresentation);
    } catch (Exception e) {
      log.error("Error while reading user from Keycloak", e);
      keycloak.put("user", null);
    }
  }

  private long getAccessibleSearchIndexDocumentCount(CedarResourceType resourceType, CedarUser cedarUser, FilesystemResourcePermission permission) {
    List<String> resourceTypes = new ArrayList<>();
    resourceTypes.add(resourceType.getValue());
    try {
      return nodeSearchingService.searchAccessibleResourceCountByUser(resourceTypes, permission, cedarUser);
    } catch (CedarProcessingException e) {
      log.error("Error while reading accessible document count", e);
    }
    return -1;
  }

  private long getAccessibleSearchIndexDocumentCount(Neo4JProxies proxies, CedarResourceType resourceType, CedarUser cedarUser) {
    List<CedarResourceType> resourceTypes = new ArrayList<>();
    resourceTypes.add(resourceType);
    return proxies.resource().viewAllFilteredCount(resourceTypes, ResourceVersionFilter.ALL, ResourcePublicationStatusFilter.ALL, cedarUser);
  }

}
