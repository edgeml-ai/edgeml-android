# Android SDK Bloat Reduction Track

Reviewer: @tai

## Goal

Clarify Android SDK packaging boundaries and keep the published SDK, generated contracts, app composite builds, and docs aligned.

## Findings

- The sibling SDK is newer than the SDK nested inside the app checkout.
- Published coordinates have drifted: app dependencies reference old `ai.octomil` coordinates while the SDK publishes newer `com.octomil` coordinates.
- External runtime AARs are now gated in the sibling SDK but hard-required by the older nested copy.
- Generated contract metadata and generated capabilities differ across SDK copies.
- The main SDK artifact includes Compose/demo/UI surfaces, making the client boundary unclear.
- Ignored Gradle build outputs and crash logs add local noise.

## Proposed Cleanup

- Make the app consume the current sibling SDK or update the nested submodule to the current SDK revision.
- Align published coordinates, README examples, and app dependency substitution.
- Keep external runtime AARs optional by default.
- Regenerate contract code from the current contract and add parity checks.
- Split client and UI/sample surfaces if package size or dependency graph remains high.

## Validation

```bash
./gradlew :octomil:testDebugUnitTest :octomil:lintDebug :octomil:jacocoTestReport --build-cache
./gradlew :samples:assembleDebug --build-cache
rg -n 'OCTOMIL_GROUP|artifactId|includeExternalRuntimes|ModelCapability|contract-version|octomil-client|octomil-ui' .
```
