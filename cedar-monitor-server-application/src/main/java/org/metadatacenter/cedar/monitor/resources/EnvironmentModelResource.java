package org.metadatacenter.cedar.monitor.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarConfigEnvironmentDescriptor;
import org.metadatacenter.config.environment.CedarEnvironmentSource;
import org.metadatacenter.config.environment.CedarEnvironmentVariable;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * The parts of CEDAR's environment that no running service can be asked about.
 *
 * <p>The per-service environment report answers by asking each service what it resolved. Most of
 * what reads a CEDAR variable cannot be asked anything: the AngularJS frontends read theirs in a
 * gulp build that finished long ago, Keycloak reads its own from {@code standalone.xml}, and the
 * admin and caDSR tools are processes that exist only while a command runs. Leaving them off the
 * page did not make them stop consuming variables — it made a matrix that looked complete while
 * covering fifteen of the twenty-two components the descriptor knows about.
 *
 * <p>So there are two routes here, and they answer different questions from the per-service one.
 * {@code /declarations} is the static table: which components declare which variables, with no value
 * attached, because there is nothing running to hold one. {@code /unmodelled} is the opposite gap —
 * variables the host sets that {@link CedarEnvironmentVariable} has never heard of, and which are
 * therefore invisible to every other view including the boot-time sandbox report.
 */
@Path("/environment-model")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Environment model")
@SecurityRequirement(name = "api_key")
public class EnvironmentModelResource extends AbstractMonitorResource {

  /** Names starting with this are CEDAR's to account for; anything else on the host is not. */
  private static final String CEDAR_PREFIX = "CEDAR_";

  public EnvironmentModelResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  private void authorize() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);
  }

  /**
   * Which variables each component declares, for every component that is not a running server.
   *
   * <p>Names only. A declaration is not a value and must not be shown as one: the page can say that
   * the production frontend build reads {@code CEDAR_VERSION}, and cannot say what it read, because
   * that build ran on someone's machine at some point in the past and left nothing behind to ask.
   */
  @GET
  @Timed
  @Path("/declarations")
  @Operation(summary = "Get the declared variables of every non-server component",
      description = "The static declaration table for the frontends, the admin and caDSR tools, the Keycloak "
          + "event listener and the shell utilities. Names only — these components are builds and scripts, "
          + "not processes that can be asked what they resolved.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Declared variable names per component"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission")
  })
  public Response declarations() throws CedarException {
    authorize();

    Map<String, String> hostEnvironment = CedarEnvironmentSource.getAll();
    List<Map<String, Object>> components = new ArrayList<>();

    for (SystemComponent component : SystemComponent.values()) {
      // Servers answer for themselves on /server-report/{server}/environment, with real values.
      // ALL is the descriptor's catch-all rather than something that runs.
      if (component.getServerName() != null || component == SystemComponent.ALL) {
        continue;
      }
      Set<CedarEnvironmentVariable> declared = CedarConfigEnvironmentDescriptor.getVariableNamesFor(component);
      List<Map<String, Object>> variables = new ArrayList<>();
      if (declared != null) {
        for (CedarEnvironmentVariable variable : declared) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("name", variable.getName());
          entry.put("secure", variable.isSecure());
          // Whether the host could supply it. This is the only fact about a value that can honestly be
          // reported for a component that is not running.
          entry.put("presentInHostEnvironment", hostEnvironment.get(variable.getName()) != null);
          variables.add(entry);
        }
      }
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("component", component.getStringValue());
      report.put("variables", variables);
      components.add(report);
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("note", "Declarations only. These components are builds and scripts, so no resolved value exists to report.");
    report.put("components", components);
    return Response.ok(report).build();
  }

  /**
   * Variables the host sets that the configuration model does not know about.
   *
   * <p>Everything else on the environment page is driven by {@link CedarEnvironmentVariable}, so a
   * variable missing from that enum is missing from the boot-time sandbox report, from every
   * service's environment report, and from this page's matrix — while still being read, because a
   * consumer that calls {@code System.getenv} directly does not consult the enum. The log
   * aggregation jobs in the worker do exactly that, which is how a variable that switches
   * aggregation on and off came to be invisible to the tool built to show the environment.
   *
   * <p>A name here is not an unused variable. Most of these are read by the infrastructure layer -
   * nginx templates, docker-compose, the Mongo and Keycloak init scripts, the shell profiles - none of
   * which goes through the Java configuration model or has any reason to. What the list is for is the
   * two kinds of exception hiding among them: a variable Java reads through {@code System.getenv},
   * which belongs in the model and is invisible until it is added, and a variable that outlived
   * whatever used to read it. Telling those apart from an ordinary infrastructure variable means going
   * and looking; this route can only say which names the model does not account for.
   *
   * <p>Values are withheld. These names are by definition outside the model, so nothing has declared
   * whether they are secret, and a value whose secrecy is unknown is treated as secret.
   */
  @GET
  @Timed
  @Path("/unmodelled")
  @Operation(summary = "Get CEDAR_* variables set on this host that the configuration model does not define",
      description = "Names only, never values: a variable outside CedarEnvironmentVariable carries no "
          + "secrecy flag, so its value is withheld. A name here is either a consumer reading the "
          + "environment directly, or a variable that has fallen out of use.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Names of unmodelled CEDAR_* variables"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "The caller lacks the monitor read permission")
  })
  public Response unmodelled() throws CedarException {
    authorize();

    Set<String> modelled = new java.util.HashSet<>();
    for (CedarEnvironmentVariable variable : CedarEnvironmentVariable.values()) {
      modelled.add(variable.getName());
    }

    // Sorted, because this is read as a list of names to act on rather than in any meaningful order.
    Map<String, Object> found = new TreeMap<>();
    for (Map.Entry<String, String> entry : CedarEnvironmentSource.getAll().entrySet()) {
      String name = entry.getKey();
      if (name.startsWith(CEDAR_PREFIX) && !modelled.contains(name)) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("hasValue", entry.getValue() != null && !entry.getValue().isEmpty());
        found.put(name, detail);
      }
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scope", "The host this monitoring server runs on");
    report.put("note", "Set on the host and absent from CedarEnvironmentVariable, so no other view reports "
        + "them. This does not mean they are unused: most are read by the infrastructure layer - nginx "
        + "config generation, docker-compose, container init scripts, the shell profiles - which does not "
        + "pass through the Java configuration model. The exceptions worth finding are a variable Java "
        + "reads through System.getenv, and one that outlived whatever used to read it. "
        + "Values are withheld: nothing has declared whether these are secret.");
    report.put("count", found.size());
    report.put("variables", found);
    return Response.ok(report).build();
  }
}
