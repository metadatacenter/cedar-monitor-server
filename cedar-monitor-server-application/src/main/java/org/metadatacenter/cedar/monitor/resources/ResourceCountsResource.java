package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.util.dw.CedarCedarExceptionMapper;
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
import org.metadatacenter.server.service.TemplateElementService;
import org.metadatacenter.server.service.TemplateFieldService;
import org.metadatacenter.server.service.TemplateInstanceService;
import org.metadatacenter.server.service.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/resources")
@Produces(MediaType.APPLICATION_JSON)
public class ResourceCountsResource extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(ResourceCountsResource.class);
  private static TemplateFieldService<String, JsonNode> templateFieldService;
  private static TemplateElementService<String, JsonNode> templateElementService;
  private static TemplateService<String, JsonNode> templateService;
  private static TemplateInstanceService<String, JsonNode> templateInstanceService;
  private static NodeSearchingService nodeSearchingService;

  public ResourceCountsResource(CedarConfig cedarConfig, TemplateFieldService<String, JsonNode> templateFieldService,
                                TemplateElementService<String, JsonNode> templateElementService,
                                TemplateService<String, JsonNode> templateService, TemplateInstanceService<String,
      JsonNode> templateInstanceService) {
    super(cedarConfig);
    ResourceCountsResource.templateFieldService = templateFieldService;
    ResourceCountsResource.templateElementService = templateElementService;
    ResourceCountsResource.templateService = templateService;
    ResourceCountsResource.templateInstanceService = templateInstanceService;

    IndexUtils indexUtils = new IndexUtils(cedarConfig);
    nodeSearchingService = indexUtils.getNodeSearchingService();
  }

  @GET
  @Timed
  @Path("/counts")
  public Response queueCounts() throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    Map<String, Object> r = new HashMap<>();

    Map<String, Object> neo4j = new HashMap<>();
    r.put("neo4j", neo4j);

    UserServiceSession userSession = CedarDataServices.getUserServiceSession(c);
    long userCount = userSession.getUserCount();
    neo4j.put("user", userCount);

    GroupServiceSession groupSession = CedarDataServices.getGroupServiceSession(c);
    long groupCount = groupSession.getGroupCount();
    neo4j.put("group", groupCount);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
    long categoryCount = categorySession.getCategoryCount();
    neo4j.put("category", categoryCount);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    long folderCount = folderSession.getFolderCount();
    neo4j.put("folder", folderCount);

    Neo4JProxyFilesystemResource fsNeo4JProxy = CedarDataServices.getProxies().filesystemResource();
    long fieldTotalCount = fsNeo4JProxy.getTotalCount(CedarResourceType.FIELD);
    neo4j.put("field", fieldTotalCount);
    long elementTotalCount = fsNeo4JProxy.getTotalCount(CedarResourceType.ELEMENT);
    neo4j.put("element", elementTotalCount);
    long templateTotalCount = fsNeo4JProxy.getTotalCount(CedarResourceType.TEMPLATE);
    neo4j.put("template", templateTotalCount);
    long instanceTotalCount = fsNeo4JProxy.getTotalCount(CedarResourceType.INSTANCE);
    neo4j.put("instance", instanceTotalCount);

    Map<String, Object> mongo = new HashMap<>();
    r.put("mongo", mongo);

    mongo.put("field", templateFieldService.count());
    mongo.put("element", templateElementService.count());
    mongo.put("template", templateService.count());
    mongo.put("instance", templateInstanceService.count());

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
