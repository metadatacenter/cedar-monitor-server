package org.metadatacenter.cedar.monitor;

import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.hibernate.UnitOfWorkAwareProxyFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.server.logging.AppLoggerExecutorService;
import org.metadatacenter.server.logging.agg.LogAggregationService;
import org.metadatacenter.server.logging.agg.RollupAccumulators;
import org.metadatacenter.server.logging.dao.ApplicationCypherLogDAO;
import org.metadatacenter.server.logging.dao.ApplicationRequestLogDAO;
import org.metadatacenter.server.logging.dao.agg.AggregationRollupDAO;
import org.metadatacenter.server.logging.dao.agg.LogAggregationStateDAO;
import org.metadatacenter.server.logging.model.*;

import java.lang.reflect.Field;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import org.metadatacenter.util.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.server.logging.dbmodel.ApplicationCypherLog;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Persistence and aggregation regressions inherited by the existing monitor database fixture. */
abstract class LogPersistenceCases {
  private static final Instant AT = Instant.parse("2026-01-15T12:00:00Z");
  // A broad range avoids JDBC/JVM timezone differences; assertions concern totals, not hour labels.
  private static final Instant FROM = AT.minus(Duration.ofDays(1));
  private static final Instant TO = AT.plus(Duration.ofDays(1));
  private SessionFactory sessions;
  private AppLoggerExecutorService logger;

  protected abstract HibernateBundle<?> hibernate();
  protected abstract Connection connect() throws SQLException;

  @BeforeEach void persistenceServices() {
    sessions = hibernate().getSessionFactory();
    logger = new UnitOfWorkAwareProxyFactory(hibernate()).create(AppLoggerExecutorService.class,
        new Class[]{ApplicationRequestLogDAO.class, ApplicationCypherLogDAO.class},
        new Object[]{new ApplicationRequestLogDAO(sessions), new ApplicationCypherLogDAO(sessions)});
  }

  @Test void replayCommittedRequestStartMustSucceedAndPreserveCompletion() throws Exception {
    completeRequest();
    assertDoesNotThrow(() -> logger.handleLog(event(AppLogType.REQUEST_FILTER, AppLogSubType.START, 0)));
    assertEquals(1, scalar("SELECT COUNT(*) FROM log_request"));
    assertEquals(3_000_000_000L, scalar("SELECT handlerDuration FROM log_request"));
    assertEquals(503, scalar("SELECT status FROM log_request"));
  }

  @Test void replayCommittedCypherMustNotInflateUsage() throws Exception {
    AppLogMessage message = cypher();
    logger.handleLog(message);
    logger.handleLog(message); // same persisted message after a lost queue acknowledgement
    aggregate(service(new AggregationRollupDAO(sessions)), false);
    assertAll(
        () -> assertEquals(1, scalar("SELECT COUNT(*) FROM log_cypher")),
        () -> assertEquals(1, scalar("SELECT SUM(execCount) FROM agg_cypher_hourly")));
  }

  @ParameterizedTest(name = "arrival order {0}")
  @MethodSource("requestArrivalOrders")
  void requestArrivalOrderMustPreserveTimingAndStatus(String order) throws Exception {
    for (char stage : order.toCharArray()) logger.handleLog(requestStage(stage));
    assertAll(
        () -> assertEquals(1, scalar("SELECT COUNT(*) FROM log_request")),
        () -> assertEquals(503, scalar("SELECT COALESCE(status,0) FROM log_request")),
        () -> assertEquals(3_000_000_000L, scalar("SELECT handlerDuration FROM log_request")),
        () -> assertEquals(1_000_000_000L, scalar("SELECT preHandlerDuration FROM log_request")),
        () -> assertEquals(1, scalar("SELECT COUNT(*) FROM log_request WHERE errorPack IS NOT NULL "
            + "AND userId = 'probe-user' AND className = 'ProbeResource' AND httpMethod = 'GET'")));
    aggregate(service(new AggregationRollupDAO(sessions)), true);
    assertEquals(1, scalar("SELECT SUM(errorCount) FROM agg_request_hourly"));
  }

