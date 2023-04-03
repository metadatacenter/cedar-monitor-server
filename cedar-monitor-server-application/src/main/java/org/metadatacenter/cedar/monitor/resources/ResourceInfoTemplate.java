package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
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
public class ResourceInfoTemplate extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceInfoTemplate.class);

  private static UserService userService;
  private static NodeSearchingService nodeSearchingService;

  public ResourceInfoTemplate(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(UserService userService, NodeSearchingService nodeSearchingService) {
    ResourceInfoTemplate.userService = userService;
    ResourceInfoTemplate.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/templates/{id}")
  public Response search(@PathParam(PP_ID) String id) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(id);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

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
      log.error("Error while reading artifact from elasticsearch", e);
    }
    opensearch.put("document", document);
  }

}
