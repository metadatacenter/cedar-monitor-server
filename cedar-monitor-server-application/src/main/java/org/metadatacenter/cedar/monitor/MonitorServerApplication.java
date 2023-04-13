package org.metadatacenter.cedar.monitor;

import com.mongodb.MongoClient;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.monitor.health.MonitorServerHealthCheck;
import org.metadatacenter.cedar.monitor.resources.*;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceApplicationWithMongo;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.MongoConfig;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.server.logging.dao.ApplicationCypherLogDAO;
import org.metadatacenter.server.logging.dao.ApplicationRequestLogDAO;
import org.metadatacenter.server.logging.dbmodel.ApplicationCypherLog;
import org.metadatacenter.server.logging.dbmodel.ApplicationRequestLog;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.util.IndexUtils;

public class MonitorServerApplication extends CedarMicroserviceApplicationWithMongo<MonitorServerConfiguration> {

  private HibernateBundle<MonitorServerConfiguration> hibernate;
  private ApplicationRequestLogDAO requestLogDAO;
  private ApplicationCypherLogDAO cypherLogDAO;

  public static void main(String[] args) throws Exception {
    new MonitorServerApplication().run(args);
  }

  @Override
  protected ServerName getServerName() {
    return ServerName.MONITOR;
  }

  @Override
  protected void initializeWithBootstrap(Bootstrap<MonitorServerConfiguration> bootstrap, CedarConfig cedarConfig) {
    hibernate = new CedarMonitorHibernateBundle(
        cedarConfig,
        ApplicationRequestLog.class, new Class[]{
        ApplicationCypherLog.class,
    }
    );
    bootstrap.addBundle(hibernate);
  }

  @Override
  public void initializeApp() {

    requestLogDAO = new ApplicationRequestLogDAO(hibernate.getSessionFactory());
    cypherLogDAO = new ApplicationCypherLogDAO(hibernate.getSessionFactory());

    IndexUtils indexUtils = new IndexUtils(cedarConfig);
    NodeSearchingService nodeSearchingService = indexUtils.getNodeSearchingService();

    ResourceInfoUser.injectServices(userService, nodeSearchingService);
    ResourceInfoGroup.injectServices(userService, nodeSearchingService);
    ResourceInfoFolder.injectServices(userService, nodeSearchingService);
    ResourceInfoTemplateField.injectServices(userService, nodeSearchingService);
    ResourceInfoTemplateElement.injectServices(userService, nodeSearchingService);
    ResourceInfoTemplate.injectServices(userService, nodeSearchingService);
    ResourceInfoTemplateInstance.injectServices(userService, nodeSearchingService);

    MongoConfig artifactServerConfig = cedarConfig.getArtifactServerConfig();
    CedarDataServices.initializeMongoClientFactoryForDocuments(artifactServerConfig.getMongoConnection());

    MongoClient mongoClientForDocuments = CedarDataServices.getMongoClientFactoryForDocuments().getClient();

    initMongoServices(mongoClientForDocuments, artifactServerConfig);

  }

  @Override
  public void runApp(MonitorServerConfiguration configuration, Environment environment) {

    final IndexResource index = new IndexResource();
    environment.jersey().register(index);

    final MonitorServerHealthCheck healthCheck = new MonitorServerHealthCheck();
    environment.healthChecks().register("message", healthCheck);

    final ResourceInfoUser resourceInfoUser = new ResourceInfoUser(cedarConfig);
    environment.jersey().register(resourceInfoUser);

    final ResourceInfoGroup resourceInfoGroup = new ResourceInfoGroup(cedarConfig);
    environment.jersey().register(resourceInfoGroup);

    final ResourceInfoFolder info = new ResourceInfoFolder(cedarConfig);
    environment.jersey().register(info);

    final ResourceInfoTemplateField resourceInfoTemplateField = new ResourceInfoTemplateField(cedarConfig);
    environment.jersey().register(resourceInfoTemplateField);

    final ResourceInfoTemplateElement resourceInfoTemplateElement = new ResourceInfoTemplateElement(cedarConfig);
    environment.jersey().register(resourceInfoTemplateElement);

    final ResourceInfoTemplate resourceInfoTemplate = new ResourceInfoTemplate(cedarConfig);
    environment.jersey().register(resourceInfoTemplate);

    final ResourceInfoTemplateInstance resourceInfoTemplateInstance = new ResourceInfoTemplateInstance(cedarConfig);
    environment.jersey().register(resourceInfoTemplateInstance);

    final RedisQueueCountsResource redisQueueCounts = new RedisQueueCountsResource(cedarConfig);
    environment.jersey().register(redisQueueCounts);

    final ResourceCountsResource resourceCounts = new ResourceCountsResource(cedarConfig, templateFieldService, templateElementService, templateService, templateInstanceService);
    environment.jersey().register(resourceCounts);

    final HealthChecksResource healthChecksResource = new HealthChecksResource(cedarConfig);
    environment.jersey().register(healthChecksResource);

    final CommandResource commandResource = new CommandResource(cedarConfig);
    environment.jersey().register(commandResource);

  }
}
