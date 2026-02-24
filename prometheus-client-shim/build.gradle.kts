plugins {
  id("otel.java-conventions")
  id("otel.jmh-conventions")
}

description = "OpenTelemetry Prometheus Client Shim"
otelJava.moduleName.set("io.opentelemetry.prometheusclientshim")

dependencies {
  implementation("io.prometheus:prometheus-metrics-core")
  implementation("io.prometheus:prometheus-metrics-model")

  api(project(":api:all"))
  implementation(project(":sdk:metrics"))

  testImplementation(project(":sdk:testing"))

  jmh("io.prometheus:prometheus-metrics-core")
  jmh(project(":sdk:metrics"))
  jmh(project(":sdk:testing"))
}
