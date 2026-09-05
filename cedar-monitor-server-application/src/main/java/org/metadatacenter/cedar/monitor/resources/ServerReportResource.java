package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static org.metadatacenter.constant.CedarPathParameters.PP_SERVER;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * One named server's own account of itself, read across the network.
 *
 * <p>Each route reads the matching route on the named server and hands back what it said. The
 * fan-out is left to the caller, exactly as the health-check page already does it: fifteen small
 * parallel requests from the browser return the whole matrix in the time of the slowest server, and
 * one server being down leaves fourteen cells populated instead of failing the page. Fanning out
 * here would have serialized fifteen network calls behind one request and made the slowest server
 * the speed of every page.
 *
 * <p>What is behind these routes is documented on {@code CedarServerReportResource} and
 * {@code CedarServerInsightReportResource} in the shared microservice library. Secrets are masked
 * there, before they cross this hop.
 */
@Path("/server-report")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Server report")
@SecurityRequirement(name = "api_key")
public class ServerReportResource extends AbstractMonitorResource {

  /** The routes on the other server, relative to its application base URL. */
  private static final String ENVIRONMENT_PATH = "server-report/environment";
  private static final String CONFIGURATION_PATH = "server-report/configuration";
  private static final String BUILD_PATH = "server-report/build";
  private static final String INSIGHT_PATH = "insight/full";

  public ServerReportResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  private CedarRequestContext authorized() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);
    return c;
  }

  @GET
  @Timed
  @Path("/{server}/environment")
  @Operation(summary = "Get a server's environment variable report",
      description = "The environment variables the named server resolved its configuration from, with "
          + "secret values masked and undeclared values withheld. Read one server per call; the "
          + "monitoring UI reads all of them in parallel to build the drift matrix.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The named server's environment report"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "No CEDAR server answers to this name"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The named server could not be read")
  })
  public Response environment(
      @Parameter(description = "CEDAR server name, as the configuration spells it. Examples: "
          + "artifact, terminology, resource.", required = true)
      @PathParam(PP_SERVER) String server) throws CedarException {
    return proxyToServer(server, ENVIRONMENT_PATH, authorized());
  }

  @GET
  @Timed
  @Path("/{server}/configuration")
  @Operation(summary = "Get a server's resolved configuration",
      description = "cedar-main.yml as the named server resolved it, with secrets masked. Placeholders "
          + "that could not be resolved are left as the literal ${NAME}.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The named server's resolved configuration"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "No CEDAR server answers to this name"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The named server could not be read")
  })
  public Response configuration(
      @Parameter(description = "CEDAR server name, as the configuration spells it.", required = true)
      @PathParam(PP_SERVER) String server) throws CedarException {
    return proxyToServer(server, CONFIGURATION_PATH, authorized());
  }

  @GET
  @Timed
  @Path("/{server}/build")
  @Operation(summary = "Get a server's version and build report",
      description = "The version the named server's environment declares, together with the path and "
          + "modification time of the artifact its JVM actually loaded, its uptime and its host.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The named server's build report"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "No CEDAR server answers to this name"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The named server could not be read")
  })
  public Response build(
      @Parameter(description = "CEDAR server name, as the configuration spells it.", required = true)
      @PathParam(PP_SERVER) String server) throws CedarException {
    return proxyToServer(server, BUILD_PATH, authorized());
  }

  @GET
  @Timed
  @Path("/{server}/insight")
  @Operation(summary = "Get a server's JVM report",
      description = "Heap and non-heap memory, thread counts, garbage collection counts and host load "
          + "for the named server. Thread stack traces are deliberately not included: the full dump runs "
          + "to roughly half a megabyte per server and is served by the server's own /insight/thread-details.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The named server's JVM report"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "No CEDAR server answers to this name"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The named server could not be read")
  })
  public Response insight(
      @Parameter(description = "CEDAR server name, as the configuration spells it.", required = true)
      @PathParam(PP_SERVER) String server) throws CedarException {
    return proxyToServer(server, INSIGHT_PATH, authorized());
  }
}
