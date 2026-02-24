/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.metrics.internal.aggregator.AggregatorHandle;
import io.opentelemetry.sdk.metrics.internal.state.WriteableMetricStorage;
import javax.annotation.Nullable;

/**
 * Pre-resolves an {@link AggregatorHandle} for a fixed attribute set, allowing direct recording
 * that skips the per-call {@code ConcurrentHashMap} lookup, attribute processing, and validation.
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
   * Record a double value directly into the pre-resolved handle. Uses {@code Context.current()} to
   * support auto-sampled exemplars (the OTel reservoir extracts trace context from the current
   * span).
   */
  public void recordDouble(double value) {
    if (handle != null) {
      handle.recordDouble(value, attributes, Context.current());
    }
  }

  /**
   * Record a double value with explicit trace context for exemplars. Constructs a synthetic OTel
   * {@link SpanContext} from the provided trace/span IDs, allowing the exemplar reservoir to pick
   * them up.
   */
  public void recordDoubleWithExemplar(
      double value, @Nullable String traceId, @Nullable String spanId) {
    if (handle != null) {
      Context context;
      if (traceId != null && spanId != null) {
        SpanContext spanContext =
            SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        context = Context.root().with(Span.wrap(spanContext));
      } else {
        context = Context.current();
      }
      handle.recordDouble(value, attributes, context);
    }
  }

  /**
   * Record a long value directly into the pre-resolved handle. Uses {@code Context.current()} to
   * support auto-sampled exemplars.
   */
  public void recordLong(long value) {
    if (handle != null) {
      handle.recordLong(value, attributes, Context.current());
    }
  }
}
