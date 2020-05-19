package org.metadatacenter.cedar.internals.resources;

import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;

public abstract class AbstractInternalsResource extends CedarMicroserviceResource {

  public AbstractInternalsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }
}
