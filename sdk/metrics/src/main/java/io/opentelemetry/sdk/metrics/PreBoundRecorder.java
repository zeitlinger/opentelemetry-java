/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.metrics.internal.aggregator.AggregatorHandle;
import io.opentelemetry.sdk.metrics.internal.state.WriteableMetricStorage;
import javax.annotation.Nullable;

/**
 * Pre-resolves an {@link AggregatorHandle} for a fixed attribute set, allowing direct recording
 * that skips the per-call {@code ConcurrentHashMap} lookup, attribute processing, validation, and
 * {@code Context.current()}.
 *
 * <p>This is an internal optimization for the Prometheus client shim, where attributes are fixed at
 * {@code labelValues()} time.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class PreBoundRecorder {

  @Nullable private final AggregatorHandle<?> handle;
  private final Attributes attributes;

  private PreBoundRecorder(@Nullable AggregatorHandle<?> handle, Attributes attributes) {
    this.handle = handle;
    this.attributes = attributes;
  }

  private static final PreBoundRecorder UNBOUND =
      new PreBoundRecorder(null, Attributes.empty());

  /** Bind a {@link DoubleCounter} to pre-resolved attributes. */
  public static PreBoundRecorder bind(DoubleCounter counter, Attributes attributes) {
    if (counter instanceof SdkDoubleCounter) {
      return bindStorage(((SdkDoubleCounter) counter).storage, attributes);
    }
    return UNBOUND;
  }

  /** Bind a {@link DoubleGauge} to pre-resolved attributes. */
  public static PreBoundRecorder bind(DoubleGauge gauge, Attributes attributes) {
    if (gauge instanceof SdkDoubleGauge) {
      return bindStorage(((SdkDoubleGauge) gauge).storage, attributes);
    }
    return UNBOUND;
  }

  /** Bind a {@link DoubleHistogram} to pre-resolved attributes. */
  public static PreBoundRecorder bind(DoubleHistogram histogram, Attributes attributes) {
    if (histogram instanceof SdkDoubleHistogram) {
      return bindStorage(((SdkDoubleHistogram) histogram).storage, attributes);
    }
    return UNBOUND;
  }

  private static PreBoundRecorder bindStorage(
      WriteableMetricStorage storage, Attributes attributes) {
    AggregatorHandle<?> handle = storage.resolveHandle(attributes);
    return new PreBoundRecorder(handle, attributes);
  }

  /**
   * Returns {@code true} if the fast path is available (handle was successfully pre-resolved).
   * When {@code false}, the caller should fall back to the normal instrument API.
   */
  public boolean isBound() {
    return handle != null;
  }

  /**
   * Record a double value directly into the pre-resolved handle. Skips CHM lookup, validation, and
   * Context.current().
   */
  public void recordDouble(double value) {
    if (handle != null) {
      handle.recordDouble(value, attributes, Context.root());
    }
  }

  /**
   * Record a long value directly into the pre-resolved handle. Skips CHM lookup, validation, and
   * Context.current().
   */
  public void recordLong(long value) {
    if (handle != null) {
      handle.recordLong(value, attributes, Context.root());
    }
  }
}
