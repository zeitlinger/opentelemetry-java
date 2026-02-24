/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics.internal.state;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.internal.aggregator.AggregatorHandle;
import javax.annotation.Nullable;

/**
 * Stores {@link MetricData} and allows synchronous writes of measurements.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public interface WriteableMetricStorage {

  /** Records a measurement. */
  void recordLong(long value, Attributes attributes, Context context);

  /** Records a measurement. */
  void recordDouble(double value, Attributes attributes, Context context);

  /**
   * Returns {@code true} if the storage is actively recording measurements, and {@code false}
   * otherwise (i.e. noop / empty metric storage is installed).
   */
  boolean isEnabled();

  /**
   * Pre-resolve the {@link AggregatorHandle} for the given attributes. Returns {@code null} if
   * pre-resolution is not supported (e.g. delta temporality or multi-storage).
   *
   * <p>This is an optimization for callers that record many values against the same attribute set
   * (e.g. pre-bound Prometheus metrics). The returned handle can be used to record values directly,
   * skipping the per-call attribute lookup.
   */
  @Nullable
  default AggregatorHandle<?> resolveHandle(Attributes attributes) {
    return null;
  }
}
