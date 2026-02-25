/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.sdk.metrics.PreBoundRecorder;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.model.snapshots.Exemplar;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * A {@link DistributionDataPoint} that delegates {@code observe()} to an OTel {@link
 * DoubleHistogram}.
 *
 * <p>Count and sum are tracked locally so that {@code getCount()} and {@code getSum()} continue to
 * work for callers that read values directly (e.g. {@code collect()}). Local tracking is skipped
 * when {@code dualWrite=false} since nobody reads the values.
 */
final class OtelHistogramDataPoint implements DistributionDataPoint {

  private final DoubleHistogram otelHistogram;
  private final Attributes attributes;
  @Nullable private final PreBoundRecorder recorder;
  @Nullable private final LongAdder count;
  @Nullable private final DoubleAdder sum;

  OtelHistogramDataPoint(
      DoubleHistogram otelHistogram,
      Attributes attributes,
      @Nullable PreBoundRecorder recorder,
      boolean trackLocally) {
    this.otelHistogram = otelHistogram;
    this.attributes = attributes;
    this.recorder = recorder;
    this.count = trackLocally ? new LongAdder() : null;
    this.sum = trackLocally ? new DoubleAdder() : null;
  }

  @Override
  public void observe(double value) {
    if (count != null && sum != null) {
      count.increment();
      sum.add(value);
    }
    if (recorder != null) {
      recorder.recordDoubleSkipExemplars(value);
    } else {
      otelHistogram.record(value, attributes);
    }
  }

  @Override
  public void observeWithExemplar(double value, Labels labels) {
    if (count != null && sum != null) {
      count.increment();
      sum.add(value);
    }
    if (recorder != null) {
      recorder.recordDoubleWithExemplar(
          value, labels.get(Exemplar.TRACE_ID), labels.get(Exemplar.SPAN_ID));
    } else {
      otelHistogram.record(value, attributes);
    }
  }

  @Override
  public long getCount() {
    return count != null ? count.sum() : 0;
  }

  @Override
  public double getSum() {
    return sum != null ? sum.sum() : 0;
  }
}
