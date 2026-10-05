package org.metadatacenter.cedar.monitor.resources;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.ServerConfig;
import org.metadatacenter.exception.CedarDependencyUnavailableException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.ProxyUtil;
import org.opensearch.OpenSearchStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public abstract class AbstractMonitorResource extends CedarMicroserviceResource {

  private static final Logger log = LoggerFactory.getLogger(AbstractMonitorResource.class);

  /** The key a diagnostic section records an unreadable store under. */
  protected static final String UNAVAILABLE = "unavailable";


  public AbstractMonitorResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public AbstractMonitorResource(CedarConfig cedarConfig, CedarDataServices dataServices) {
    super(cedarConfig, dataServices);
  }

  /** One read of a store a diagnostic answer reports on. */
  @FunctionalInterface
  protected interface StoreRead<T> {
    T read() throws Exception;
  }

  /**
   * What a store holds, or null with the reason recorded in the section under {@code unavailable}.
   *
   * <p>A diagnostic answer puts the stores side by side, so one that cannot be read is a finding
   * rather than a failed request. It used to leave only the null, which is also what a store holding
   * nothing gives, so an index that was down read as an artifact that had never been indexed. An
   * index that answered with an error, a missing one for instance, failed the whole answer with 500.
   */
  protected static <T> T readStore(Map<String, Object> section, String store, StoreRead<T> read) {
    try {
      return read.read();
    } catch (Exception e) {
      log.warn("{} could not be read for a diagnostic answer", store, e);
      section.put(UNAVAILABLE, store + " could not be read: "
          + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      return null;
    }
  }

  /**
   * Reads a count from OpenSearch for an answer that must not be partial. An index that answered
   * with an error is as unreadable as one that did not answer, and was a 500 where that was a 503.
   */
  protected static long openSearchCount(StoreRead<Long> read) throws CedarException {
    try {
      return read.read();
    } catch (CedarException e) {
      throw e;
    } catch (OpenSearchStatusException e) {
      throw new CedarDependencyUnavailableException("OpenSearch could not be read", e);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
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
          .message("Server can not be found by name")
          .parameter("server", server)
          .build();
    }
    if (serverConfig.getBase() == null) {
      return CedarResponse.internalServerError()
          .message("No application base URL is configured for this server, so it can not be read")
          .parameter("server", server)
          .build();
    }

    return render(server, ProxyUtil.proxyGet(serverConfig.getBase() + relativePath, c));
  }

  /**
   * The same, for a route that is asked to do something rather than to report.
   *
   * <p>The Monitor is otherwise read-only, and the one thing it may start — a search index rebuild —
   * is not its own authority to grant. {@link ProxyUtil} forwards the caller's credential, so the
   * resource server decides, against the permission that command has always required. The Monitor
   * adds no privilege of its own; it only gives the command a page to be started from.
   */
  protected Response proxyPostToServer(String server, String relativePath, String body, CedarRequestContext c)
      throws CedarException {
    ServerConfig serverConfig = configFor(server);
    if (serverConfig == null) {
      return CedarResponse.notFound()
          .message("Server can not be found by name, or has no application base URL configured")
          .parameter("server", server)
          .build();
    }
    return render(server, ProxyUtil.proxyPost(serverConfig.getBase() + relativePath, c, body));
  }

  private ServerConfig configFor(String server) {
    ServerName serverName = ServerName.forName(server);
    ServerConfig serverConfig = serverName == null ? null : cedarConfig.getServers().get(serverName);
    return serverConfig == null || serverConfig.getBase() == null ? null : serverConfig;
  }

  /** Pass the other server's status and body through unchanged, whatever they were. */
  private Response render(String server, ClassicHttpResponse proxyResponse) {
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
          .message("Error while reading the response of " + server)
          .exception(e)
          .build();
    }
  }
}
