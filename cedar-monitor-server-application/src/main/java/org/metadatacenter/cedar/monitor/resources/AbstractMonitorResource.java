package org.metadatacenter.cedar.monitor.resources;

import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;

public abstract class AbstractMonitorResource extends CedarMicroserviceResource {

  protected final org.metadatacenter.bridge.CedarDataServices dataServices;

  public AbstractMonitorResource(CedarConfig cedarConfig) {
    this(cedarConfig, org.metadatacenter.bridge.CedarDataServices.getInstance());
  }

  public AbstractMonitorResource(CedarConfig cedarConfig, org.metadatacenter.bridge.CedarDataServices dataServices) {
    super(cedarConfig);
    this.dataServices = dataServices;
  }
}
