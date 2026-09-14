package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarError;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * Starting a search index rebuild, and watching one.
 *
 * <p>A full rebuild takes hours, and until now the only way to start one was a shell on the
 * application server running {@code cedarat}, after which the only account of its progress was in the
 * resource server's log. Both halves of that are why this exists: somewhere to start it from, and
 * somewhere to watch it.
 *
 * <p><b>This route grants nothing.</b> Reading the Monitor needs {@code MONITOR_READ}, which is what
 * gets a user to this page at all; starting a rebuild is refused here by anyone without it, and then
 * refused again by the resource server, which is the only thing that decides. The proxy forwards the
 * caller's own credential, so the answer comes from the same {@code SEARCH_INDEX_REINDEX} check the
 * command has always required — the Monitor cannot start a rebuild on behalf of a user who could not
 * have started one themselves.
 *
 * <p>Status is readable by anyone who can read the Monitor. Whether a rebuild is running is
 * operational fact rather than privileged data, and someone who cannot start one still needs to know
 * why search is behaving oddly this afternoon.
 */
@Path("/search-index")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Search index")
@SecurityRequirement(name = "api_key")
public class SearchIndexResource extends AbstractMonitorResource {

  private static final String REGENERATE_PATH = "command/regenerate-search-index";
  private static final String STATUS_PATH = "command/index-job-status";

  /**
   * The rebuild is always forced from here.
   *
   * <p>Unforced, the command first compares every identifier in the graph against every identifier in
   * the index to decide whether a rebuild is needed — itself a long job — and then usually declines.
   * Someone who has opened this page and pressed the button has already decided.
   */
  private static final String FORCE_BODY = "{\"force\":true}";

  public SearchIndexResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/regenerate")
  @Operation(summary = "Start a full search index rebuild",
      description = "Queue a rebuild of the search index and answer with the job to poll. The rebuild "
          + "runs for as long as the repository requires — hours, on a large one — while search "
          + "continues against the existing index. Requires the search reindex permission, which the "
          + "resource server checks against the caller's own credential; the Monitor adds no authority "
          + "of its own.")
  @ApiResponses({
      @ApiResponse(responseCode = "202", description = "The rebuild was queued",
          content = @Content(schema = @Schema(ref = "#/components/schemas/IndexJobStatus"))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller may not rebuild the search index"),
      @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "A rebuild is already running"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response regenerate() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    return proxyPostToServer(ServerName.RESOURCE.getName(), REGENERATE_PATH, FORCE_BODY, c);
  }

  @GET
  @Timed
  @Path("/job-status")
  @Operation(summary = "How far the search index rebuild has got",
      description = "Report the state of each index rebuild, and for one that is running or was "
          + "running, how far it got: the phase, the count against its total, the breakdown by "
          + "resource type, the rate it is achieving and an estimate of when it will finish. A rebuild "
          + "the server did not survive is reported as INTERRUPTED with the progress of its last "
          + "heartbeat, rather than as though nothing had ever run.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The state of each index",
          content = @Content(schema = @Schema(ref = "#/components/schemas/IndexJobStatusByIndex"))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The caller lacks the monitor read permission"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response jobStatus() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);

    return proxyToServer(ServerName.RESOURCE.getName(), STATUS_PATH, c);
  }
}
