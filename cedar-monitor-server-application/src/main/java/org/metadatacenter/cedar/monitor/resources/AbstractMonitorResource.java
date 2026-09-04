package org.metadatacenter.cedar.monitor.resources;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.ServerConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.ProxyUtil;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public abstract class AbstractMonitorResource extends CedarMicroserviceResource {


  public AbstractMonitorResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public AbstractMonitorResource(CedarConfig cedarConfig, CedarDataServices dataServices) {
    super(cedarConfig, dataServices);
  }

  /**
   * Reads one route from another CEDAR server and returns what it said, unchanged.
   *
   * <p>Every cross-service page in the Monitor works this way. The other server's status and body
   * are passed through rather than reinterpreted, so a 500 from this method means the named server
   * answered 500 — a page that rewrote that into its own error would hide which of the two failed.
   *
   * <p>The application connector is used, not the admin one: the admin connector is bound to
   * loopback, which under Docker puts it out of reach of every container but its own.
   * {@link ProxyUtil} forwards the caller's own credential, which the routes on the other side
   * require.
   *
   * @param server       the server's name as the configuration spells it
   * @param relativePath the route to read, relative to that server's application base URL
   */
  protected Response proxyToServer(String server, String relativePath, CedarRequestContext c)
      throws CedarException {
    ServerName serverName = ServerName.forName(server);
    ServerConfig serverConfig = serverName == null ? null : cedarConfig.getServers().get(serverName);

    if (serverConfig == null) {
      return CedarResponse.notFound()
          .errorMessage("Server can not be found by name")
          .parameter("server", server)
          .build();
    }
    if (serverConfig.getBase() == null) {
      return CedarResponse.internalServerError()
          .errorMessage("No application base URL is configured for this server, so it can not be read")
          .parameter("server", server)
          .build();
    }

    ClassicHttpResponse proxyResponse = ProxyUtil.proxyGet(serverConfig.getBase() + relativePath, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    HttpEntity entity = proxyResponse.getEntity();
    int statusCode = proxyResponse.getCode();
    if (entity == null) {
      return Response.status(statusCode).build();
    }
    String mediaType = entity.getContentType();
    try {
      String content = new String(entity.getContent().readAllBytes(), StandardCharsets.UTF_8);
      return Response.status(statusCode).type(mediaType).entity(content).build();
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .errorMessage("Error while reading the response of " + server)
          .exception(e)
          .build();
    }
  }
}
