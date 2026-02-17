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
}
