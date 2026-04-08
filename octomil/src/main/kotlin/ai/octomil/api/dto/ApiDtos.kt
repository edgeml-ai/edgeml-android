/**
 * API Data Transfer Objects for the Octomil Android SDK.
 *
 * DTOs are organized by domain in separate files:
 * - [DeviceDtos.kt] — Device registration, heartbeat, groups, policy
 * - [ModelDtos.kt] — Model resolution, metadata, downloads, updates
 * - [TrainingDtos.kt] — Training events, gradient updates, weight uploads
 * - [InferenceDtos.kt] — Inference events and metrics
 * - [RoundDtos.kt] — Federated learning rounds, secure aggregation
 * - [DesiredStateDtos.kt] — Desired state, observed state, device sync
 * - [CommonDtos.kt] — Error and health responses
 * - [OtlpDtos.kt] — OpenTelemetry log/trace DTOs
 * - [TelemetryV2Dtos.kt] — Telemetry v2 event DTOs
 */
package ai.octomil.api.dto
