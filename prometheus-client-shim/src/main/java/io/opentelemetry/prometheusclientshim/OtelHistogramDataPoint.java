/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.sdk.metrics.PreBoundRecorder;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * A {@link DistributionDataPoint} that delegates {@code observe()} to an OTel {@link
 * DoubleHistogram}.
 *
 * <p>Count and sum are tracked locally so that {@code getCount()} and {@code getSum()} continue to
 * work for callers that read values directly (e.g. {@code collect()}).
 */
final class OtelHistogramDataPoint implements DistributionDataPoint {

  private final DoubleHistogram otelHistogram;
  private final Attributes attributes;
  @Nullable private final PreBoundRecorder recorder;
  private final LongAdder count = new LongAdder();
  private final DoubleAdder sum = new DoubleAdder();

  OtelHistogramDataPoint(
      DoubleHistogram otelHistogram,
      Attributes attributes,
      @Nullable PreBoundRecorder recorder) {
    this.otelHistogram = otelHistogram;
    this.attributes = attributes;
    this.recorder = recorder;
  }

  @Override
  public void observe(double value) {
    count.increment();
    sum.add(value);
    if (recorder != null) {
      recorder.recordDouble(value);
    } else {
      otelHistogram.record(value, attributes);
    }
  }

  @Override
  public void observeWithExemplar(double value, Labels labels) {
    observe(value);
  }

  @Override
  public long getCount() {
    return count.sum();
  }

  @Override
  public double getSum() {
    return sum.sum();
  }
}
