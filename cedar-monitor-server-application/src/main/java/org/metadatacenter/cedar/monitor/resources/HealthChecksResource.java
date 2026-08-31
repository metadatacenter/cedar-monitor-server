package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.metadatacenter.constant.CedarPathParameters.PP_SERVER;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/health-check")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Health checks")
@SecurityRequirement(name = "api_key")
public class HealthChecksResource extends AbstractMonitorResource {

  private static final Logger log = LoggerFactory.getLogger(HealthChecksResource.class);

  public HealthChecksResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/{server}")
  @Operation(summary = "Get another server's health check",
      description = "Proxy the named server's Dropwizard health check on its admin port, which is "
          + "not otherwise reachable from outside. The status and body are the other server's own, "
          + "so a 500 here can mean that server is unhealthy rather than that this one failed.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The named server's health check, as it reported it"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "404", description = "No CEDAR server answers to this name"),
      @ApiResponse(responseCode = "500",
          description = "The named server reported itself unhealthy, or its response could not be read")
  })
  public Response healthCheck(
      @Parameter(description = "CEDAR server name, as the configuration spells it. Examples: "
          + "artifact, terminology, resource.", required = true)
      @PathParam(PP_SERVER) String server) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    ServerName serverName = ServerName.forName(server);
    ServerConfig serverConfig = cedarConfig.getServers().get(serverName);

    if (serverConfig == null) {
      return CedarResponse.notFound().errorMessage("Server can not be found by name").parameter("server", server).build();
    }

    String url = serverConfig.getAdminBase() + "healthcheck";
    ClassicHttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    HttpEntity entity = proxyResponse.getEntity();
    int statusCode = proxyResponse.getCode();
    String mediaType = entity.getContentType();
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
