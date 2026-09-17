package org.metadatacenter.cedar.monitor.counts;

/** What a single cross-store count comparison came to. */
public enum DriftStatus {
  /** The rule holds exactly. */
  OK,
  /** The rule is violated, by {@code StoreCountDrift#drift} resources. */
  DRIFT,
  /**
   * The two counts differ for a reason no count can settle. Not a fault and not a clean bill:
   * it says the question needs something other than a total to answer it.
   */
  UNVERIFIED,
  /** One of the two stores could not be read, so there is nothing to compare. */
  UNAVAILABLE,
  /**
   * The count is reported and no rule is applied to it.
   *
   * <p>Distinct from {@link #UNVERIFIED}, which is a real comparison whose difference no total can
   * settle. This is the absence of a comparison, and it is a deliberate choice rather than a gap:
   * it exists so that a figure carried on the page for context does not have to masquerade as a
   * passing check to be shown. Not counted in {@code totalChecks}.
   */
  NOT_CHECKED
}
