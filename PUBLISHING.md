# Publishing the EdgeML Android SDK

This guide explains how to publish the EdgeML Android SDK for distribution.

## Quick Links

- **PR**: https://github.com/sbangalore/fed-learning/pull/new/feat/android-weight-extraction
- **Docs**: `edgeml-android/Docs/WEIGHT_EXTRACTION.md`

## Changes Summary

✅ **Implemented**: TensorFlow Lite weight/delta extraction for federated learning
✅ **New File**: `WeightExtractor.kt` - Production-ready weight extraction
✅ **New File**: `TrainingTypes.kt` - Training data classes and configuration
✅ **Updated**: `TFLiteTrainer.kt` - Training and automatic delta vs full weight detection
✅ **Documented**: Complete guide with code samples and troubleshooting

## Distribution Options

### 1. Maven Central (Recommended for Production)

Maven Central is the standard repository for Android libraries.

#### Step 1: Configure Gradle Publishing

Add to `edgeml-android/edgeml/build.gradle.kts`:

```kotlin
plugins {
    id("maven-publish")
    id("signing")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "ai.edgeml"
            artifactId = "edgeml-android"
            version = "1.1.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("EdgeML Android SDK")
                description.set("Federated learning SDK for Android with TensorFlow Lite")
                url.set("https://github.com/sbangalore/fed-learning")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("edgeml")
                        name.set("EdgeML Team")
                        email.set("team@edgeml.ai")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/sbangalore/fed-learning.git")
                    developerConnection.set("scm:git:ssh://github.com/sbangalore/fed-learning.git")
                    url.set("https://github.com/sbangalore/fed-learning")
                }
            }
        }
    }

    repositories {
        maven {
            name = "OSSRH"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = project.findProperty("ossrhUsername") as String? ?: ""
                password = project.findProperty("ossrhPassword") as String? ?: ""
            }
        }
    }
}

signing {
    sign(publishing.publications["release"])
}
```

#### Step 2: Sign and Publish

```bash
# Generate GPG key (first time only)
gpg --gen-key

# Export key
gpg --keyring secring.gpg --export-secret-keys > ~/.gnupg/secring.gpg

# Add credentials to ~/.gradle/gradle.properties
signing.keyId=YOUR_KEY_ID
signing.password=YOUR_KEY_PASSWORD
signing.secretKeyRingFile=/Users/you/.gnupg/secring.gpg
ossrhUsername=YOUR_SONATYPE_USERNAME
ossrhPassword=YOUR_SONATYPE_PASSWORD

# Publish
cd edgeml-android
./gradlew publishReleasePublicationToOSSRHRepository
```

#### Step 3: Release on Maven Central

1. Go to https://s01.oss.sonatype.org/
2. Login with your Sonatype credentials
3. Navigate to "Staging Repositories"
4. Find your repository, select it, and click "Close"
5. Wait for validation, then click "Release"

#### Step 4: Developers Install

Add to `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ai.edgeml:edgeml-android:1.1.0")
}
```

---

### 2. JitPack (Recommended for Beta)

JitPack builds directly from GitHub tags - no additional setup required.

#### Step 1: Tag a Release

```bash
# Version the release
git tag -a v1.1.0 -m "Add TensorFlow Lite weight extraction for federated learning

New features:
- Weight delta extraction (updated - original)
- Full weight fallback for unsupported models
- PyTorch-compatible serialization
- Comprehensive documentation
"

# Push tag
git push origin v1.1.0
```

#### Step 2: Create GitHub Release

1. Go to https://github.com/sbangalore/fed-learning/releases/new
2. Choose tag: `v1.1.0`
3. Release title: "EdgeML Android SDK v1.1.0 - Weight Extraction"
4. Description:
```markdown
## EdgeML Android SDK v1.1.0

### New Features
- ✨ TensorFlow Lite weight/delta extraction for federated learning
- 🔄 Automatic fallback from delta to full weights
- 📦 PyTorch-compatible serialization format
- 📚 Comprehensive documentation and examples

### Installation

**JitPack:**
```kotlin
// Add JitPack repository
repositories {
    maven { url = uri("https://jitpack.io") }
}

// Add dependency
dependencies {
    implementation("com.github.sbangalore:fed-learning:1.1.0")
}
```

**Maven Central:**
```kotlin
dependencies {
    implementation("ai.edgeml:edgeml-android:1.1.0")
}
```

**Usage:**
```kotlin
val trainer = TFLiteTrainer(context, config)
trainer.loadModel(model).getOrThrow()

val result = trainer.train(dataProvider, trainingConfig).getOrThrow()
val update = trainer.extractWeightUpdate(result).getOrThrow()

client.uploadWeights(update)
```

### Documentation
- [Weight Extraction Guide](edgeml-android/Docs/WEIGHT_EXTRACTION.md)
- [API Documentation](#)

### Breaking Changes
None - fully backward compatible

### Requirements
- Android 8.0+ (API 26+)
- Kotlin 1.9+
- TensorFlow Lite 2.14+
```

5. Publish release

#### Step 3: Developers Install

