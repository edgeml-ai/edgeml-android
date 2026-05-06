package ai.octomil.generated

// Auto-generated from octomil-contracts runtime_planner schemas. Do not edit.

data class RuntimePlannerJsonValue(val value: String? = null)

data class AppResolution(
    val app_id: String? = null,
    val app_slug: String? = null,
    val capability: String? = null,
    val routing_policy: String? = null,
    val selected_model: String? = null,
    val selected_model_variant_id: String? = null,
    val selected_model_version: String? = null,
    val artifact_candidates: List<RuntimeArtifactPlan>? = null,
    val preferred_engines: List<String>? = null,
    val fallback_policy: String? = null,
    val plan_ttl_seconds: Int? = null,
    val public_client_allowed: Boolean? = null,
)

data class CandidateGate(
    val code: String? = null,
    val required: Boolean? = null,
    val threshold_number: Double? = null,
    val threshold_string: String? = null,
    val window_seconds: Int? = null,
    val source: String? = null,
    val gate_class: String? = null,
    val evaluation_phase: String? = null,
    val fallback_eligible: Boolean? = null,
    val blocking_default: Boolean? = null,
)

data class DeviceRuntimeProfile(
    val sdk: String? = null,
    val sdk_version: String? = null,
    val platform: String? = null,
    val arch: String? = null,
    val os_version: String? = null,
    val chip: String? = null,
    val ram_total_bytes: Int? = null,
    val gpu_core_count: Int? = null,
    val accelerators: List<String>? = null,
    val installed_runtimes: List<InstalledRuntime>? = null,
    val supported_gate_codes: List<String>? = null,
)

data class InstalledRuntime(
    val engine: String? = null,
    val version: String? = null,
    val available: Boolean? = null,
    val accelerator: String? = null,
    val metadata: Map<String, RuntimePlannerJsonValue>? = null,
)

data class RouteAttempt(
    val index: Int? = null,
    val locality: String? = null,
    val mode: String? = null,
    val engine: String? = null,
    val artifact: AttemptArtifact? = null,
    val status: String? = null,
    val stage: String? = null,
    val gate_results: List<GateResult>? = null,
    val reason: Map<String, RuntimePlannerJsonValue>? = null,
)

data class AttemptArtifact(
    val id: String? = null,
    val digest: String? = null,
    val cache: Map<String, RuntimePlannerJsonValue>? = null,
)

data class GateResult(
    val code: String? = null,
    val status: String? = null,
    val observed_number: Double? = null,
    val threshold_number: Double? = null,
    val threshold_string: String? = null,
    val reason_code: String? = null,
    val gate_class: String? = null,
    val evaluation_phase: String? = null,
    val required: Boolean? = null,
    val fallback_eligible: Boolean? = null,
    val observed_string: String? = null,
    val safe_metadata: Map<String, RuntimePlannerJsonValue>? = null,
)

data class RouteEvent(
    val route_id: String? = null,
    val request_id: String? = null,
    val plan_id: String? = null,
    val app_id: String? = null,
    val app_slug: String? = null,
    val deployment_id: String? = null,
    val experiment_id: String? = null,
    val variant_id: String? = null,
    val capability: String? = null,
    val policy: String? = null,
    val planner_source: String? = null,
    val model_ref: String? = null,
    val model_ref_kind: ModelRefKind? = null,
    val selected_locality: String? = null,
    val final_locality: String? = null,
    val final_mode: String? = null,
    val engine: String? = null,
    val artifact_id: String? = null,
    val cache_status: String? = null,
    val fallback_used: Boolean? = null,
    val fallback_trigger_code: String? = null,
    val fallback_trigger_stage: String? = null,
    val candidate_attempts: Int? = null,
    val attempt_details: List<RouteEventAttemptDetail>? = null,
    val ttft_ms: Double? = null,
    val tokens_per_second: Double? = null,
    val total_tokens: Int? = null,
    val duration_ms: Double? = null,
)

data class RouteEventAttemptDetail(
    val index: Int? = null,
    val locality: String? = null,
    val mode: String? = null,
    val engine: String? = null,
    val status: String? = null,
    val stage: String? = null,
    val gate_summary: Map<String, RuntimePlannerJsonValue>? = null,
    val reason_code: String? = null,
)

data class RouteMetadata(
    val status: String? = null,
    val execution: RouteExecution? = null,
    val model: RouteModel? = null,
    val artifact: RouteArtifact? = null,
    val planner: PlannerInfo? = null,
    val fallback: FallbackInfo? = null,
    val attempts: List<RouteAttempt>? = null,
    val reason: RouteReason? = null,
)

data class RouteExecution(
    val locality: String? = null,
    val mode: String? = null,
    val engine: String? = null,
)

