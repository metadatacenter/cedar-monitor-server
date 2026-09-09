package org.metadatacenter.cedar.monitor.resources;

import org.junit.jupiter.api.Test;
import org.metadatacenter.util.test.OpenApiSuccessContract;

import java.io.IOException;
import java.io.InputStream;

/**
 * Holds this service's committed OpenAPI document to the estate's rule that a success payload is
 * described rather than merely acknowledged.
 *
 * <p>Only three of the eleven spec-shipping services verified their committed document at all, and
 * none of them checked the success side. A 2xx with no schema, a dangling reference, an empty schema
 * and a JSON body typed as a bare string all leave a client generator with nothing to work from, and
 * each is cheap to reintroduce by adding a route and forgetting an annotation.</p>
 *
 * <p>This service showed why the weakest of those checks matters. Its fifteen log routes carried no
 * OpenAPI annotations at all, so they reached the document with a `default` response and no 2xx,
 * which a check that only looked for a schema on declared success responses counted as complete.</p>
 */
class OpenApiSuccessContractTest {

  @Test
  void everySuccessPayloadIsDescribed() throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/assets/swagger-api/swagger.json")) {
      OpenApiSuccessContract.assertDescribed(input);
    }
  }
}
