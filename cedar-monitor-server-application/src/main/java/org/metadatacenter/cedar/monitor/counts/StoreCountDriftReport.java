package org.metadatacenter.cedar.monitor.counts;

import java.util.ArrayList;
import java.util.List;

/**
 * Every cross-store count rule CEDAR has, applied to one reading of the stores.
 *
 * <p>Deliberately a pure function of numbers: it takes a snapshot and returns the verdicts, with no
 * access to Neo4j, Mongo, OpenSearch or Keycloak. The rules are the part worth testing and the part
 * that is easy to get subtly wrong — a sign flipped, an exclusion counted twice — and a test for
 * them should not need four running stores.
 *
 * @param checks          one entry per row of the counts page, in the order it lists them. Most
 *                        are verdicts; a {@link DriftStatus#NOT_CHECKED} entry is a number the page
 *                        carries for context, with the reasoning for not checking it
 * @param totalChecks     how many entries are actual checks. {@code NOT_CHECKED} is excluded, so
 *                        "n of m checks hold" counts only things that could have failed
 * @param driftingChecks  how many came back {@link DriftStatus#DRIFT}
 * @param unverifiedChecks how many could not be settled from totals alone
 * @param unavailableChecks how many had a store missing
 */
public record StoreCountDriftReport(
    List<StoreCountDrift> checks,
    int totalChecks,
    int driftingChecks,
    int unverifiedChecks,
    int unavailableChecks) {

  /**
   * The counts this report is computed from.
   *
   * <p>{@code keycloakUser} is boxed because Keycloak is the one store the counts route tolerates
   * losing: it reports the failure in {@code errorPack} and returns everything else. A null here
   * means the identity provider could not be read, not that it holds no users.
   */
  public record Snapshot(
      long neo4jUser,
      long neo4jFolder,
      long neo4jUserHomeFolder,
      long neo4jSystemFolder,
      long neo4jRegularFolder,
      long neo4jField,
      long neo4jElement,
      long neo4jTemplate,
      long neo4jInstance,
      long mongoField,
      long mongoElement,
      long mongoTemplate,
      long mongoInstance,
      long openSearchField,
      long openSearchElement,
      long openSearchTemplate,
      long openSearchInstance,
      long openSearchFolder,
      Integer keycloakUser) {
  }

  private static final String INDEX_RULE =
      "The search index holds one document per graph node of this type. Any difference is a reindex "
          + "that did not finish, or writes made while one was promoted.";

  private static final String INDEX_DRIFT_NOTE =
      "A positive figure is documents the index holds that the graph does not, a negative one is "
          + "graph nodes missing from the index. Either way search results and the repository "
          + "disagree; rebuild the index.";

  private static final String STORE_RULE =
      "Every artifact document belongs to a graph node, and every graph node to a document. The "
          + "artifact store is written first and the graph second, so the two part company when a "
          + "create fails between them.";

  private static final String STORE_DRIFT_NOTE =
      "A positive figure is orphaned documents — stored artifacts with no graph node, unreachable "
          + "through the workspace. A negative one is worse: graph nodes whose document is gone, "
          + "which are listed in the workspace and fail when opened.";

  private static final String FOLDER_RULE =
      "Regular folders are the ones the search index holds - every graph folder that is not a user "
          + "home and not a system folder. The index is expected to hold exactly these, which is "
          + "why it legitimately holds far fewer folders than the graph does.";

  private static final String EXPANSION_RULE =
      "Every folder is regular, a user home or a system folder, and no folder is two of those, so "
          + "the three parts account for the total exactly. All four are counted separately in the "
          + "graph rather than one being derived from the others, so this catches a folder carrying "
          + "two labels or a kind of folder nothing here knows about.";

  private static final String EXPANSION_DRIFT_NOTE =
      "The three parts do not add up to the folder total. Either a folder carries two of the "
          + "labels, or there is a kind of folder these three do not cover - in which case the "
          + "index comparison on the parts is reading an incomplete picture.";

  private static final String HOME_FOLDER_RULE =
      "Every CEDAR user has exactly one home folder. The user node and its home folder are created "
          + "by the same Keycloak login callback, one immediately after the other, so the two "
          + "counts are expected to be equal.";

  private static final String HOME_FOLDER_DRIFT_NOTE =
      "A negative figure is users with no home folder - a login callback that stopped between "
          + "creating the user and creating the folder; those users have no workspace to open. A "
          + "positive one is home folders whose user is gone.";

  private static final String SYSTEM_FOLDER_RULE =
      "The folders CEDAR creates for itself rather than for a user: the repository root and /Users. "
          + "Reported here, and deliberately not checked against a fixed number - a check against "
          + "the literal two would fire the first time a third is legitimately added. It does not "
          + "need one: this count is subtracted in the Folders rule above, so a third system folder "
          + "changes what the search index is expected to hold and is caught there, by a rule that "
          + "stays true whatever the number is.";

  private static final String USER_RULE =
      "A Keycloak account becomes a CEDAR user node on its first sign-in through cedar-angular-app, "
          + "so Keycloak is expected to hold more accounts than the graph holds users. The graph "
          + "holding more is always a fault.";

  private static final String HOME_FOLDER_ROW_RULE =
      "Counted here because it is part of what the folder total is made of. It has no check of its "
          + "own: the rule about it - that there is one home folder per user - compares it with the "
          + "user count, so it is reported on the Users row where the other side of that comparison "
          + "is.";

  private static final StoreCountDrift.Labels STORE_LABELS = new StoreCountDrift.Labels(
      "store in step",
      d -> d > 0 ? d + " orphaned documents" : -d + " documents missing");

  private static final StoreCountDrift.Labels INDEX_LABELS = new StoreCountDrift.Labels(
      "index in step",
      d -> d > 0 ? d + " extra in index" : -d + " missing from index");

  private static final StoreCountDrift.Labels EXPANSION_LABELS = new StoreCountDrift.Labels(
      "parts add up",
      d -> d > 0 ? "parts over by " + d : "parts short by " + -d);

  private static final StoreCountDrift.Labels HOME_FOLDER_LABELS = new StoreCountDrift.Labels(
      "one home per user",
      d -> d > 0 ? d + " homes without a user" : -d + " users without a home");

  public static StoreCountDriftReport of(Snapshot s) {
    List<StoreCountDrift> checks = new ArrayList<>();

    checks.add(users(s));
    // Reported on the Users row rather than on the folder sub-row it counts, because the other side
    // of this comparison is the user count. On the folder sub-row it read as a check about folders,
    // which it is not.
    checks.add(StoreCountDrift.exact("user-home-folders", "Users", "neo4j users", s.neo4jUser(),
        "home folders", s.neo4jUserHomeFolder(), s.neo4jUser(), HOME_FOLDER_LABELS,
        HOME_FOLDER_RULE, HOME_FOLDER_DRIFT_NOTE));

    checks.add(StoreCountDrift.exact("fields-mongo", "Fields", "neo4j", s.neo4jField(),
        "mongo", s.mongoField(), s.neo4jField(), STORE_LABELS, STORE_RULE, STORE_DRIFT_NOTE));
    checks.add(StoreCountDrift.exact("elements-mongo", "Element", "neo4j", s.neo4jElement(),
        "mongo", s.mongoElement(), s.neo4jElement(), STORE_LABELS, STORE_RULE, STORE_DRIFT_NOTE));
    checks.add(StoreCountDrift.exact("templates-mongo", "Templates", "neo4j", s.neo4jTemplate(),
        "mongo", s.mongoTemplate(), s.neo4jTemplate(), STORE_LABELS, STORE_RULE, STORE_DRIFT_NOTE));
    checks.add(StoreCountDrift.exact("instances-mongo", "Instances", "neo4j", s.neo4jInstance(),
        "mongo", s.mongoInstance(), s.neo4jInstance(), STORE_LABELS, STORE_RULE, STORE_DRIFT_NOTE));

    checks.add(StoreCountDrift.exact("fields-opensearch", "Fields", "neo4j", s.neo4jField(),
        "opensearch", s.openSearchField(), s.neo4jField(), INDEX_LABELS, INDEX_RULE, INDEX_DRIFT_NOTE));
    checks.add(StoreCountDrift.exact("elements-opensearch", "Element", "neo4j", s.neo4jElement(),
        "opensearch", s.openSearchElement(), s.neo4jElement(), INDEX_LABELS, INDEX_RULE, INDEX_DRIFT_NOTE));
    checks.add(StoreCountDrift.exact("templates-opensearch", "Templates", "neo4j", s.neo4jTemplate(),
        "opensearch", s.openSearchTemplate(), s.neo4jTemplate(), INDEX_LABELS, INDEX_RULE, INDEX_DRIFT_NOTE));
    checks.add(StoreCountDrift.exact("instances-opensearch", "Instances", "neo4j", s.neo4jInstance(),
        "opensearch", s.openSearchInstance(), s.neo4jInstance(), INDEX_LABELS, INDEX_RULE, INDEX_DRIFT_NOTE));

    checks.add(StoreCountDrift.exact("folders-expansion", "Folders", "folders", s.neo4jFolder(),
        "parts", s.neo4jRegularFolder() + s.neo4jUserHomeFolder() + s.neo4jSystemFolder(),
        s.neo4jFolder(), EXPANSION_LABELS, EXPANSION_RULE, EXPANSION_DRIFT_NOTE));
    checks.add(StoreCountDrift.exact("regular-folders-opensearch", "Regular folders", "neo4j",
        s.neo4jRegularFolder(), "opensearch", s.openSearchFolder(), s.neo4jRegularFolder(),
        INDEX_LABELS, FOLDER_RULE, INDEX_DRIFT_NOTE));
    checks.add(userHomeFolderRow(s));
    checks.add(systemFolders(s));

    int drifting = 0;
    int unverified = 0;
    int unavailable = 0;
    int notChecked = 0;
    for (StoreCountDrift check : checks) {
      switch (check.status()) {
        case DRIFT -> drifting++;
        case UNVERIFIED -> unverified++;
        case UNAVAILABLE -> unavailable++;
        case NOT_CHECKED -> notChecked++;
        default -> {
        }
      }
    }
    return new StoreCountDriftReport(List.copyOf(checks), checks.size() - notChecked, drifting,
        unverified, unavailable);
  }

  /**
   * System folders, reported without a rule.
   *
   * <p>There are two creation sites and there have always been two folders, but asserting the
   * number two here would be a check that fires the first time someone legitimately adds a third.
   * It does not need one: a third system folder is one fewer regular folder, so the Regular folders
   * row's comparison with the index moves, and the expansion check confirms the three parts still
   * account for the total - both rules staying true whatever the number is.
   */
  private static StoreCountDrift systemFolders(Snapshot s) {
    return notChecked("system-folders", "System folders", s.neo4jSystemFolder(), SYSTEM_FOLDER_RULE);
  }

  /**
   * The user home folder count, carried without a check.
   *
   * <p>There is a rule about this number and it is a good one, but it compares the count with the
   * users, not with another store's view of folders. It is reported on the Users row for that
   * reason. Leaving a second copy of it here would have said the folder breakdown was being checked
   * as folders, which it is not - and it would have counted the same check twice in the headline.
   */
  private static StoreCountDrift userHomeFolderRow(Snapshot s) {
    return notChecked("user-home-folders-row", "User home folders", s.neo4jUserHomeFolder(),
        HOME_FOLDER_ROW_RULE);
  }

  /** A number the page carries for context, with the reasoning for not checking it. */
  private static StoreCountDrift notChecked(String id, String resourceType, long count, String rule) {
    return new StoreCountDrift(id, resourceType, "neo4j", count, "neo4j", count, null, 0, null,
        DriftStatus.NOT_CHECKED, "not checked", rule, null);
  }

  /**
   * Users, where only one direction of the difference is a rule.
   *
   * <p>Keycloak holding more accounts than the graph holds users is the normal state and its size
   * says nothing on its own: an account that has never signed in and an account whose provisioning
   * callback failed are the same number from here. Reported as {@link DriftStatus#UNVERIFIED} with
   * the size named, rather than as a clean pass that hides a real population or a failure that
   * cries wolf on every dormant registration.
   *
   * <p>The other direction is exact. A CEDAR user with no Keycloak account cannot sign in and
   * cannot be administered, and there is no path that produces one on purpose.
   */
  private static StoreCountDrift users(Snapshot s) {
    if (s.keycloakUser() == null) {
      return new StoreCountDrift("users-keycloak", "Users", "keycloak", 0L, "neo4j", s.neo4jUser(),
          null, 0, null, DriftStatus.UNAVAILABLE, "keycloak not read", USER_RULE,
          "Keycloak could not be read; see errorPack.");
    }
    long keycloak = s.keycloakUser();
    long gapToKeycloak = s.neo4jUser() - keycloak;
    if (gapToKeycloak > 0) {
      return new StoreCountDrift("users-keycloak", "Users", "keycloak", keycloak, "neo4j",
          s.neo4jUser(), keycloak, gapToKeycloak, gapToKeycloak, DriftStatus.DRIFT,
          gapToKeycloak + " without a Keycloak account", USER_RULE,
          "CEDAR users with no Keycloak account. They cannot sign in and cannot be administered.");
    }
    long gap = keycloak - s.neo4jUser();
    String note = gap == 0
        ? "Every Keycloak account has signed in at least once."
        : gap + " Keycloak accounts have no CEDAR user node. Expected for accounts that have never "
            + "signed in, for service accounts and for disabled ones — but a provisioning callback "
            + "that failed looks exactly the same from a total. Separating them needs an id-level "
            + "reconciliation, not a count.";
    return new StoreCountDrift("users-keycloak", "Users", "keycloak", keycloak, "neo4j",
        s.neo4jUser(), null, gapToKeycloak, null,
        gap == 0 ? DriftStatus.OK : DriftStatus.UNVERIFIED,
        gap == 0 ? "all accounts signed in" : gap + " without a user node", USER_RULE, note);
  }
}
