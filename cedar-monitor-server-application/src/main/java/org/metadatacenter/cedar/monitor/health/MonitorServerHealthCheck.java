package org.metadatacenter.cedar.monitor.health;

import com.codahale.metrics.health.HealthCheck;

public class MonitorServerHealthCheck extends HealthCheck {

  public MonitorServerHealthCheck() {
  }

  @Override
  protected Result check() throws Exception {
    if (2 * 2 == 5) {
      return Result.unhealthy("Unhealthy, because 2 * 2 == 5");
    }
    return Result.healthy();
  }
}
