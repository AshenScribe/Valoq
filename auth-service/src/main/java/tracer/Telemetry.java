/*
 * MIT License
 *
 * Copyright (c) 2026 Valoq
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package tracer;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.instrumentation.runtimemetrics.Cpu;
import io.opentelemetry.instrumentation.runtimemetrics.GarbageCollector;
import io.opentelemetry.instrumentation.runtimemetrics.MemoryPools;
import io.opentelemetry.instrumentation.runtimemetrics.Threads;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

public final class Telemetry implements AutoCloseable {

    private final OpenTelemetrySdk sdk;

    private Telemetry(OpenTelemetrySdk sdk) {
        this.sdk = sdk;
    }

    public static Telemetry initialize() {
        String serviceName =
                System.getenv().getOrDefault("OTEL_SERVICE_NAME", "valoq-auth-service");

        String endpoint =
                System.getenv()
                        .getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4317");

        Resource resource =
                Resource.getDefault()
                        .merge(
                                Resource.create(
                                        Attributes.of(
                                                AttributeKey.stringKey("service.name"),
                                                serviceName)));

        var exporter = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();

        var tracerProvider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                        .build();

        int prometheusPort =
                Integer.parseInt(System.getenv().getOrDefault("PROMETHEUS_PORT", "9464"));
        var prometheusServer = PrometheusHttpServer.builder().setPort(prometheusPort).build();
        var meterProvider =
                SdkMeterProvider.builder()
                        .setResource(resource)
                        .registerMetricReader(prometheusServer)
                        .build();

        OpenTelemetrySdk sdk =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerProvider)
                        .setMeterProvider(meterProvider)
                        .buildAndRegisterGlobal();

        MemoryPools.registerObservers(sdk);
        GarbageCollector.registerObservers(sdk);
        Threads.registerObservers(sdk);
        Cpu.registerObservers(sdk);
        JvmMetrics.register(sdk);

        return new Telemetry(sdk);
    }

    public OpenTelemetry openTelemetry() {
        return sdk;
    }

    @Override
    public void close() {
        sdk.close();
    }
}
