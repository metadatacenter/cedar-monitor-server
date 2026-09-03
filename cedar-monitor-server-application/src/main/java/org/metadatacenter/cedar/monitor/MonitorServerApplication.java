package org.metadatacenter.cedar.monitor;

import com.mongodb.client.MongoClient;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.monitor.resources.*;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceIndexResource;
import org.metadatacenter.cedar.util.dw.CedarHibernateBundle;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceApplicationWithMongo;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.MongoConfig;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.server.logging.dao.ApplicationCypherLogDAO;
import org.metadatacenter.server.logging.dao.ApplicationRequestLogDAO;
import org.metadatacenter.server.logging.dao.agg.AggregationQueryDAO;
import org.metadatacenter.server.logging.dao.agg.LogExplorerDAO;
import org.metadatacenter.server.logging.dao.query.LogQueryDAO;
import org.metadatacenter.server.logging.dbmodel.ApplicationCypherLog;
import org.metadatacenter.server.logging.dbmodel.ApplicationRequestLog;
import org.metadatacenter.server.logging.dbmodel.agg.AggCypherHourly;
import org.metadatacenter.server.logging.dbmodel.agg.AggCypherOutlier;
import org.metadatacenter.server.logging.dbmodel.agg.AggCypherQueryCatalog;
import org.metadatacenter.server.logging.dbmodel.agg.AggRequestHourly;
import org.metadatacenter.server.logging.dbmodel.agg.AggRequestOutlier;
import org.metadatacenter.server.logging.dbmodel.agg.AggRequestUserHourly;
import org.metadatacenter.server.logging.dbmodel.agg.LogAggregationState;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.util.IndexUtils;

public class MonitorServerApplication extends CedarMicroserviceApplicationWithMongo<MonitorServerConfiguration> {

  private CedarHibernateBundle<MonitorServerConfiguration> hibernate;
  private ApplicationRequestLogDAO requestLogDAO;
  private ApplicationCypherLogDAO cypherLogDAO;
  private AggregationQueryDAO aggregationQueryDAO;
  private LogExplorerDAO logExplorerDAO;
  private LogQueryDAO logQueryDAO;

  public static void main(String[] args) throws Exception {
    new MonitorServerApplication().run(args);
  }

  @Override
  protected ServerName getServerName() {
    return ServerName.MONITOR;
  }

  @Override
  protected void initializeWithBootstrap(Bootstrap<MonitorServerConfiguration> bootstrap, CedarConfig cedarConfig) {
    hibernate = new CedarHibernateBundle<>(
        cedarConfig.getDBLoggingConfig(),
        ApplicationRequestLog.class, new Class[]{
        ApplicationCypherLog.class,
        AggRequestHourly.class,
        AggCypherHourly.class,
        AggRequestUserHourly.class,
        AggCypherQueryCatalog.class,
        AggRequestOutlier.class,
        AggCypherOutlier.class,
        LogAggregationState.class,
    }
    );
    bootstrap.addBundle(hibernate);
  }

  @Override
  public void initializeApp() {

    requestLogDAO = new ApplicationRequestLogDAO(hibernate.getSessionFactory());
    cypherLogDAO = new ApplicationCypherLogDAO(hibernate.getSessionFactory());
    aggregationQueryDAO = new AggregationQueryDAO(hibernate.getSessionFactory());
    logExplorerDAO = new LogExplorerDAO(hibernate.getSessionFactory());
    logQueryDAO = new LogQueryDAO(hibernate.getSessionFactory());

    IndexUtils indexUtils = new IndexUtils(cedarConfig);
    NodeSearchingService nodeSearchingService = indexUtils.getNodeSearchingService();

    ResourceInfoUser.injectServices(userService, nodeSearchingService);
    ResourceInfoGroup.injectServices(nodeSearchingService);
    ResourceInfoFolder.injectServices(nodeSearchingService);
    ResourceInfoArtifact.injectServices(nodeSearchingService);

    MongoConfig artifactServerConfig = cedarConfig.getArtifactServerConfig();
    CedarDataServices.initializeMongoClientFactoryForDocuments(artifactServerConfig.getMongoConnection());

    MongoClient mongoClientForDocuments = CedarDataServices.getInstance().getMongoClientFactoryForDocuments().getClient();

    initMongoServices(mongoClientForDocuments, artifactServerConfig);

  }

  @Override
  public void runApp(MonitorServerConfiguration configuration, Environment environment) {

    final CedarMicroserviceIndexResource index =
        new CedarMicroserviceIndexResource(cedarConfig, getServerName());
    environment.jersey().register(index);


    final ResourceInfoUser resourceInfoUser = new ResourceInfoUser(cedarConfig);
    environment.jersey().register(resourceInfoUser);

    final ResourceInfoGroup resourceInfoGroup = new ResourceInfoGroup(cedarConfig);
    environment.jersey().register(resourceInfoGroup);

    final ResourceInfoFolder info = new ResourceInfoFolder(cedarConfig);
    environment.jersey().register(info);

    // One resource serves all four artifact kinds; each keeps its own path and OpenAPI entry.
    final ResourceInfoArtifact resourceInfoArtifact = new ResourceInfoArtifact(cedarConfig);
    environment.jersey().register(resourceInfoArtifact);

    final RedisQueueCountsResource redisQueueCounts = new RedisQueueCountsResource(cedarConfig);
    environment.jersey().register(redisQueueCounts);

    final ResourceCountsResource resourceCounts = new ResourceCountsResource(cedarConfig, templateFieldService, templateElementService, templateService, templateInstanceService);
    environment.jersey().register(resourceCounts);

    final ResourceCountsOpenSearchResource resourceCountsOpenSearch = new ResourceCountsOpenSearchResource(cedarConfig);
    environment.jersey().register(resourceCountsOpenSearch);

    final MySqlCountsResource mySqlCounts = new MySqlCountsResource(cedarConfig);
    environment.jersey().register(mySqlCounts);

    final HealthChecksResource healthChecksResource = new HealthChecksResource(cedarConfig);
    environment.jersey().register(healthChecksResource);

    final CommandResource commandResource = new CommandResource(cedarConfig);
    environment.jersey().register(commandResource);

    final LogUsageResource logUsageResource = new LogUsageResource(cedarConfig, aggregationQueryDAO);
    environment.jersey().register(logUsageResource);

    final LogExplorerResource logExplorerResource = new LogExplorerResource(cedarConfig, logExplorerDAO);
    environment.jersey().register(logExplorerResource);

    final LogQueryResource logQueryResource = new LogQueryResource(cedarConfig, logQueryDAO);
    environment.jersey().register(logQueryResource);

  }
}
