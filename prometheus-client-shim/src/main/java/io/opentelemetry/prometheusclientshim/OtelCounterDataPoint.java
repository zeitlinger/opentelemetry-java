/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.sdk.metrics.PreBoundRecorder;
import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.model.snapshots.Exemplar;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * A {@link CounterDataPoint} that delegates {@code inc()} to an OTel {@link DoubleCounter}.
 *
 * <p>The value is also tracked locally via adders so that {@code get()} and {@code getLongValue()}
 * continue to work for callers that read the counter value directly (e.g. {@code collect()}).
 * Local tracking is skipped when {@code dualWrite=false} since nobody reads the value.
 */
final class OtelCounterDataPoint implements CounterDataPoint {

  private final DoubleCounter otelCounter;
  private final Attributes attributes;
  @Nullable private final PreBoundRecorder recorder;
  @Nullable private final LongAdder longValue;
  @Nullable private final DoubleAdder doubleValue;

  OtelCounterDataPoint(
      DoubleCounter otelCounter,
      Attributes attributes,
      @Nullable PreBoundRecorder recorder,
      boolean trackLocally) {
    this.otelCounter = otelCounter;
    this.attributes = attributes;
    this.recorder = recorder;
    this.longValue = trackLocally ? new LongAdder() : null;
    this.doubleValue = trackLocally ? new DoubleAdder() : null;
  }

  @Override
  public void inc(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException(
          "Negative increment " + amount + " is illegal for Counter metrics.");
    }
    if (longValue != null) {
      longValue.add(amount);
    }
    if (recorder != null) {
      recorder.recordDouble((double) amount);
    } else {
      otelCounter.add((double) amount, attributes);
    }
  }

  @Override
  public void inc(double amount) {
    if (amount < 0) {
      throw new IllegalArgumentException(
          "Negative increment " + amount + " is illegal for Counter metrics.");
    }
    if (doubleValue != null) {
      doubleValue.add(amount);
    }
    if (recorder != null) {
      recorder.recordDouble(amount);
    } else {
      otelCounter.add(amount, attributes);
    }
  }

  @Override
  public void incWithExemplar(long amount, Labels labels) {
    if (amount < 0) {
      throw new IllegalArgumentException(
          "Negative increment " + amount + " is illegal for Counter metrics.");
    }
    if (longValue != null) {
      longValue.add(amount);
    }
    if (recorder != null) {
      recorder.recordDoubleWithExemplar(
          (double) amount, labels.get(Exemplar.TRACE_ID), labels.get(Exemplar.SPAN_ID));
    } else {
      otelCounter.add((double) amount, attributes);
    }
  }

  @Override
  public void incWithExemplar(double amount, Labels labels) {
    if (amount < 0) {
      throw new IllegalArgumentException(
          "Negative increment " + amount + " is illegal for Counter metrics.");
    }
    if (doubleValue != null) {
      doubleValue.add(amount);
    }
    if (recorder != null) {
      recorder.recordDoubleWithExemplar(
          amount, labels.get(Exemplar.TRACE_ID), labels.get(Exemplar.SPAN_ID));
    } else {
      otelCounter.add(amount, attributes);
    }
  }

  @Override
  public double get() {
    if (longValue == null || doubleValue == null) {
      return 0;
    }
    return longValue.sum() + doubleValue.sum();
  }

  @Override
  public long getLongValue() {
    if (longValue == null || doubleValue == null) {
      return 0;
    }
    return longValue.sum() + (long) doubleValue.sum();
  }
}
