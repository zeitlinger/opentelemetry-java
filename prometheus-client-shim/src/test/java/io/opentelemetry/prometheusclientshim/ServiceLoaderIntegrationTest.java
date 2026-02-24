/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test exercising the full transparent path:
 *
 * <ol>
 *   <li>{@code ServiceLoader} discovers {@link OtelMetricBackend} (via META-INF/services)
 *   <li>Application calls {@link OtelMetricBackend#configure(io.opentelemetry.api.metrics.MeterProvider)}
 *       during startup
 *   <li>{@code Counter.builder().register()} creates data points backed by the OTel SDK
 *   <li>Data flows from Prometheus API → OTel SDK → {@link InMemoryMetricReader}
 * </ol>
 *
 * <p>This is the intended user experience: add the shim JAR, call {@code
 * OtelMetricBackend.configure(meterProvider)} once at startup, and existing Prometheus
 * instrumentation just works.
 */
class ServiceLoaderIntegrationTest {

  private InMemoryMetricReader reader;
  private PrometheusRegistry registry;

  @BeforeEach
  void setUp() {
    reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelMetricBackend.configure(meterProvider);
    registry = new PrometheusRegistry();
  }

  @AfterEach
  void tearDown() {
    OtelMetricBackend.resetForTest();
  }

  @Test
  void transparentCounterWithLabels() {
    // Standard Prometheus client API — no shim-specific code beyond the one-time configure().
    Counter counter =
        Counter.builder()
            .name("http_requests_total")
            .help("Total HTTP requests")
            .labelNames("method", "status")
            .register(registry);

    counter.labelValues("GET", "200").inc();
    counter.labelValues("GET", "200").inc();
    counter.labelValues("POST", "201").inc(3);

    // Verify data arrived in the OTel SDK pipeline.
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
                                            .hasValue(2.0)
                                            .hasAttribute(
                                                AttributeKey.stringKey("method"), "GET")
                                            .hasAttribute(
                                                AttributeKey.stringKey("status"), "200"),
                                    point ->
                                        point
                                            .hasValue(3.0)
                                            .hasAttribute(
                                                AttributeKey.stringKey("method"), "POST")
                                            .hasAttribute(
                                                AttributeKey.stringKey("status"), "201"))));

    // The Prometheus collect() path also still works (dual-write).
    assertThat(counter.collect().getDataPoints()).hasSize(2);
  }

  @Test
  void transparentCounterWithoutLabels() {
    Counter counter =
        Counter.builder()
            .name("events_total")
            .help("Total events")
            .register(registry);

    counter.inc(42);

    assertThat(reader.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasName("events_total")
                    .hasDoubleSumSatisfying(
                        sum ->
                            sum.isMonotonic()
                                .hasPointsSatisfying(point -> point.hasValue(42.0))));

    assertThat(counter.get()).isEqualTo(42.0);
  }

  @Test
  void transparentGaugeWithLabels() {
    Gauge gauge =
        Gauge.builder()
            .name("current_active_users")
            .help("Number of users that are currently active")
            .labelNames("region")
            .register(registry);

    gauge.labelValues("us-east").inc();
    gauge.labelValues("us-east").inc();
    gauge.labelValues("eu-west").set(5.0);

    assertThat(reader.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasName("current_active_users")
                    .hasDoubleGaugeSatisfying(
                        g ->
                            g.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasValue(2.0)
                                        .hasAttribute(
                                            AttributeKey.stringKey("region"), "us-east"),
                                point ->
                                    point
                                        .hasValue(5.0)
                                        .hasAttribute(
                                            AttributeKey.stringKey("region"), "eu-west"))));

    // The Prometheus collect() path also still works (dual-write).
    assertThat(gauge.collect().getDataPoints()).hasSize(2);
  }

  @Test
  void transparentGaugeSetAndInc() {
    Gauge gauge =
        Gauge.builder()
            .name("temperature")
            .help("Current temperature")
            .register(registry);

    gauge.set(20.0);
    gauge.inc(3.0);
    gauge.dec(1.0);

    assertThat(reader.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasName("temperature")
                    .hasDoubleGaugeSatisfying(
                        g -> g.hasPointsSatisfying(point -> point.hasValue(22.0))));

    assertThat(gauge.get()).isEqualTo(22.0);
  }

  @Test
  void transparentHistogramWithLabels() {
    Histogram histogram =
        Histogram.builder()
            .name("http_request_duration_seconds")
            .help("HTTP request duration in seconds")
            .classicOnly()
            .classicUpperBounds(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
            .labelNames("method")
            .register(registry);

    histogram.labelValues("GET").observe(0.05);
    histogram.labelValues("GET").observe(0.15);
    histogram.labelValues("POST").observe(1.5);

    assertThat(reader.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasName("http_request_duration_seconds")
                    .hasHistogramSatisfying(
                        h ->
                            h.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSum(0.2)
                                        .hasCount(2)
                                        .hasAttribute(
                                            AttributeKey.stringKey("method"), "GET"),
                                point ->
                                    point
                                        .hasSum(1.5)
                                        .hasCount(1)
                                        .hasAttribute(
                                            AttributeKey.stringKey("method"), "POST"))));

    // The Prometheus collect() path also still works (dual-write).
    assertThat(histogram.collect().getDataPoints()).hasSize(2);
  }

  @Test
  void transparentHistogramWithoutLabels() {
    Histogram histogram =
        Histogram.builder()
            .name("request_size_bytes")
            .help("Request size")
            .classicOnly()
            .register(registry);

    histogram.observe(100.0);
    histogram.observe(200.0);
    histogram.observe(300.0);

    assertThat(reader.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasName("request_size_bytes")
                    .hasHistogramSatisfying(
                        h ->
                            h.hasPointsSatisfying(
                                point -> point.hasSum(600.0).hasCount(3))));

    assertThat(histogram.getCount()).isEqualTo(3);
    assertThat(histogram.getSum()).isEqualTo(600.0);
  }
}
