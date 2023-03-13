package org.metadatacenter.cedar.internals.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.ServerConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.ProxyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.metadatacenter.constant.CedarPathParameters.PP_SERVER;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/health-check")
@Produces(MediaType.APPLICATION_JSON)
public class HealthChecksResource extends AbstractInternalsResource {

  private static final Logger log = LoggerFactory.getLogger(HealthChecksResource.class);

  public HealthChecksResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/{server}")
  public Response healthCheck(@PathParam(PP_SERVER) String server) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.INTERNALS_READ);

    ServerName serverName = ServerName.forName(server);
    ServerConfig serverConfig = cedarConfig.getServers().get(serverName);

    if (serverConfig == null) {
      return CedarResponse.notFound().errorMessage("Server can not be found by name").parameter("server", server).build();
    }

    String url = serverConfig.getAdminBase() + "healthcheck";
    System.out.println("---------------------------------------------------------");
    System.out.println(url);
    // parameter
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    HttpEntity entity = proxyResponse.getEntity();
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    String mediaType = entity.getContentType().getValue();
    if (entity != null) {
      try {
        String content = new String(entity.getContent().readAllBytes(), StandardCharsets.UTF_8);
        return Response.status(statusCode).type(mediaType).entity(content).build();
      } catch (IOException e) {
        return CedarResponse.internalServerError().errorMessage("Error while reading response").exception(e).build();
      }
    } else {
      return Response.status(statusCode).type(mediaType).build();
    }
  }


}
