package org.metadatacenter.cedar.internals;

import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.metadatacenter.cedar.internals.health.InternalsServerHealthCheck;
import org.metadatacenter.cedar.internals.resources.*;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceApplication;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.server.logging.dao.ApplicationCypherLogDAO;
import org.metadatacenter.server.logging.dao.ApplicationRequestLogDAO;
import org.metadatacenter.server.logging.dbmodel.ApplicationCypherLog;
import org.metadatacenter.server.logging.dbmodel.ApplicationRequestLog;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.util.IndexUtils;

public class InternalsServerApplication extends CedarMicroserviceApplication<InternalsServerConfiguration> {

  private HibernateBundle<InternalsServerConfiguration> hibernate;
  private ApplicationRequestLogDAO requestLogDAO;
  private ApplicationCypherLogDAO cypherLogDAO;

  public static void main(String[] args) throws Exception {
    new InternalsServerApplication().run(args);
  }

  @Override
  protected ServerName getServerName() {
    return ServerName.INTERNALS;
  }

  @Override
  protected void initializeWithBootstrap(Bootstrap<InternalsServerConfiguration> bootstrap, CedarConfig cedarConfig) {
    hibernate = new CedarInternalsHibernateBundle(
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
    ResourceInfoTemplateField.injectServices(userService, nodeSearchingService);
    ResourceInfoResource.injectServices(userService, nodeSearchingService);
  }

  @Override
  public void runApp(InternalsServerConfiguration configuration, Environment environment) {

    final IndexResource index = new IndexResource();
    environment.jersey().register(index);

    final InternalsServerHealthCheck healthCheck = new InternalsServerHealthCheck();
    environment.healthChecks().register("message", healthCheck);

    final ResourceInfoUser resourceInfoUser = new ResourceInfoUser(cedarConfig);
    environment.jersey().register(resourceInfoUser);

    final ResourceInfoTemplateField resourceInfoTemplateField = new ResourceInfoTemplateField(cedarConfig);
    environment.jersey().register(resourceInfoTemplateField);

    final ResourceInfoResource info = new ResourceInfoResource(cedarConfig);
    environment.jersey().register(info);

    final HealthChecksResource healthChecksResource = new HealthChecksResource(cedarConfig);
    environment.jersey().register(healthChecksResource);

    final CommandResource commandResource = new CommandResource(cedarConfig);
    environment.jersey().register(commandResource);

  }
}
