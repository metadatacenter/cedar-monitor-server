package org.metadatacenter.cedar.internals.health;

import com.codahale.metrics.health.HealthCheck;

public class InternalsServerHealthCheck extends HealthCheck {

  public InternalsServerHealthCheck() {
  }

  @Override
  protected Result check() throws Exception {
    if (2 * 2 == 5) {
      return Result.unhealthy("Unhealthy, because 2 * 2 == 5");
    }
    return Result.healthy();
  }
}