# Deliverables Analyzer

Deliverables Analyzer is a highly scalable RESTful Web Service designed to scan given URLs containing software distributions (like `.zip` or `.jar` files) and return the list of associated builds from PNC and Koji.

## Architecture overview

Because analyzing large deliverables (which may contain thousands of internal artifacts) can be a time-consuming process, the Deliverables Analyzer uses an **asynchronous callback architecture** powered by Java 21 Virtual Threads.

Instead of forcing clients to poll for readiness, the client submits a request with a `callback` definition. The service immediately returns an Operation ID and processes the heavy file downloading, archiving scanning, and HTTP fan-out lookups in the background. Once finished, it automatically pushes the final `AnalysisReport` to the client's callback URL.

---

## Endpoints

### Analyze
Initiates a new background analysis job.

- **Request:** `POST /api/analyze`
- **Payload:** Accepts an `AnalyzePayload` JSON object.
  - `urls` (Required): A list of `http` or `https` URLs pointing to the deliverables.
  - `operationId` (Optional): A custom ID for tracing. If omitted, one is generated.
  - `callback` (Required for results): A JSON object defining the `method` (e.g., `POST`), `uri`, and headers where the final `AnalysisReport` should be sent.
  - `heartbeat` (Optional): A request definition to receive periodic pings while the job is actively running.
  - `config` (Optional): A JSON string to override default `BuildSpecificConfig` settings.
- **Response:** Returns `200 OK` (or `202 Accepted`) immediately with the `operationId` as a string.

### Cancel
Aborts a currently running analysis job across the entire cluster.

- **Request:** (e.g., `DELETE /api/analyze/{id}/cancel`)
- **Behavior:** This endpoint utilizes a "Local-First + Distributed Broadcast" approach. It attempts to instantly interrupt the Virtual Thread running the job locally. If the job is executing on a different Kubernetes pod, it broadcasts a cancellation signal via the Infinispan `cancel-events` cache, ensuring the owning pod receives the event and physically interrupts its own execution threads.

### Version
Returns the current build and deployment information for the service.

- **Request:** `GET /api/version`
- **Response:** Returns `200 OK` with a JSON representation of `ComponentVersion`. This includes the application `name`, `version`, `commit` hash, and `builtOn` timestamp.

### Health
The service supports the standard MicroProfile `/q/health` endpoint (including `/q/health/live` and `/q/health/ready` for Kubernetes probes).

---

## Configuration

Deliverables Analyzer is a Quarkus application and can be configured via standard Quarkus configuration mechanisms: system properties (`-Dkey=value`), environment variables, or the `application.properties` file.

### Core Application Properties (`analyzer.*`)

The following properties configure the core scanning and networking behavior of the application.

| Configuration Key | Description                                                                        | Default / Example |
|-------------------|------------------------------------------------------------------------------------|---------|
| `analyzer.pnc-url` | **(Required)** The Red Hat PNC REST API URL                                        | `http://pnc.localhost` |
| `analyzer.koji-url` | **(Required)** The Red Hat Koji Hub XML-RPC URL                                    | `http://brewhub.localhost` |
| `analyzer.disable-spdx-init` | Disables the SPDX license mapping initialization (useful for faster test startups) | `false` |
| `analyzer.disable-cache` | Disables the persistent Infinispan caches                                          | `false` |
| `analyzer.cache-lifespan` | The lifespan of the cache entries in milliseconds                                  | `3600000` (1 hour) |
| `analyzer.disable-recursion` | Disables recursive scanning of nested archives                                     | `false` |
| `analyzer.archive-extensions`| Comma-separated list of file extensions to process                                 | `dll,dylib,ear,jar,jdocbook,jdocbook-style,kar,plugin,pom,rar,sar,so,war,xml,exe,msi,zip,rpm` |
| `analyzer.excludes` | Regex pattern for files to exclude from analysis                                   | `^(?!.*/pom\.xml$).*/.*\.xml$` |
| `analyzer.pnc-num-threads` | Number of concurrent virtual threads for PNC lookups                               | `10` |
| `analyzer.koji-num-threads` | Number of concurrent virtual threads for Koji lookups                              | `12` |
| `analyzer.koji-multicall-size`| Batch size for Koji XML-RPC multicall requests                                     | `8` |

### Security Properties
| Configuration Key | Description | Default |
|-------------------|-------------|---------|
| `callback.auth.enabled` | Whether to inject Bearer authorization tokens into the headers when firing completed callbacks | `true` |

### Infinispan Cache Configuration
The application relies on Infinispan (`REMOTE` mode) to maintain a highly available cache for checksum deduplication and distributed cancellation events.

| Configuration Key | Description | Example |
|-------------------|-------------|---------|
| `quarkus.infinispan-client.hosts` | Comma-delimited Infinispan server list | `localhost:11222` |
| `quarkus.infinispan-client.username` | Infinispan username | `admin` |
| `quarkus.infinispan-client.password` | Infinispan password | `password` |

The following caches must be provisioned on the Infinispan server:
- `cancel-events` (Used for cluster-wide job termination)
- `pnc-archives` (Used for deduplicating repeated scans of identical archives)
- `koji-archives`
- `koji-builds`
- `koji-rpms`

---

## Building and Running

To build the project and run the test suite via Maven:

```bash
$ mvn clean install
```

---

### OpenTelemetry

| Configuration Key                   | Description                             | Example                                                                |
|-------------------------------------|-----------------------------------------|------------------------------------------------------------------------|
| quarkus.otel.exporter.otlp.endpoint | OTLP endpoint to send telemetry data to | http://localhost:4317                                                  |
| quarkus.otel.resource.attributes    | Attributes to add to the exported trace | "service.name=pnc-deliverable-analyzer,deployment.environment=staging" |

---

### Related Guides

- Infinispan Client ([guide](https://quarkus.io/guides/infinispan-client)): Connect to the Infinispan data grid for distributed caching
- OpenID Connect ([guide](https://quarkus.io/guides/security-openid-connect)): Verify Bearer access tokens and authenticate users with Authorization Code Flow
- Logging JSON ([guide](https://quarkus.io/guides/logging#json-logging)): Add JSON formatter for console logging
