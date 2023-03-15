package org.metadatacenter.cedar.internals.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarFQResourceId;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.CedarResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.constant.CedarPathParameters.PP_INPUT;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource extends AbstractInternalsResource {

  private static final Logger log = LoggerFactory.getLogger(CommandResource.class);
  public static final String RESOURCE_ID_SOURCE = "resourceIdSource";
  public static final String STACK_TRACE = "stackTrace";
  public static final String EXCEPTION_MESSAGE = "exceptionMessage";
  public static final String ERROR_PHASE = "errorPhase";
  public static final String REQUEST = "request";
  public static final String INPUT = "input";
  public static final String SANITIZED_INPUT = "sanitizedInput";
  public static final String RESOURCE_ID = "resourceId";
  public static final String RESOURCE_ID_STRING = "resourceIdString";
  public static final String SUCCESS = "success";
  public static final String PATH = "path";
  public static final String QUERY_STRING = "queryString";
  public static final String QUERY_PARAMETERS = "queryParameters";

  private static UserService userService;
  private static NodeSearchingService nodeSearchingService;

  public CommandResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(UserService userService, NodeSearchingService nodeSearchingService) {
    CommandResource.userService = userService;
    CommandResource.nodeSearchingService = nodeSearchingService;
  }

  @GET
  @Timed
  @Path("/resource-id-lookup/{input}")
  public Response lookUpResource(@PathParam(PP_INPUT) String input) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.INTERNALS_READ);

    Map<String, Object> response = new HashMap<>();

    Map<String, Object> request = new HashMap<>();
    response.put(REQUEST, request);

    request.put(INPUT, input);

    String sanitizedInput = input != null ? input.trim() : "";
    request.put(SANITIZED_INPUT, sanitizedInput);

    CedarFQResourceId resourceId = null;
    if (resourceId == null) {
      String path = buildPath(sanitizedInput, request, response);
      resourceId = detectResourceIdInPath(path, response);
      if (resourceId != null) {
        response.put(RESOURCE_ID_SOURCE, PATH);
      }
    }
    if (resourceId == null) {
      resourceId = detectResourceIdInInput(sanitizedInput, response);
      if (resourceId != null) {
        response.put(RESOURCE_ID_SOURCE, "inputString");
      }
    }
    if (resourceId == null) {
      List<NameValuePair> params = buildParams(sanitizedInput, request, response);
      resourceId = detectResourceIdInQuery(params, response);
      if (resourceId != null) {
        response.put(RESOURCE_ID_SOURCE, QUERY_STRING);
      }
    }

    response.put(RESOURCE_ID, resourceId);
    response.put(RESOURCE_ID_STRING, resourceId == null ? "" : resourceId.toString());

    if (resourceId != null) {
      response.put(SUCCESS, true);
      response.remove(ERROR_PHASE);
    } else {
      response.put(SUCCESS, false);
    }
    return Response.ok().entity(response).build();
  }

  private String buildPath(String sanitizedInput, Map<String, Object> request, Map<String, Object> response) {
    String path = null;
    try {
      URL url = new URL(sanitizedInput);
      path = url.getPath();
      request.put(PATH, path);
      request.put(QUERY_STRING, url.getQuery());
    } catch (MalformedURLException e) {
      response.put(STACK_TRACE, e.getStackTrace());
      response.put(EXCEPTION_MESSAGE, e.getMessage());
      response.put(ERROR_PHASE, "pathParsing");
    }
    return path;
  }

  private List<NameValuePair> buildParams(String sanitizedInput, Map<String, Object> request,
                                          Map<String, Object> response) {
    List<NameValuePair> params = null;
    try {
      URI uri = new URI(sanitizedInput);
      params = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));
      request.put(QUERY_PARAMETERS, params);
    } catch (URISyntaxException e) {
      response.put(STACK_TRACE, e.getStackTrace());
      response.put(EXCEPTION_MESSAGE, e.getMessage());
      response.put(ERROR_PHASE, "paramParsing");
    }
    return params;
  }

  private CedarFQResourceId detectResourceIdInInput(String sanitizedInput, Map<String, Object> response) {
    CedarFQResourceId resourceId = CedarFQResourceId.build(sanitizedInput);
    if (resourceId == null) {
      response.put(ERROR_PHASE, "inputParsing");
    }
    return resourceId;
  }

  private CedarFQResourceId detectResourceIdInPath(String path, Map<String, Object> response) {
    if (path != null) {
      CedarFQResourceId resourceId = CedarFQResourceId.build(path);
      if (resourceId == null) {
        response.put(ERROR_PHASE, "pathParsing");
      }
      return resourceId;
    }
    return null;
  }

  private CedarFQResourceId detectResourceIdInQuery(List<NameValuePair> params, Map<String, Object> response) {
    if (params != null) {
      for (NameValuePair pair : params) {
        CedarFQResourceId resourceId = CedarFQResourceId.build(pair.getValue());
        if (resourceId != null) {
          return resourceId;
        }
      }
    }
    response.put(ERROR_PHASE, "parameterParsing");
    return null;
  }


}
