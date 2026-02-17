/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: Prometheus Counter API → OtelMetricBackend → OTel SDK → InMemoryMetricReader.
 */
class OtelCounterTest {

  private InMemoryMetricReader reader;
  private OtelMetricBackend backend;

  @BeforeEach
  void setUp() {
    reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelMetricBackend.configure(meterProvider);
    backend = new OtelMetricBackend();
  }

  @AfterEach
  void tearDown() {
    OtelMetricBackend.resetForTest();
  }

  @Test
  void counterIncrementsFlowThroughOtelSdk() {
    MetricMetadata metadata = new MetricMetadata("http_requests");
    String[] labelNames = {"method", "status"};
    String[] labelValues = {"GET", "200"};

    CounterDataPoint dataPoint =
        backend.createCounterDataPoint(metadata, labelNames, labelValues);

    dataPoint.inc();
    dataPoint.inc(2.5);

    assertThat(reader.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasName("http_requests_total")
                    .hasDoubleSumSatisfying(
                        sum ->
                            sum.isMonotonic()
                                .isCumulative()
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasValue(3.5)
                                            .hasAttribute(
                                                AttributeKey.stringKey("method"), "GET")
                                            .hasAttribute(
                                                AttributeKey.stringKey("status"), "200"))));
  }

  @Test
  void counterWithoutLabels() {
    MetricMetadata metadata = new MetricMetadata("events");
    CounterDataPoint dataPoint =
        backend.createCounterDataPoint(metadata, new String[0], new String[0]);

    dataPoint.inc(5);

    assertThat(reader.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasName("events_total")
                    .hasDoubleSumSatisfying(
                        sum ->
                            sum.isMonotonic()
                                .hasPointsSatisfying(point -> point.hasValue(5.0))));
  }

  @Test
  void localValueTrackingMatchesOtelValue() {
    MetricMetadata metadata = new MetricMetadata("local_counter");
    CounterDataPoint dataPoint =
        backend.createCounterDataPoint(metadata, new String[0], new String[0]);

    dataPoint.inc(3);
    dataPoint.inc(1.5);

    assertThat(dataPoint.get()).isEqualTo(4.5);
    assertThat(dataPoint.getLongValue()).isEqualTo(4L);
  }
}
