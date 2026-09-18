package org.metadatacenter.cedar.monitor.counts;

import java.util.function.LongFunction;

/**
 * One store's count read against another's, together with the rule that says what the second one
 * ought to be.
 *
 * <p>The counts page has always shown the stores side by side and left the arithmetic to whoever
 * was reading it. Two of the gaps it shows are expected and permanent — the search index holds no
 * user home or system folders, and a Keycloak account only becomes a CEDAR user node when someone
 * signs in — so the page reported thousands of resources' worth of difference that nobody should
 * act on, in the same typeface as the differences that matter. A reader who learns to discount the
 * folder row learns to discount the artifact rows beside it.
 *
 * @param expected what {@code comparedCount} should be, or null where no exact rule gives it
 * @param gap      {@code comparedCount - authorityCount}, the raw difference between the two
 *                 sides before any rule is applied. Equal to {@code drift} wherever the rule
 *                 expects the two sides to match, which is most of them; it earns its place on a
 *                 check that has no exact expectation, where it is the only figure there is. The
 *                 users row is that case - without it the page could not say whether the
 *                 unverifiable difference was 338 or 3.
 * @param drift    {@code comparedCount - expected}, or null where there is no expectation to
 *                 subtract from. Signed: which way it leans usually says which failure happened.
 * @param label    a few words saying what this check came to, for the page to show without the
 *                 reader having to open the rule. Written per check rather than composed from the
 *                 two side names and the number: that formula produced "parts 0" and
 *                 "home folders 0", which are only readable by someone who already knows what the
 *                 check does - and it gave a passing check and an unchecked one the same shape, so
 *                 nothing on the page said they were different kinds of statement.
 */
public record StoreCountDrift(
    String id,
    String resourceType,
    String authority,
    long authorityCount,
    String compared,
    long comparedCount,
    Long expected,
    long gap,
    Long drift,
    DriftStatus status,
    String label,
    String rule,
    String note) {

  /**
   * How a check words itself: what it says when it holds, and what it says when it does not.
   *
   * @param ok    shown when the drift is zero
   * @param drift given the signed drift, which is never zero when this is called. Both directions
   *              are usually different news, so each check words them separately rather than
   *              printing a sign.
   */
  record Labels(String ok, LongFunction<String> drift) {
  }

  static StoreCountDrift exact(String id, String resourceType, String authority, long authorityCount,
                               String compared, long comparedCount, long expected, Labels labels,
                               String rule, String driftNote) {
    long drift = comparedCount - expected;
    return new StoreCountDrift(id, resourceType, authority, authorityCount, compared, comparedCount,
        expected, comparedCount - authorityCount, drift,
        drift == 0 ? DriftStatus.OK : DriftStatus.DRIFT,
        drift == 0 ? labels.ok() : labels.drift().apply(drift), rule,
        drift == 0 ? null : driftNote);
  }
}
