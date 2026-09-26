package org.metadatacenter.cedar.monitor.paging;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.metadatacenter.server.logging.agg.AggQueryResults.CypherStat;
import org.metadatacenter.server.logging.agg.AggQueryResults.EndpointStat;
import org.metadatacenter.server.logging.agg.AggQueryResults.UserStat;
import org.metadatacenter.server.logging.agg.LogExplorerResults.CypherRow;
import org.metadatacenter.server.logging.agg.LogExplorerResults.RequestRow;
import org.metadatacenter.util.http.PagedListResponse;

import java.util.List;

/**
 * The monitor server's listings in CEDAR's body envelope: the paging fields of
 * {@link PagedListResponse} beside the rows of one page, under a name that says what they are.
 */
public final class LogPages {

  private LogPages() {
  }

  /** A page of request rows, raw or retained. */
  @Schema(name = "RequestRowPage")
  public static final class RequestRowPage extends PagedListResponse {
    @ArraySchema(schema = @Schema(implementation = RequestRow.class))
    private final List<RequestRow> requests;

    public RequestRowPage(List<RequestRow> requests, String requestUrl, long totalCount, int limit, int offset,
                          boolean countCapped) {
      this.requests = requests;
      page(requestUrl, totalCount, limit, offset, countCapped);
    }

    public List<RequestRow> getRequests() {
      return requests;
    }
  }

  /** A page of Cypher rows, raw or retained. */
  @Schema(name = "CypherRowPage")
  public static final class CypherRowPage extends PagedListResponse {
    @ArraySchema(schema = @Schema(implementation = CypherRow.class))
    private final List<CypherRow> statements;

    public CypherRowPage(List<CypherRow> statements, String requestUrl, long totalCount, int limit, int offset,
                         boolean countCapped) {
      this.statements = statements;
      page(requestUrl, totalCount, limit, offset, countCapped);
    }

    public List<CypherRow> getStatements() {
      return statements;
    }
  }

  /** A page of endpoints by request volume. */
  @Schema(name = "EndpointStatPage")
  public static final class EndpointStatPage extends PagedListResponse {
    @ArraySchema(schema = @Schema(implementation = EndpointStat.class))
    private final List<EndpointStat> endpoints;

    public EndpointStatPage(List<EndpointStat> endpoints, String requestUrl, long totalCount, int limit,
                            int offset) {
      this.endpoints = endpoints;
      page(requestUrl, totalCount, limit, offset, false);
    }

    public List<EndpointStat> getEndpoints() {
      return endpoints;
    }
  }

  /** A page of Cypher statements by execution count. */
  @Schema(name = "CypherStatPage")
  public static final class CypherStatPage extends PagedListResponse {
    @ArraySchema(schema = @Schema(implementation = CypherStat.class))
    private final List<CypherStat> statements;

    public CypherStatPage(List<CypherStat> statements, String requestUrl, long totalCount, int limit, int offset) {
      this.statements = statements;
      page(requestUrl, totalCount, limit, offset, false);
    }

    public List<CypherStat> getStatements() {
      return statements;
    }
  }

  /** A page of callers by request volume. */
  @Schema(name = "UserStatPage")
  public static final class UserStatPage extends PagedListResponse {
    @ArraySchema(schema = @Schema(implementation = UserStat.class))
    private final List<UserStat> users;

    public UserStatPage(List<UserStat> users, String requestUrl, long totalCount, int limit, int offset) {
      this.users = users;
      page(requestUrl, totalCount, limit, offset, false);
    }

    public List<UserStat> getUsers() {
      return users;
    }
  }
}