data class RouteModel(
    val requested: RouteModelRequested? = null,
    val resolved: RouteModelResolved? = null,
)

data class RouteModelRequested(
    val ref: String? = null,
    val kind: ModelRefKind? = null,
    val capability: String? = null,
)

data class RouteModelResolved(
    val id: String? = null,
    val slug: String? = null,
    val version_id: String? = null,
    val variant_id: String? = null,
)

data class RouteArtifact(
    val id: String? = null,
    val version: String? = null,
    val format: String? = null,
    val digest: String? = null,
    val cache: ArtifactCache? = null,
)

data class ArtifactCache(
    val status: String? = null,
    val managed_by: String? = null,
)

data class PlannerInfo(
    val source: String? = null,
)

data class FallbackInfo(
    val used: Boolean? = null,
    val from_attempt: Int? = null,
    val to_attempt: Int? = null,
    val trigger: FallbackTrigger? = null,
)

data class FallbackTrigger(
    val code: String? = null,
    val stage: String? = null,
    val message: String? = null,
    val gate_code: String? = null,
    val gate_class: String? = null,
    val evaluation_phase: String? = null,
    val candidate_index: Int? = null,
    val output_visible_before_failure: Boolean? = null,
)

data class RouteReason(
    val code: String? = null,
    val message: String? = null,
)

data class RuntimeBenchmarkSubmission(
    val source: String? = null,
    val model: String? = null,
    val model_version: String? = null,
    val artifact_digest: String? = null,
    val capability: String? = null,
    val engine: String? = null,
    val engine_version: String? = null,
    val quantization: String? = null,
    val device: DeviceRuntimeProfile? = null,
    val benchmark_tokens: Int? = null,
    val ttft_ms: Double? = null,
    val tokens_per_second: Double? = null,
    val latency_ms: Double? = null,
    val peak_memory_bytes: Int? = null,
    val success: Boolean? = null,
    val error_code: String? = null,
    val metadata: Map<String, RuntimePlannerJsonValue>? = null,
)

data class RuntimeBenchmarkSubmissionResponse(
    val id: String? = null,
    val accepted: Boolean? = null,
    val created_at: String? = null,
)

data class RuntimeDefaultsResponse(
    val default_engines: Map<String, RuntimePlannerJsonValue>? = null,
    val supported_capabilities: List<String>? = null,
    val supported_policies: List<String>? = null,
    val plan_ttl_seconds: Int? = null,
)

data class RuntimePlanRequest(
    val model: String? = null,
    val capability: String? = null,
    val routing_policy: String? = null,
    val app_id: String? = null,
    val app_slug: String? = null,
    val org_id: String? = null,
    val device: DeviceRuntimeProfile? = null,
    val allow_cloud_fallback: Boolean? = null,
)

data class RuntimePlanResponse(
    val plan_schema_version: Int? = null,
    val model: String? = null,
    val capability: String? = null,
    val policy: String? = null,
    val candidates: List<RuntimeCandidatePlan>? = null,
    val fallback_candidates: List<RuntimeCandidatePlan>? = null,
    val plan_ttl_seconds: Int? = null,
    val fallback_allowed: Boolean? = null,
    val public_client_allowed: Boolean? = null,
    val server_generated_at: String? = null,
    val plan_correlation_id: String? = null,
    val app_resolution: AppResolution? = null,
    val resolution: ModelResolution? = null,
)

data class ModelResolution(
    val ref_kind: ModelRefKind? = null,
    val original_ref: String? = null,
    val resolved_model: String? = null,
    val deployment_id: String? = null,
    val deployment_key: String? = null,
    val experiment_id: String? = null,
    val variant_id: String? = null,
    val variant_name: String? = null,
    val capability: String? = null,
    val routing_policy: String? = null,
)

data class RuntimeCandidatePlan(
    val locality: String? = null,
    val engine: String? = null,
    val engine_version_constraint: String? = null,
    val artifact: RuntimeArtifactPlan? = null,
    val priority: Int? = null,
    val confidence: Double? = null,
    val reason: String? = null,
    val benchmark_required: Boolean? = null,
    val gates: List<CandidateGate>? = null,
    val delivery_mode: String? = null,
    val prepare_required: Boolean? = null,
    val prepare_policy: String? = null,
)

data class RuntimeArtifactPlan(
    val model_id: String? = null,
    val artifact_id: String? = null,
    val model_version: String? = null,
    val format: String? = null,
    val quantization: String? = null,
    val uri: String? = null,
    val digest: String? = null,
    val size_bytes: Int? = null,
    val min_ram_bytes: Int? = null,
    val required_files: List<String>? = null,
    val download_urls: List<Map<String, RuntimePlannerJsonValue>>? = null,
    val manifest_uri: String? = null,
)
