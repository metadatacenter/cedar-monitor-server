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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/resource")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Diagnostics")
@SecurityRequirement(name = "api_key")
public class ResourceInfoGroup extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceInfoGroup.class);

  private static NodeSearchingService nodeSearchingService;

  public ResourceInfoGroup(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(NodeSearchingService nodeSearchingService) {
    ResourceInfoGroup.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/groups")
  @Operation(summary = "Get everything CEDAR knows about a group",
      description = "Gather what each store holds about one group into a single answer: the workspace graph's record of it and its members. "
          + "Written for diagnosis rather than for an application: the point is to see the stores "
          + "side by side, since a group that behaves oddly usually has one store disagreeing with "
          + "another. A store that cannot be reached leaves its section null rather than failing the "
          + "request, and an identifier nothing knows returns an empty answer with 200.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "What each store holds about the group"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getGroupInfo(
      @Parameter(description = "Identifier of the group to report on.", required = true)
      @QueryParam(PP_ID) String id) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CedarGroupId gid = CedarGroupId.build(id);

    GroupServiceSession groupSession = dataServices.getGroupServiceSession(c);

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
