package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarGroupId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.FolderServerGroup;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.GroupServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.security.model.auth.CedarGroupUsers;
import org.metadatacenter.server.security.model.auth.CedarNodeMaterializedPermissions;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.permission.resource.FilesystemResourcePermission;
import org.metadatacenter.server.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/resource")
@Produces(MediaType.APPLICATION_JSON)
public class ResourceInfoGroup extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceInfoGroup.class);

  private static UserService userService;
  private static NodeSearchingService nodeSearchingService;

  public ResourceInfoGroup(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(UserService userService, NodeSearchingService nodeSearchingService) {
    ResourceInfoGroup.userService = userService;
    ResourceInfoGroup.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/groups/{id}")
  public Response getGroupInfo(@PathParam(PP_ID) String id) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CedarGroupId gid = CedarGroupId.build(id);

    GroupServiceSession groupSession = CedarDataServices.getGroupServiceSession(c);

    FolderServerGroup group = groupSession.findGroupById(gid);

    if (group != null) {
      readGroupInfo(r, gid, group, groupSession);
    }

    return Response.ok().entity(r).build();
  }

  private void readGroupInfo(Map<String, Object> r, CedarGroupId gid, FolderServerGroup group,
                             GroupServiceSession groupSession) {
    r.put("resourceType", CedarResourceType.GROUP);
    r.put("neo4j", group);

    CedarGroupUsers groupUsers = groupSession.findGroupUsers(gid);
    r.put("groupUsers", groupUsers);

    List<String> allSearchCedarIds = findAllSearchCedarIds(gid);
    r.put("searchCedarIds", allSearchCedarIds);


    String readKey = CedarNodeMaterializedPermissions.getKey(gid.getId(), FilesystemResourcePermission.READ);
    String writeKey = CedarNodeMaterializedPermissions.getKey(gid.getId(), FilesystemResourcePermission.WRITE);

    Map<String, Object> opensearch = new HashMap<>();
    r.put("opensearch", opensearch);

    opensearch.put("readKey", readKey);
    opensearch.put("writeKey", writeKey);
  }

  private List<String> findAllSearchCedarIds(CedarGroupId groupId) {
    try {
      return nodeSearchingService.findAllCedarIdsForGroup(groupId);
    } catch (CedarProcessingException e) {
      log.error("Error while reading accessible document count", e);
    }
    return null;
  }

}
