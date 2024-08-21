/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.internal.otlp.metrics;

import io.opentelemetry.exporter.internal.marshal.Marshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.data.MetricData;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

public abstract class MetricReusableDataMarshaler {

  private final Deque<LowAllocationMetricsRequestMarshaler> marshalerPool = new ArrayDeque<>();

  private final MemoryMode memoryMode;

  public MetricReusableDataMarshaler(MemoryMode memoryMode) {
    this.memoryMode = memoryMode;
  }

  public MemoryMode getMemoryMode() {
    return memoryMode;
  }

  public abstract CompletableResultCode doExport(Marshaler exportRequest, int numItems);

  public CompletableResultCode export(Collection<MetricData> metrics) {
    if (memoryMode == MemoryMode.REUSABLE_DATA) {
      LowAllocationMetricsRequestMarshaler marshaler = marshalerPool.poll();
      if (marshaler == null) {
        marshaler = new LowAllocationMetricsRequestMarshaler();
      }
      LowAllocationMetricsRequestMarshaler exportMarshaler = marshaler;
      exportMarshaler.initialize(metrics);
      return doExport(exportMarshaler, metrics.size())
          .whenComplete(
              () -> {
                exportMarshaler.reset();
                marshalerPool.add(exportMarshaler);
              });
    }
    // MemoryMode == MemoryMode.IMMUTABLE_DATA
    MetricsRequestMarshaler request = MetricsRequestMarshaler.create(metrics);
    return doExport(request, metrics.size());
  }
}
