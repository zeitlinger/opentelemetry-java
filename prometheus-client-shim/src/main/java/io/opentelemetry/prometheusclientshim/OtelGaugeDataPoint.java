/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link GaugeDataPoint} that delegates {@code set()} and {@code inc()} to an OTel {@link
 * DoubleGauge}.
 *
 * <p>The value is also tracked locally so that {@code get()} continues to work for callers that
 * read the gauge value directly (e.g. {@code collect()}).
 */
final class OtelGaugeDataPoint implements GaugeDataPoint {

  private final DoubleGauge otelGauge;
  private final Attributes attributes;
  private final AtomicLong value = new AtomicLong(Double.doubleToRawLongBits(0));

  OtelGaugeDataPoint(DoubleGauge otelGauge, Attributes attributes) {
    this.otelGauge = otelGauge;
    this.attributes = attributes;
  }

  @Override
  public void inc(double amount) {
    double newValue =
        Double.longBitsToDouble(
            value.updateAndGet(
                l -> Double.doubleToRawLongBits(Double.longBitsToDouble(l) + amount)));
    otelGauge.set(newValue, attributes);
  }

  @Override
  public void incWithExemplar(double amount, Labels labels) {
    inc(amount);
  }

  @Override
  public void set(double value) {
    this.value.set(Double.doubleToRawLongBits(value));
    otelGauge.set(value, attributes);
  }

  @Override
  public double get() {
    return Double.longBitsToDouble(value.get());
  }

  @Override
  public void setWithExemplar(double value, Labels labels) {
    set(value);
  }
}