  static Stream<String> requestArrivalOrders() {
    return Stream.of("SHE", "SEH", "HSE", "HES", "ESH", "EHS")
        .flatMap(order -> IntStream.rangeClosed(0, 3)
            .mapToObj(position -> order.substring(0, position) + "X" + order.substring(position)));
  }

  @Test void lateCompletionMustReachRollupsBeforePruning() throws Exception {
    logger.handleLog(requestStage('S'));
    logger.handleLog(requestStage('H'));
    LogAggregationService agg = service(new AggregationRollupDAO(sessions));
    aggregate(agg, true);
    logger.handleLog(requestStage('E'));
    aggregate(agg, true);
    assertEquals(3_000_000_000L, scalar("SELECT handlerDuration FROM log_request"), "raw completion arrived");
    long duration = scalar("SELECT SUM(sumHandlerNanos) FROM agg_request_hourly");
    long errors = scalar("SELECT SUM(errorCount) FROM agg_request_hourly");
    agg.pruneRequests(TO, 10);
    assertAll(
        () -> assertEquals(3_000_000_000L, duration, "completed duration must reach rollup"),
        () -> assertEquals(1, errors, "503 must reach error count"),
        () -> assertEquals(1, scalar("SELECT SUM(reqCount) FROM agg_request_hourly")),
        () -> assertEquals(0, scalar("SELECT COUNT(*) FROM log_request")));
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void overlappingAggregatorsMustCountOnce(boolean request) throws Exception {
    seed(request);
    CountDownLatch folded = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AggregationRollupDAO coordinated = new AggregationRollupDAO(sessions) {
      @Override public List<Long> foldLiveRequestBatch(RollupAccumulators acc, Instant from, Instant to, int limit) {
        List<Long> result = super.foldLiveRequestBatch(acc, from, to, limit);
        pause(); return result;
      }
      @Override public List<Long> foldLiveCypherBatch(RollupAccumulators acc, Instant from, Instant to, int limit) {
        List<Long> result = super.foldLiveCypherBatch(acc, from, to, limit);
        pause(); return result;
      }
      private void pause() { folded.countDown(); await(release); }
    };
    LogAggregationService agg = service(coordinated);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<Long> first = workers.submit(() -> aggregate(agg, request));
      Future<Long> second = workers.submit(() -> aggregate(agg, request));
      assertTrue(folded.await(10, TimeUnit.SECONDS), "both transactions must select before either flushes");
      release.countDown();
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
      assertEquals(1, scalar("SELECT SUM(" + countColumn(request) + ") FROM " + rollupTable(request)));
      assertEquals(1, scalar("SELECT COUNT(*) FROM " + rawTable(request) + " WHERE aggregatedAt IS NOT NULL"));
    } finally {
      release.countDown(); workers.shutdownNow();
      assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void delayedLowerIdCommitMustNotBeMarkedWithoutBeingCounted(boolean request) throws Exception {
    try (Connection delayed = connect(); Statement insert = delayed.createStatement()) {
      delayed.setAutoCommit(false);
      // Reserve the lower auto-increment ID, but keep the row invisible to the batch SELECT.
      String columns = request ? "requestTime, handlerDuration, httpMethod, preHandlerDuration, startTime, endTime, status" : "logTime, duration, operation";
      insert.executeUpdate("INSERT INTO " + rawTable(request) + " (" + columns
          + ", systemComponentName, lineNumber) VALUES ('2026-01-15 12:00:00', 1000, 'GET'"
          + (request ? ", 0, '2026-01-15 12:00:00', '2026-01-15 12:00:01', 200" : "") + ", 'RESOURCE', 0)");
      seed(request); // a later ID commits first and is the only row visible to SELECT
      AggregationRollupDAO interleaved = new AggregationRollupDAO(sessions) {
        @Override public List<Long> foldLiveRequestBatch(RollupAccumulators acc, Instant from, Instant to, int limit) {
          List<Long> result = super.foldLiveRequestBatch(acc, from, to, limit);
          commitDelayed(result); return result;
        }
        @Override public List<Long> foldLiveCypherBatch(RollupAccumulators acc, Instant from, Instant to, int limit) {
          List<Long> result = super.foldLiveCypherBatch(acc, from, to, limit);
          commitDelayed(result); return result;
        }
        private void commitDelayed(List<Long> result) {
          assertEquals(1, result.size(), "one row must still be invisible during SELECT");
          try { delayed.commit(); } catch (SQLException e) { throw new AssertionError(e); }
        }
      };
      assertEquals(1, aggregate(service(interleaved), request));
      LogAggregationService ordinary = service(new AggregationRollupDAO(sessions));
      long remaining = aggregate(ordinary, request);
      long counted = scalar("SELECT SUM(" + countColumn(request) + ") FROM " + rollupTable(request));
      long marked = scalar("SELECT COUNT(*) FROM " + rawTable(request) + " WHERE aggregatedAt IS NOT NULL");
      int pruned = prune(ordinary, request, TO);
      assertAll(
          () -> assertEquals(1, remaining, "the delayed row must be picked up by the next batch"),
          () -> assertEquals(2, counted, "both committed rows must be counted before removal"),
          () -> assertEquals(2, marked),
          () -> assertEquals(2, pruned));
    }
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void failureBetweenFlushAndMarkMustRollBackAndRetryOnce(boolean request) throws Exception {
    seed(request);
    AggregationRollupDAO failing = new AggregationRollupDAO(sessions) {
      @Override public int markLiveRequestRows(List<Long> ids, Instant now) {
        throw new IllegalStateException("injected after rollup flush");
      }
      @Override public int markLiveCypherRows(List<Long> ids, Instant now) {
        throw new IllegalStateException("injected after rollup flush");
      }
    };
    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> aggregate(service(failing), request));
    assertEquals("injected after rollup flush", failure.getMessage());
    assertEquals(0, scalar("SELECT COUNT(*) FROM " + rollupTable(request)));
    assertEquals(0, scalar("SELECT COUNT(*) FROM " + rawTable(request) + " WHERE aggregatedAt IS NOT NULL"));
    LogAggregationService agg = service(new AggregationRollupDAO(sessions));
    assertEquals(1, aggregate(agg, request));
    assertEquals(0, aggregate(agg, request));
    assertEquals(1, scalar("SELECT SUM(" + countColumn(request) + ") FROM " + rollupTable(request)));
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void pruningMustLeaveUnaggregatedRowsAndRespectCutoff(boolean request) throws Exception {
    seed(request);
    LogAggregationService agg = service(new AggregationRollupDAO(sessions));
    assertEquals(0, prune(agg, request, TO));
    assertEquals(1, scalar("SELECT COUNT(*) FROM " + rawTable(request)));
    aggregate(agg, request);
    assertEquals(0, prune(agg, request, FROM));
    assertEquals(1, prune(agg, request, TO));
    assertEquals(0, prune(agg, request, TO));
    assertEquals(1, scalar("SELECT SUM(" + countColumn(request) + ") FROM " + rollupTable(request)));
  }

  @ParameterizedTest @ValueSource(strings = {"S", "H", "E", "SH", "SE", "HE"})
  void incompleteRequestsStayRawAndDoNotKeepTheSchedulerBusy(String stages) throws Exception {
    for (char c : stages.toCharArray()) logger.handleLog(requestStage(c));
    LogAggregationService agg = service(new AggregationRollupDAO(sessions));
    assertEquals(0, aggregate(agg, true));
    assertNull(agg.earliestUnaggregatedRequestTime());
    assertEquals(0, agg.pruneRequests(TO, 10));
    assertEquals(1, scalar("SELECT COUNT(*) FROM log_request"));
    completeRequest();
    assertNotNull(agg.earliestUnaggregatedRequestTime());
    assertEquals(1, aggregate(agg, true));
    assertEquals(3_000_000_000L, scalar("SELECT SUM(sumHandlerNanos) FROM agg_request_hourly"));
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void replayAfterPruningCannotRecreateRawRowsOrInflateRollups(boolean request) throws Exception {
    AppLogMessage query = cypher();
    if (request) completeRequest(); else logger.handleLog(query);
    LogAggregationService agg = service(new AggregationRollupDAO(sessions));
    aggregate(agg, request);
    assertEquals(1, prune(agg, request, TO));
    if (request) {
      completeRequest();
      logger.handleLog(event(AppLogType.RESPONSE_EXCEPTION, AppLogSubType.START, 3)
          .param(AppLogParam.EXCEPTION, Map.of("message", "late")));
    } else logger.handleLog(query);
    assertEquals(0, scalar("SELECT COUNT(*) FROM " + rawTable(request)));
    assertEquals(0, aggregate(agg, request));
    assertEquals(1, scalar("SELECT SUM(" + countColumn(request) + ") FROM " + rollupTable(request)));
    assertEquals(1, scalar("SELECT COUNT(*) FROM log_event_receipt"), "one compact durable fence");
  }

  @Test void legacyQueryReplayHasStableIdentityButDistinctExecutionsStillCount() throws Exception {
    ObjectNode legacy = JsonMapper.STRICT_MAPPER.valueToTree(cypher());
    legacy.remove("eventId");
    AppLogMessage first = JsonMapper.STRICT_MAPPER.treeToValue(legacy, AppLogMessage.class);
    AppLogMessage replay = JsonMapper.STRICT_MAPPER.readValue(legacy.toString(), AppLogMessage.class);
    assertNull(first.getEventId());
    logger.handleLog(first); logger.handleLog(replay);
    assertEquals(1, scalar("SELECT COUNT(*) FROM log_cypher"));
    logger.handleLog(cypher()); logger.handleLog(cypher());
    assertEquals(3, scalar("SELECT COUNT(*) FROM log_cypher"));
  }

  @Test void failedPersistenceRollsBackItsReceiptAndCanBeRetried() throws Exception {
    AppLogMessage message = cypher();
    ApplicationCypherLogDAO failing = new ApplicationCypherLogDAO(sessions) {
      @Override public Long create(ApplicationCypherLog row) {
        super.create(row);
        throw new IllegalStateException("injected after raw insert");
      }
    };
    AppLoggerExecutorService broken = new UnitOfWorkAwareProxyFactory(hibernate()).create(AppLoggerExecutorService.class,
        new Class[]{ApplicationRequestLogDAO.class, ApplicationCypherLogDAO.class},
        new Object[]{new ApplicationRequestLogDAO(sessions), failing});
    assertEquals("injected after raw insert", assertThrows(IllegalStateException.class,
        () -> broken.handleLog(message)).getMessage());
    assertEquals(0, scalar("SELECT COUNT(*) FROM log_event_receipt"));
    assertEquals(0, scalar("SELECT COUNT(*) FROM log_cypher"));
    logger.handleLog(message); logger.handleLog(message);
    assertEquals(1, scalar("SELECT COUNT(*) FROM log_cypher"));
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void concurrentReplaysAndFragmentsAreSerialized(boolean request) throws Exception {
    AppLogMessage query = cypher();
    ExecutorService workers = Executors.newFixedThreadPool(3);
    CountDownLatch ready = new CountDownLatch(3), go = new CountDownLatch(1);
    try {
      java.util.ArrayList<Future<?>> futures = new java.util.ArrayList<>();
      for (char phase : "EHS".toCharArray()) {
        futures.add(workers.submit(() -> {
          ready.countDown(); await(go);
          logger.handleLog(request ? requestStage(phase) : query);
        }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
      for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
      assertEquals(1, scalar("SELECT COUNT(*) FROM " + rawTable(request)));
      assertEquals(1, scalar("SELECT COUNT(*) FROM log_event_receipt"));
      if (request) {
        assertEquals(3_000_000_000L, scalar("SELECT handlerDuration FROM log_request"));
        assertEquals(503, scalar("SELECT status FROM log_request"));
      }
    } finally {
      go.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
    }
  }

  @Test void legacyIncompleteAggregateIsProtectedFromPruningAndSilentMutation() throws Exception {
    logger.handleLog(requestStage('S'));
    try (Connection c = connect(); Statement statement = c.createStatement()) {
      statement.executeUpdate("UPDATE log_request SET aggregatedAt = '2026-01-20 00:00:00'");
    }
    LogAggregationService agg = service(new AggregationRollupDAO(sessions));
    assertEquals(0, agg.pruneRequests(TO, 10));
    assertThrows(IllegalStateException.class, () -> logger.handleLog(requestStage('E')));
    assertEquals(1, scalar("SELECT COUNT(*) FROM log_request WHERE endTime IS NULL"));
  }

  private void completeRequest() { for (char c : "SHE".toCharArray()) logger.handleLog(requestStage(c)); }
  private void seed(boolean request) { if (request) completeRequest(); else logger.handleLog(cypher()); }
  private AppLogMessage requestStage(char c) {
    return switch (c) {
      case 'S' -> event(AppLogType.REQUEST_FILTER, AppLogSubType.START, 0);
      case 'H' -> event(AppLogType.REQUEST_HANDLER, AppLogSubType.START, 1);
      case 'E' -> event(AppLogType.REQUEST_FILTER, AppLogSubType.END, 4).param(AppLogParam.STATUS, 503);
      case 'X' -> event(AppLogType.RESPONSE_EXCEPTION, AppLogSubType.START, 3).param(AppLogParam.EXCEPTION, Map.of("message", "failed"));
      default -> throw new IllegalArgumentException();
    };
  }
  private AppLogMessage event(AppLogType type, AppLogSubType subType, long seconds) {
    AppLogMessage message = new AppLogMessage(SystemComponent.SERVER_RESOURCE, type, subType, "global", "local");
    try {
      Field time = AppLogMessage.class.getDeclaredField("logTime"); time.setAccessible(true);
      time.set(message, AT.plusSeconds(seconds));
    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    return message.param(AppLogParam.HTTP_METHOD, "GET").param(AppLogParam.PATH, "/probe")
        .param(AppLogParam.CLASS_NAME, "ProbeResource").param(AppLogParam.METHOD_NAME, "get")
        .param(AppLogParam.USER_ID, "probe-user").param(AppLogParam.AUTH_SOURCE, "api_key")
        .param(AppLogParam.QUERY_PARAMETERS, Map.of());
  }
  private AppLogMessage cypher() {
    AppLogMessage message = event(AppLogType.CYPHER_QUERY, AppLogSubType.FULL, 0)
        .param(AppLogParam.OPERATION, "read").param(AppLogParam.RUNNABLE_QUERY_HASH, "hash")
        .param(AppLogParam.RUNNABLE_QUERY, "RETURN 1").param(AppLogParam.INTERPOLATED_QUERY, "RETURN 1");
    message.setDuration(Duration.ofMillis(7));
    message.setStartTime(AT); message.setEndTime(AT.plusMillis(7));
    return message;
  }
  private LogAggregationService service(AggregationRollupDAO dao) {
    return new UnitOfWorkAwareProxyFactory(hibernate()).create(LogAggregationService.class,
        new Class[]{AggregationRollupDAO.class, LogAggregationStateDAO.class},
        new Object[]{dao, new LogAggregationStateDAO(sessions)});
  }
  private long aggregate(LogAggregationService service, boolean request) {
    return request ? service.aggregateLiveRequestBatch(FROM, TO, 10) : service.aggregateLiveCypherBatch(FROM, TO, 10);
  }
  private int prune(LogAggregationService service, boolean request, Instant cutoff) {
    return request ? service.pruneRequests(cutoff, 10) : service.pruneCypher(cutoff, 10);
  }
  private String rawTable(boolean request) { return request ? "log_request" : "log_cypher"; }
  private String rollupTable(boolean request) { return request ? "agg_request_hourly" : "agg_cypher_hourly"; }
  private String countColumn(boolean request) { return request ? "reqCount" : "execCount"; }
  private long scalar(String query) throws SQLException {
    try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
      assertTrue(rs.next()); return rs.getLong(1);
    }
  }
  private void await(CountDownLatch latch) {
    try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("coordination timeout"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
  }
}
