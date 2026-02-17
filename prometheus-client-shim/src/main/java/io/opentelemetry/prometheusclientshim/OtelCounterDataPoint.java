/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * A {@link CounterDataPoint} that delegates {@code inc()} to an OTel {@link DoubleCounter}.
 *
 * <p>The value is also tracked locally via adders so that {@code get()} and {@code getLongValue()}
 * continue to work for callers that read the counter value directly (e.g. {@code collect()}).
 */
final class OtelCounterDataPoint implements CounterDataPoint {

  private final DoubleCounter otelCounter;
  private final Attributes attributes;
  private final LongAdder longValue = new LongAdder();
  private final DoubleAdder doubleValue = new DoubleAdder();

  OtelCounterDataPoint(DoubleCounter otelCounter, Attributes attributes) {
    this.otelCounter = otelCounter;
    this.attributes = attributes;
  }

  @Override
  public void inc(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException(
          "Negative increment " + amount + " is illegal for Counter metrics.");
    }
    longValue.add(amount);
    otelCounter.add((double) amount, attributes);
  }

  @Override
  public void inc(double amount) {
    if (amount < 0) {
      throw new IllegalArgumentException(
          "Negative increment " + amount + " is illegal for Counter metrics.");
    }
    doubleValue.add(amount);
    otelCounter.add(amount, attributes);
  }

  @Override
  public void incWithExemplar(long amount, Labels labels) {
    inc(amount);
  }

  @Override
  public void incWithExemplar(double amount, Labels labels) {
    inc(amount);
  }

  @Override
  public double get() {
    return longValue.sum() + doubleValue.sum();
  }

  @Override
  public long getLongValue() {
    return longValue.sum() + (long) doubleValue.sum();
  }
}
