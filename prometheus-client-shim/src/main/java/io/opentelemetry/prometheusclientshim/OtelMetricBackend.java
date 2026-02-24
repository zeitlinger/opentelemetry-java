/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.core.metrics.MetricBackend;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MetricBackend} that delegates to the OpenTelemetry SDK.
 *
 * <p>When this class is on the classpath and registered via {@code ServiceLoader}, Prometheus
 * client metrics record directly into the OTel SDK pipeline.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * // During application startup, after building your MeterProvider:
 * OtelMetricBackend.configure(sdkMeterProvider);
 *
 * // Then use the Prometheus client API as usual:
 * Counter counter = Counter.builder()
 *     .name("http_requests_total")
 *     .labelNames("method")
 *     .register();
 * counter.labelValues("GET").inc();
 * }</pre>
 */
public final class OtelMetricBackend implements MetricBackend {

  private static final String INSTRUMENTATION_SCOPE = "io.prometheus";

  private static volatile MeterProvider meterProvider = MeterProvider.noop();

  private static final ConcurrentHashMap<String, DoubleCounter> counters =
      new ConcurrentHashMap<>();

  private static final ConcurrentHashMap<String, DoubleGauge> gauges =
      new ConcurrentHashMap<>();

  private static final ConcurrentHashMap<String, DoubleHistogram> histograms =
      new ConcurrentHashMap<>();

  /**
   * Set the {@link MeterProvider} that backs all Prometheus metrics.
   *
   * <p>Call this once during application startup, before creating any Prometheus metrics. Counters
   * created before this call will record into a no-op provider.
   */
  public static void configure(MeterProvider meterProvider) {
    OtelMetricBackend.meterProvider = meterProvider;
  }

  /** Reset to no-op and clear cached counters. Visible for testing. */
  static void resetForTest() {
    meterProvider = MeterProvider.noop();
    counters.clear();
    gauges.clear();
    histograms.clear();
  }

  /** No-arg constructor used by {@code ServiceLoader}. */
  public OtelMetricBackend() {}

  @Override
  public CounterDataPoint createCounterDataPoint(
      MetricMetadata metadata, String[] labelNames, String[] labelValues) {

    String name = metadata.getPrometheusName() + "_total";
    DoubleCounter counter =
        counters.computeIfAbsent(
            name,
            n -> {
              Meter meter = meterProvider.get(INSTRUMENTATION_SCOPE);
              return meter.counterBuilder(n).ofDoubles().build();
            });

    Attributes attributes = buildAttributes(labelNames, labelValues);
    return new OtelCounterDataPoint(counter, attributes);
  }

  @Override
  public GaugeDataPoint createGaugeDataPoint(
      MetricMetadata metadata, String[] labelNames, String[] labelValues) {

    String name = metadata.getPrometheusName();
    DoubleGauge gauge =
        gauges.computeIfAbsent(
            name,
            n -> {
              Meter meter = meterProvider.get(INSTRUMENTATION_SCOPE);
              return meter.gaugeBuilder(n).build();
            });

    Attributes attributes = buildAttributes(labelNames, labelValues);
    return new OtelGaugeDataPoint(gauge, attributes);
  }

  @Override
  public DistributionDataPoint createHistogramDataPoint(
      MetricMetadata metadata,
      String[] labelNames,
      String[] labelValues,
      double[] classicUpperBounds) {

    String name = metadata.getPrometheusName();
    DoubleHistogram histogram =
        histograms.computeIfAbsent(
            name,
            n -> {
              Meter meter = meterProvider.get(INSTRUMENTATION_SCOPE);
              return meter
                  .histogramBuilder(n)
                  .setExplicitBucketBoundariesAdvice(toFiniteBoundaries(classicUpperBounds))
                  .build();
            });

    Attributes attributes = buildAttributes(labelNames, labelValues);
    return new OtelHistogramDataPoint(histogram, attributes);
  }

  /**
   * Convert Prometheus classic upper bounds to OTel explicit bucket boundaries. Prometheus includes
   * +Inf as the last bound; OTel does not.
   */
  private static List<Double> toFiniteBoundaries(double[] classicUpperBounds) {
    List<Double> boundaries = new ArrayList<>(classicUpperBounds.length);
    for (double bound : classicUpperBounds) {
      if (!Double.isInfinite(bound)) {
        boundaries.add(bound);
      }
    }
    return boundaries;
  }

  private static Attributes buildAttributes(String[] labelNames, String[] labelValues) {
    if (labelNames.length == 0) {
      return Attributes.empty();
    }
    AttributesBuilder builder = Attributes.builder();
    for (int i = 0; i < labelNames.length; i++) {
      builder.put(AttributeKey.stringKey(labelNames[i]), labelValues[i]);
    }
    return builder.build();
  }
}
