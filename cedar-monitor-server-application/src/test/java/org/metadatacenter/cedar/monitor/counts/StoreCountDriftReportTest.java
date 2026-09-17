package org.metadatacenter.cedar.monitor.counts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules, against the production reading of 2026-09-16 and against the cases that reading does
 * not contain.
 */
class StoreCountDriftReportTest {

  /**
   * Prod, 2026-09-16. The folder row is the one this whole report exists for: 8035 against 2374
   * reads as 5661 missing documents and is in fact exact, because 5659 of those folders are user
   * homes and two are system folders.
   */
  private static StoreCountDriftReport.Snapshot prod() {
    return new StoreCountDriftReport.Snapshot(
        5659, 8035, 5659, 2, 2374,
        141693, 5307, 4831, 150640,
        141773, 5612, 4899, 150644,
        141693, 5307, 4831, 150640, 2374,
        5997);
  }

  private static StoreCountDrift check(StoreCountDriftReport report, String id) {
    return report.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void theFolderGapIsExactlyTheIndexExclusionRule() {
    StoreCountDrift folders = check(StoreCountDriftReport.of(prod()), "regular-folders-opensearch");

    assertEquals(2374L, folders.expected());
    assertEquals(0L, folders.drift());
    assertEquals(DriftStatus.OK, folders.status());
    assertNull(folders.note());
  }

  /**
   * 2374 + 5659 + 2 = 8035. Four counts read separately from the graph, so this can fail; derived
   * from each other it could not.
   */
  @Test
  void theThreeKindsOfFolderAccountForTheTotal() {
    StoreCountDrift expansion = check(StoreCountDriftReport.of(prod()), "folders-expansion");

    assertEquals(8035L, expansion.expected());
    assertEquals(0L, expansion.drift());
    assertEquals(DriftStatus.OK, expansion.status());
  }

  /** A folder that is none of the three kinds, or one carrying two labels, leaves the parts short. */
  @Test
  void aFolderTheExpansionDoesNotCoverShowsAsThePartsNotAddingUp() {
    StoreCountDriftReport.Snapshot s = new StoreCountDriftReport.Snapshot(
        5659, 8040, 5659, 2, 2374,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640, 2374,
        5997);

    StoreCountDrift expansion = check(StoreCountDriftReport.of(s), "folders-expansion");

    assertEquals(-5L, expansion.drift());
    assertEquals(DriftStatus.DRIFT, expansion.status());
  }

  @Test
  void theSearchIndexIsInStepWithTheGraphOnEveryArtifactType() {
    StoreCountDriftReport report = StoreCountDriftReport.of(prod());

    for (String id : new String[]{"fields-opensearch", "elements-opensearch", "templates-opensearch",
        "instances-opensearch"}) {
      assertEquals(DriftStatus.OK, check(report, id).status(), id);
    }
  }

  @Test
  void artifactStoreSurplusIsReportedAsDriftAndCountsTheOrphans() {
    StoreCountDriftReport report = StoreCountDriftReport.of(prod());

    assertEquals(80L, check(report, "fields-mongo").drift());
    assertEquals(305L, check(report, "elements-mongo").drift());
    assertEquals(68L, check(report, "templates-mongo").drift());
    assertEquals(4L, check(report, "instances-mongo").drift());
    assertEquals(DriftStatus.DRIFT, check(report, "elements-mongo").status());
    assertTrue(check(report, "elements-mongo").note().contains("orphaned documents"));
  }

  @Test
  void aMissingDocumentDriftsTheOtherWayAndSaysSo() {
    StoreCountDriftReport.Snapshot s = new StoreCountDriftReport.Snapshot(
        5659, 8035, 5659, 2, 2374,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150_639,
        141693, 5307, 4831, 150640, 2374,
        5997);

    StoreCountDrift instances = check(StoreCountDriftReport.of(s), "instances-mongo");

    assertEquals(-1L, instances.drift());
    assertEquals(DriftStatus.DRIFT, instances.status());
  }

  @Test
  void moreKeycloakAccountsThanUsersIsNotCalledAPassAndNotCalledAFault() {
    StoreCountDrift users = check(StoreCountDriftReport.of(prod()), "users-keycloak");

    assertEquals(DriftStatus.UNVERIFIED, users.status());
    assertNull(users.expected());
    assertNull(users.drift());
    assertTrue(users.note().startsWith("338 Keycloak accounts"));
  }

  /**
   * The size of that difference is reported even though it cannot be verified. Leaving it only in
   * the prose would mean a reader could not tell 338 from 3.
   */
  @Test
  void theUnverifiableUserGapStillCarriesItsSize() {
    assertEquals(-338L, check(StoreCountDriftReport.of(prod()), "users-keycloak").gap());
  }

  /**
   * Where a rule expects the two sides to match, the whole difference and the unexplained part are
   * the same number. That is every check but one, and it is why {@code gap} is worth having only
   * for the one: the users row, where there is no expectation for {@code drift} to be measured
   * against.
   */
  @Test
  void gapAndDriftAgreeWhereverThereIsAnExactExpectation() {
    for (StoreCountDrift c : StoreCountDriftReport.of(prod()).checks()) {
      if (c.expected() != null) {
        assertEquals(c.gap(), c.drift(), c.id());
      }
    }
  }

  /**
   * The chips read as statements rather than as a side name and a number. "parts 0" and
   * "home folders 0" needed the rule open to mean anything, and gave a passing check the same shape
   * as an unchecked one.
   */
  @Test
  void everyCheckSaysWhatItCameToInWords() {
    StoreCountDriftReport report = StoreCountDriftReport.of(prod());

    assertEquals("338 without a user node", check(report, "users-keycloak").label());
    assertEquals("one home per user", check(report, "user-home-folders").label());
    assertEquals("80 orphaned documents", check(report, "fields-mongo").label());
    assertEquals("index in step", check(report, "fields-opensearch").label());
    assertEquals("parts add up", check(report, "folders-expansion").label());
    assertEquals("index in step", check(report, "regular-folders-opensearch").label());
    assertEquals("not checked", check(report, "system-folders").label());
    assertEquals("not checked", check(report, "user-home-folders-row").label());
  }

  /**
   * The rule about home folders compares them with users, so it is reported on the Users row where
   * the other side of the comparison is. The folder sub-row carries the count and no check.
   */
  @Test
  void theHomeFolderCheckBelongsToTheUsersRowAndTheFolderSubRowCarriesNoCheck() {
    StoreCountDriftReport report = StoreCountDriftReport.of(prod());

    assertEquals("Users", check(report, "user-home-folders").resourceType());
    assertEquals(DriftStatus.NOT_CHECKED, check(report, "user-home-folders-row").status());
    assertEquals("User home folders", check(report, "user-home-folders-row").resourceType());
  }

  @Test
  void everyUserHasExactlyOneHomeFolder() {
    StoreCountDrift homes = check(StoreCountDriftReport.of(prod()), "user-home-folders");

    assertEquals(5659L, homes.expected());
    assertEquals(0L, homes.drift());
    assertEquals(DriftStatus.OK, homes.status());
  }

  /**
   * A login callback that stopped between creating the user and creating its home folder. Nothing
   * checked for this before; it leaves a user who cannot open a workspace.
   */
  @Test
  void aUserWithNoHomeFolderIsAFaultAndReadsAsOne() {
    StoreCountDriftReport.Snapshot s = new StoreCountDriftReport.Snapshot(
        5659, 8034, 5658, 2, 2374,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640, 2374,
        5997);

    StoreCountDrift homes = check(StoreCountDriftReport.of(s), "user-home-folders");

    assertEquals(-1L, homes.drift());
    assertEquals(DriftStatus.DRIFT, homes.status());
    assertTrue(homes.note().contains("no home folder"));
  }

  /**
   * System folders are reported, not asserted against the number two. A third one is caught by the
   * folder rule instead, which stays true whatever the number is.
   */
  @Test
  void aThirdSystemFolderIsCaughtByTheFolderRuleRatherThanByACount() {
    StoreCountDriftReport.Snapshot s = new StoreCountDriftReport.Snapshot(
        5659, 8036, 5659, 3, 2374,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640, 2374,
        5997);

    StoreCountDriftReport report = StoreCountDriftReport.of(s);

    assertEquals(DriftStatus.NOT_CHECKED, check(report, "system-folders").status());
    assertEquals("not checked", check(report, "system-folders").label());
    assertEquals(3L, check(report, "system-folders").comparedCount());
    // 2374 + 5659 + 3 = 8036, and the index still holds exactly the 2374 regular ones, so a third
    // system folder that is genuinely not indexed passes both rules without either naming a number.
    assertEquals(DriftStatus.OK, check(report, "folders-expansion").status());
    assertEquals(DriftStatus.OK, check(report, "regular-folders-opensearch").status());
  }

  @Test
  void moreUsersThanKeycloakAccountsIsExactAndIsAFault() {
    StoreCountDriftReport.Snapshot s = new StoreCountDriftReport.Snapshot(
        6000, 8035, 5659, 2, 2374,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640, 2374,
        5997);

    StoreCountDrift users = check(StoreCountDriftReport.of(s), "users-keycloak");

    assertEquals(DriftStatus.DRIFT, users.status());
    assertEquals(3L, users.drift());
  }

  @Test
  void keycloakBeingUnreadableIsNotAZero() {
    StoreCountDriftReport.Snapshot s = new StoreCountDriftReport.Snapshot(
        5659, 8035, 5659, 2, 2374,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640, 2374,
        null);

    StoreCountDriftReport report = StoreCountDriftReport.of(s);

    assertEquals(DriftStatus.UNAVAILABLE, check(report, "users-keycloak").status());
    assertEquals(1, report.unavailableChecks());
    assertEquals(0, report.driftingChecks());
  }

  @Test
  void aCleanEstateDriftsNowhere() {
    StoreCountDriftReport.Snapshot s = new StoreCountDriftReport.Snapshot(
        5659, 8035, 5659, 2, 2374,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640,
        141693, 5307, 4831, 150640, 2374,
        5659);

    StoreCountDriftReport report = StoreCountDriftReport.of(s);

    assertEquals(12, report.totalChecks());
    assertEquals(0, report.driftingChecks());
    assertEquals(0, report.unverifiedChecks());
    assertEquals(0, report.unavailableChecks());
  }

  /**
   * The system folder count is carried for context, not checked, so it must not inflate the "n of m
   * hold" headline into counting something that could never have failed.
   */
  @Test
  void aReportedNumberWithNoRuleIsNotCountedAsAPassingCheck() {
    StoreCountDriftReport report = StoreCountDriftReport.of(prod());

    assertEquals(DriftStatus.NOT_CHECKED, check(report, "system-folders").status());
    assertEquals(14, report.checks().size());
    assertEquals(12, report.totalChecks());
  }

  @Test
  void theProdReadingHasFourDriftingRowsAndOneUnverifiable() {
    StoreCountDriftReport report = StoreCountDriftReport.of(prod());

    assertEquals(12, report.totalChecks());
    assertEquals(4, report.driftingChecks());
    assertEquals(1, report.unverifiedChecks());
    assertEquals(0, report.unavailableChecks());
  }
}
