/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.prometheusclientshim;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
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
 * Benchmarks comparing native Prometheus client performance against the OTel shim (with and without
 * dual-write). The shim modes automatically use the fast path (pre-resolved AggregatorHandle) when
 * available.
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

    @Param({"NATIVE", "SHIM_DUAL_WRITE", "SHIM_OTL_ONLY"})
    String mode;

    private SdkMeterProvider meterProvider;
    private CounterDataPoint counterDataPoint;
    private DistributionDataPoint histogramDataPoint;

    @Setup(Level.Iteration)
    public void setup() {
      OtelMetricBackend.resetForTest();

      PrometheusRegistry registry = new PrometheusRegistry();

      if (!"NATIVE".equals(mode)) {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        meterProvider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
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
    state.counterDataPoint.inc();
  }

  @Benchmark
  @Threads(1)
  public void histogramObserve(BenchmarkState state) {
    state.histogramDataPoint.observe(0.123);
  }
}
