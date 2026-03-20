@file:Suppress("unused")

package ai.octomil

// Re-export all public SDK types so consumers can write `import ai.octomil.*`
// instead of importing from individual sub-packages.
//
// Kotlin typealiases don't forward nested/inner classes, so sealed-class
// subtypes and Builder classes are flattened as top-level aliases too.

// ── android ──
typealias AttachmentResolver = ai.octomil.android.AttachmentResolver
typealias LocalAttachment = ai.octomil.android.LocalAttachment

// ── api ──
typealias OctomilApiFactory = ai.octomil.api.OctomilApiFactory

// ── chat ──
typealias ChatThread = ai.octomil.chat.ChatThread
typealias GenerationMetrics = ai.octomil.chat.GenerationMetrics
typealias ThreadMessage = ai.octomil.chat.ThreadMessage

// ── client ──
typealias OctomilClient = ai.octomil.client.OctomilClient
typealias OctomilClientBuilder = ai.octomil.client.OctomilClient.Builder

// ── config ──
typealias AuthConfig = ai.octomil.config.AuthConfig
typealias AuthConfigOrgApiKey = ai.octomil.config.AuthConfig.OrgApiKey
typealias OctomilConfig = ai.octomil.config.OctomilConfig
typealias OctomilConfigBuilder = ai.octomil.config.OctomilConfig.Builder

// ── discovery ──
typealias DiscoveryManager = ai.octomil.discovery.DiscoveryManager

// ── errors ──
typealias OctomilErrorCode = ai.octomil.errors.OctomilErrorCode
typealias OctomilException = ai.octomil.errors.OctomilException

// ── generated (contract enums) ──
typealias DeliveryMode = ai.octomil.generated.DeliveryMode
typealias Modality = ai.octomil.generated.Modality
typealias ModelCapability = ai.octomil.generated.ModelCapability

// ── manifest ──
typealias AppManifest = ai.octomil.manifest.AppManifest
typealias AppModelEntry = ai.octomil.manifest.AppModelEntry
typealias ModelRef = ai.octomil.manifest.ModelRef
typealias ModelRefId = ai.octomil.manifest.ModelRef.Id
typealias ModelRefCapability = ai.octomil.manifest.ModelRef.Capability

// ── pairing UI ──
// PairingScreen is a @Composable fun — needs direct import ai.octomil.pairing.ui.PairingScreen
typealias PairingState = ai.octomil.pairing.ui.PairingState
typealias PairingStateSuccess = ai.octomil.pairing.ui.PairingState.Success
typealias PairingViewModel = ai.octomil.pairing.ui.PairingViewModel
typealias PairingViewModelFactory = ai.octomil.pairing.ui.PairingViewModel.Factory

// ── responses ──
typealias ContentPart = ai.octomil.responses.ContentPart
typealias ContentPartText = ai.octomil.responses.ContentPart.Text
typealias ContentPartImage = ai.octomil.responses.ContentPart.Image
typealias InputItem = ai.octomil.responses.InputItem
typealias InputItemUser = ai.octomil.responses.InputItem.User
typealias ResponseRequest = ai.octomil.responses.ResponseRequest
typealias ResponseStreamEvent = ai.octomil.responses.ResponseStreamEvent
typealias ResponseStreamEventTextDelta = ai.octomil.responses.ResponseStreamEvent.TextDelta
typealias ResponseStreamEventDone = ai.octomil.responses.ResponseStreamEvent.Done

// ── runtime ──
typealias ModelKeepAliveService = ai.octomil.runtime.ModelKeepAliveService

// ── speech ──
typealias SpeechSession = ai.octomil.speech.SpeechSession

// ── text ──
typealias TextPredictionRequest = ai.octomil.text.TextPredictionRequest