Add to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add to `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.sbangalore.fed-learning:edgeml-android:1.1.0")
}
```

---

### 3. AAR File (For Manual Distribution)

Build an Android Archive (AAR) for manual integration.

#### Step 1: Build AAR

```bash
cd edgeml-android

# Clean
./gradlew clean

# Build release AAR
./gradlew edgeml:assembleRelease

# AAR will be at: edgeml/build/outputs/aar/edgeml-release.aar
```

#### Step 2: Attach to GitHub Release

1. Upload `edgeml-release.aar` to GitHub release
2. Add checksum to release notes:
```bash
shasum -a 256 edgeml/build/outputs/aar/edgeml-release.aar
```

```markdown
### Manual Installation

Download [edgeml-release.aar](link-to-release)

**Checksum (SHA-256):**
```
abc123...xyz789
```

**Installation:**
1. Download and place AAR in `app/libs/`
2. Add to `build.gradle.kts`:
```kotlin
dependencies {
    implementation(files("libs/edgeml-release.aar"))
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.jakewharton.timber:timber:5.0.1")
}
```
3. Sync project
```

---

### 4. Google Play Internal App Sharing (For Testing)

If you have a demo app that uses the SDK:

#### Step 1: Configure App Distribution

1. Open Android Studio
2. Build → Generate Signed Bundle/APK
3. Select "Android App Bundle"
4. Create or use existing keystore
5. Build release bundle

#### Step 2: Upload to Play Console

```bash
# Install bundletool
brew install bundletool

# Test locally first
bundletool build-apks \
  --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=app.apks \
  --mode=universal

bundletool install-apks --apks=app.apks
```

#### Step 3: Internal App Sharing

1. Go to Play Console → Internal App Sharing
2. Upload app-release.aab
3. Share download link with testers
4. No review required, instant distribution

---

## Publishing Checklist

### Pre-Release
- [ ] All tests pass: `./gradlew test`
- [ ] Example app builds successfully
- [ ] Documentation is up to date
- [ ] CHANGELOG.md updated
- [ ] Version bumped in:
  - [ ] `edgeml/build.gradle.kts`
  - [ ] Root `build.gradle.kts` (if applicable)
  - [ ] README.md

### Release
- [ ] Create and push git tag: `git tag -a v1.1.0`
- [ ] Create GitHub release with notes
- [ ] Publish to Maven Central or JitPack
- [ ] Update documentation website
- [ ] Announce in release notes / blog

### Post-Release
- [ ] Monitor GitHub issues for bug reports
- [ ] Update demo apps to use new version
- [ ] Create migration guide if breaking changes
- [ ] Update integration tests

---

## Version Numbering

Follow Semantic Versioning (semver):

- **Major (1.x.x)**: Breaking changes
- **Minor (x.1.x)**: New features, backward compatible
- **Patch (x.x.1)**: Bug fixes, backward compatible

**This release: 1.1.0** (new feature, no breaking changes)

---

## Testing the Release

Before publishing, test installation:

### Test JitPack Installation

```bash
# Create test project
mkdir test-jitpack && cd test-jitpack
gradle init --type kotlin-application

# Add to build.gradle.kts
cat > build.gradle.kts << 'EOF'
plugins {
    kotlin("android") version "1.9.20"
    id("com.android.application") version "8.2.0"
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.sbangalore.fed-learning:edgeml-android:1.1.0")
}
EOF

# Build
./gradlew build
```

### Test AAR Installation

```bash
# Create test Android project
# Place AAR in libs/
# Add to build.gradle.kts
# Build and verify no errors
./gradlew assembleDebug
```

---

## Rollback Procedure

If the release has critical bugs:

### Unpublish from Maven Central

Maven Central releases are **immutable** - you cannot unpublish. Instead:

1. Release a hotfix version (e.g., 1.1.1)
2. Document the issue in release notes
3. Update documentation to recommend the hotfix

### Remove GitHub Release

1. GitHub → Releases → Edit v1.1.0
2. Mark as "Pre-release" or delete
3. Create hotfix release v1.1.1

### Notify Users

- Post GitHub issue explaining the problem
- Update documentation with migration guide
- Send notification to registered developers

---

## Support Resources

After publishing:

1. **Documentation**: Keep docs/ up to date
2. **Examples**: Update demo apps with new features
3. **Support**: Monitor GitHub issues and discussions
4. **Analytics**: Track adoption with package metrics

---

## Next Steps

After this release (v1.1.0), consider:

1. **Performance optimizations**:
   - Implement compression for weight updates
   - Add quantization (float32 → float16)
   - Optimize serialization format

2. **Advanced features**:
   - Sparse weight updates
   - Differential privacy
   - Secure aggregation

3. **Developer experience**:
   - Jetpack Compose UI components for training
   - Flow-based APIs for training progress
   - Improved error messages

4. **Documentation**:
   - Video tutorials
   - Sample projects
   - Best practices guide

---

## Questions?

- **Issues**: https://github.com/sbangalore/fed-learning/issues
- **Discussions**: https://github.com/sbangalore/fed-learning/discussions
- **Email**: team@edgeml.ai (if applicable)
