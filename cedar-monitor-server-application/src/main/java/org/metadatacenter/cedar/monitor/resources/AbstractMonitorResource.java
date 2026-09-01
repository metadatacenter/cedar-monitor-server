package org.metadatacenter.cedar.monitor.resources;

import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;

public abstract class AbstractMonitorResource extends CedarMicroserviceResource {


  public AbstractMonitorResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public AbstractMonitorResource(CedarConfig cedarConfig, CedarDataServices dataServices) {
    super(cedarConfig, dataServices);
  }
}
