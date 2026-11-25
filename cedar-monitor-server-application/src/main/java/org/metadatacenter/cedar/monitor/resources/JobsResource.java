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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarError;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_KIND;
import static org.metadatacenter.constant.CedarQueryParameters.QP_LIMIT;
import static org.metadatacenter.constant.CedarQueryParameters.QP_OFFSET;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * The resource server's jobs, relayed.
 *
 * <p>The Monitor starts a search index rebuild through the resource server, which answers with a
 * Location naming the job under {@code /jobs}. The Monitor serves the same paths, so that Location
 * resolves here as well as there, and passes the resource server's status and body through
 * unchanged. The caller's own credential is forwarded, as on every relayed page.
 *
 * <p>Reading needs the monitor read permission. Whether a rebuild is running is operational fact
 * rather than privileged data, and someone who cannot start one still needs to know why search is
 * behaving oddly this afternoon.
 */
@Path("/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Jobs")
@SecurityRequirement(name = "api_key")
public class JobsResource extends AbstractMonitorResource {

  private static final String JOBS_PATH = "jobs";

  public JobsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Operation(summary = "List the resource server's jobs",
      description = "Relay the resource server's job listing: its search index rebuilds and value sets ontology "
          + "imports, newest first, so the first job of a kind is the one running now or the one that ran last. "
          + "For a rebuild, each job says how far it got: the phase, the count against its total, the breakdown "
          + "by resource type, the rate it is achieving and an estimate of when it will finish.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "One page of jobs",
          content = @Content(schema = @Schema(ref = "#/components/schemas/JobPage"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The kind is not one the resource server runs, or the limit or offset is out of range"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response listJobs(
      @Parameter(description = "Return only jobs of this kind: search-index or value-sets-import. Omit it for both.")
      @QueryParam(QP_KIND) Optional<String> kind,
      @Parameter(description = "How many jobs to return, from 1 to 500. Defaults to 100.")
      @QueryParam(QP_LIMIT) Optional<Integer> limit,
      @Parameter(description = "How many jobs to skip. Defaults to 0.")
      @QueryParam(QP_OFFSET) Optional<Integer> offset) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    // The query is passed on as it was sent, so the resource server applies its own rules to it.
    String query = uriInfo.getRequestUri().getRawQuery();
    return proxyToServer(ServerName.RESOURCE.getName(), query == null ? JOBS_PATH : JOBS_PATH + "?" + query, c);
  }

  @GET
  @Timed
  @Path("/{id}")
  @Operation(summary = "Get the status of one of the resource server's jobs",
      description = "Relay the resource server's status of one job, named by the identifier its command "
          + "returned. A rebuild the server did not survive is reported as INTERRUPTED with the progress of "
          + "its last heartbeat.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The job and its current state",
          content = @Content(schema = @Schema(ref = "#/components/schemas/JobStatus"))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "No job answers to this identifier"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getJob(
      @Parameter(description = "Job identifier, as returned when the job was queued.", required = true)
      @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    String segment = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    return proxyToServer(ServerName.RESOURCE.getName(), JOBS_PATH + "/" + segment, c);
  }
}
