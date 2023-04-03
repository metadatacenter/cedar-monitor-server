package org.metadatacenter.cedar.monitor;

import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.HibernateBundle;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.HibernateConfig;

import java.util.Map;

public class CedarMonitorHibernateBundle extends HibernateBundle<MonitorServerConfiguration> {

  private final CedarConfig cedarConfig;

  protected CedarMonitorHibernateBundle(CedarConfig cedarConfig, Class<?> entity, Class<?>[] entities) {
    super(entity, entities);
    this.cedarConfig = cedarConfig;
  }

  @Override
  public PooledDataSourceFactory getDataSourceFactory(MonitorServerConfiguration monitorServerConfiguration) {
    HibernateConfig dbLoggingConfig = cedarConfig.getDBLoggingConfig();
    DataSourceFactory database = new DataSourceFactory();
    database.setUrl(dbLoggingConfig.getUrl());
    database.setUser(dbLoggingConfig.getUser());
    database.setPassword(dbLoggingConfig.getPassword());
    database.setDriverClass(dbLoggingConfig.getDriverClass());
    Map<String, String> properties = database.getProperties();
    properties.putAll(dbLoggingConfig.getProperties());
    return database;
  }
}
