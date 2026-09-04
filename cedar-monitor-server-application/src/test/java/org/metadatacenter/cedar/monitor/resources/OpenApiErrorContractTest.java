package org.metadatacenter.cedar.monitor.resources;

import org.junit.jupiter.api.Test;
import org.metadatacenter.util.test.OpenApiErrorContract;

import java.io.IOException;
import java.io.InputStream;

class OpenApiErrorContractTest {

  @Test
  void errorResponsesPublishTheCommonSchema() throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/assets/swagger-api/swagger.json")) {
      OpenApiErrorContract.assertDocumented(input, "GET /health-check/{server} 500");
    }
  }
}
