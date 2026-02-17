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
import org.junit.jupiter.api.Test;

/**
 * Full usage example for the Prometheus client shim PoC.
 *
 * <p>The shim lets you keep your Prometheus client instrumentation code unchanged while routing all
 * metric data through the OpenTelemetry SDK pipeline. This means a single aggregation layer, a
 * single export path, and seamless interop with OTel collectors, backends, and tooling.
 *
 * <h2>How it works</h2>
 *
 * <pre>
 *   ┌─────────────────────────────────┐
 *   │  Your application code          │
 *   │                                 │
 *   │  Counter c = Counter.builder()  │
 *   │      .name("http_requests")     │
 *   │      .labelNames("method")      │
 *   │      .register();               │
 *   │  c.labelValues("GET").inc();    │
 *   └────────────┬────────────────────┘
 *                │
 *                ▼
 *   ┌─────────────────────────────────┐
 *   │  MetricBackend SPI              │  ← prom_client_java
 *   │  (ServiceLoader discovery)      │
 *   └────────────┬────────────────────┘
 *                │
 *                ▼
 *   ┌─────────────────────────────────┐
 *   │  OtelMetricBackend              │  ← this module (prometheus-client-shim)
 *   │  OtelCounterDataPoint           │
 *   │    → otelCounter.add(1, attrs)  │
 *   └────────────┬────────────────────┘
 *                │
 *                ▼
 *   ┌─────────────────────────────────┐
 *   │  OTel SDK MeterProvider         │
 *   │    → OTLP / Prometheus / ...    │
 *   └─────────────────────────────────┘
 * </pre>
 *
 * <h2>Setup</h2>
 *
 * <ol>
 *   <li>Add the shim JAR to your classpath. {@code ServiceLoader} discovers {@link
 *       OtelMetricBackend} automatically.
 *   <li>During application startup, call {@link
 *       OtelMetricBackend#configure(io.opentelemetry.api.metrics.MeterProvider)} with your {@code
 *       SdkMeterProvider}.
 *   <li>Use the Prometheus client API as usual — {@code Counter.builder().register()} just works.
 * </ol>
 */
class UsageExample {

  @AfterEach
  void tearDown() {
    OtelMetricBackend.resetForTest();
  }

  /**
   * Demonstrates the full data flow: standard Prometheus client API → OTel SDK →
   * InMemoryMetricReader.
   *
   * <p>This is exactly what a user would write. The only shim-specific line is the {@code
   * configure()} call at startup.
   */
  @Test
  void prometheusCounterFlowsThroughOtelSdk() {
    // ── Startup: configure OTel SDK and wire the shim ───────────────────────
    //
    // In production, replace InMemoryMetricReader with e.g. OtlpGrpcMetricExporter.

    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelMetricBackend.configure(meterProvider);

    // ── Application code: standard Prometheus client API ────────────────────
    //
    // Nothing shim-specific below this line. This is how you'd write metrics
    // today — Counter, Histogram, Gauge, etc. all work the same way.
    //
    // (Only Counter is wired through the shim in this PoC. Histogram/Gauge
    // support would follow the same pattern.)

    PrometheusRegistry registry = new PrometheusRegistry();

    Counter requestCount =
        Counter.builder()
            .name("http_requests_total")
            .help("Total HTTP requests")
            .labelNames("method", "status")
            .register(registry);

    // Simulate some traffic
    requestCount.labelValues("GET", "200").inc();
    requestCount.labelValues("GET", "200").inc();
    requestCount.labelValues("POST", "201").inc(3);

    // ── Verify: data arrived in the OTel pipeline ───────────────────────────

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

    // The Prometheus collect() path still works too (dual-write).
    assertThat(requestCount.collect().getDataPoints()).hasSize(2);
    assertThat(requestCount.labelValues("GET", "200").get()).isEqualTo(2.0);
  }
}
