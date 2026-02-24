/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.sdk.metrics.ExemplarFilter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks comparing native Prometheus client performance against the OTel shim. Modes isolate
 * individual cost components:
 *
 * <ul>
 *   <li>NATIVE — Prometheus client only, no OTel
 *   <li>SHIM_OTL_ONLY — shim with fast path + exemplar support (Context.current())
 *   <li>SHIM_NO_EXEMPLARS — shim with fast path, exemplars off (isolates Context.current() cost)
 *   <li>SHIM_DUAL_WRITE — shim with both OTel and native Prometheus writes
 *   <li>RAW_OTEL — OTel SDK directly (no shim layer), measures SDK overhead baseline
 * </ul>
 *
 * <p>Run with: {@code ./gradlew :prometheus-client-shim:jmh}
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class ShimBenchmark {

  @State(Scope.Benchmark)
  public static class BenchmarkState {

    @Param({"NATIVE", "SHIM_OTL_ONLY", "SHIM_NO_EXEMPLARS", "SHIM_DUAL_WRITE", "RAW_OTEL"})
    String mode;

    private SdkMeterProvider meterProvider;
    private CounterDataPoint counterDataPoint;
    private DistributionDataPoint histogramDataPoint;

    // For RAW_OTEL mode
    private DoubleCounter rawOtelCounter;
    private DoubleHistogram rawOtelHistogram;
    private Attributes rawOtelAttributes;

    @Setup(Level.Iteration)
    public void setup() {
      OtelMetricBackend.resetForTest();

      if ("RAW_OTEL".equals(mode)) {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        rawOtelCounter =
            meterProvider
                .get("benchmark")
                .counterBuilder("benchmark_counter_total")
                .ofDoubles()
                .build();
        rawOtelHistogram =
            meterProvider.get("benchmark").histogramBuilder("benchmark_histogram").build();
        rawOtelAttributes =
            Attributes.of(
                AttributeKey.stringKey("method"), "GET", AttributeKey.stringKey("status"), "200");
        return;
      }

      PrometheusRegistry registry = new PrometheusRegistry();

      if (!"NATIVE".equals(mode)) {
        SdkMeterProviderBuilder builder = SdkMeterProvider.builder();

        if ("SHIM_NO_EXEMPLARS".equals(mode)) {
          builder.setExemplarFilter(ExemplarFilter.alwaysOff());
        }

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        meterProvider = builder.registerMetricReader(reader).build();
        boolean dualWrite = "SHIM_DUAL_WRITE".equals(mode);
        OtelMetricBackend.configure(meterProvider, dualWrite);
      }

      Counter counter =
          Counter.builder()
              .name("benchmark_counter_total")
              .help("Benchmark counter")
              .labelNames("method", "status")
              .register(registry);
      counterDataPoint = counter.labelValues("GET", "200");

      Histogram histogram =
          Histogram.builder()
              .name("benchmark_histogram")
              .help("Benchmark histogram")
              .classicOnly()
              .labelNames("method")
              .register(registry);
      histogramDataPoint = histogram.labelValues("GET");
    }

    @TearDown
    public void tearDown() {
      if (meterProvider != null) {
        meterProvider.shutdown().join(10, TimeUnit.SECONDS);
      }
      OtelMetricBackend.resetForTest();
    }
  }

  @Benchmark
  @Threads(1)
  public void counterInc(BenchmarkState state) {
    if ("RAW_OTEL".equals(state.mode)) {
      state.rawOtelCounter.add(1.0, state.rawOtelAttributes);
    } else {
      state.counterDataPoint.inc();
    }
  }

  @Benchmark
  @Threads(1)
  public void histogramObserve(BenchmarkState state) {
    if ("RAW_OTEL".equals(state.mode)) {
      state.rawOtelHistogram.record(0.123, state.rawOtelAttributes);
    } else {
      state.histogramDataPoint.observe(0.123);
    }
  }
}
