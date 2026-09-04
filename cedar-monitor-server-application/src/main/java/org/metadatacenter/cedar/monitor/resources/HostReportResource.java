package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.cedar.monitor.host.HostInspector;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariable;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * The box this monitoring server runs on: what is checked out on it, and what is filling its disk.
 *
 * <p>Everything else the Monitor serves is asked of the other services over the network. These two
 * routes cannot be — a repository checkout and a log directory belong to a host, not to a service —
 * so they report the Monitor's own host and say so. In production that host is the application
 * server and the report covers all of CEDAR; under Docker it is the monitoring container alone. The
 * {@code scope} field in each response states which, so a reader is never guessing.
 */
@Path("/host")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Host")
@SecurityRequirement(name = "api_key")
public class HostReportResource extends AbstractMonitorResource {

  /** Where cedar-services.sh writes every service's log, relative to CEDAR_HOME. */
  private static final String LOG_DIRECTORY = "log";

  public HostReportResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  private void authorize() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);
  }

  @GET
  @Timed
  @Path("/git")
  @Operation(summary = "Get the git state of every CEDAR repository on this host",
      description = "Branch, commit, commit date, upstream distance and uncommitted tracked-file count for "
          + "each repository under CEDAR_HOME. A non-zero uncommitted count is a hot-patch applied "
          + "directly on the box, which the next pull would overwrite.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "One entry per repository"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission")
  })
  public Response git() throws CedarException {
    authorize();

    String cedarHome = cedarHome();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scope", "The host this monitoring server runs on");
    report.put("cedarHome", cedarHome);
    report.put("repositories", HostInspector.repositories(cedarHome));
    return Response.ok(report).build();
  }

  @GET
  @Timed
  @Path("/disk")
  @Operation(summary = "Get disk usage and the largest log files on this host",
      description = "Room left on the filesystems CEDAR writes to, plus the largest files under "
          + "CEDAR_HOME/log with their sizes and ages. A large file that is still being written to is a "
          + "log nothing is rotating.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Filesystem usage and log file sizes"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission")
  })
  public Response disk() throws CedarException {
    authorize();

    String cedarHome = cedarHome();
    String logDirectory = cedarHome == null ? null : new File(cedarHome, LOG_DIRECTORY).getAbsolutePath();

    List<String> paths = new ArrayList<>();
    paths.add("/");
    if (cedarHome != null) {
      paths.add(cedarHome);
    }
    if (logDirectory != null) {
      paths.add(logDirectory);
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scope", "The host this monitoring server runs on");
    report.put("cedarHome", cedarHome);
    report.put("filesystems", HostInspector.filesystems(paths));
    report.put("logs", HostInspector.logFiles(logDirectory));
    return Response.ok(report).build();
  }

  /** CEDAR_HOME as this service resolved it, or null where the sandbox does not carry it. */
  private String cedarHome() {
    return CedarConfig.getInstanceEnvironment().get(CedarEnvironmentVariable.CEDAR_HOME.getName());
  }
}
