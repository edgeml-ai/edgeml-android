# On-Device Personalization for Android

This guide explains how to implement continuous on-device personalization with the EdgeML Android SDK, enabling adaptive learning similar to Google Keyboard.

## Overview

Personalization allows your app to incrementally improve its ML model based on user interactions, all while keeping data local and private. The model adapts to individual user patterns without sending raw data to servers.

## Key Features

- **Incremental Training**: Update the model with new data as it arrives
- **Background Processing**: Train automatically when conditions are met
- **Privacy-First**: All training happens on-device, no raw data leaves the device
- **Smart Buffering**: Collect samples and trigger training at optimal times
- **Model Versioning**: Maintain personalized versions per user
- **Automatic Uploads**: Periodically share aggregated updates with the server

## Quick Start

### 1. Initialize Personalization Manager

```kotlin
import ai.edgeml.personalization.PersonalizationManager
import ai.edgeml.training.TFLiteTrainer

// Create configuration
val config = EdgeMLConfig(
    apiKey = "your-api-key",
    serverUrl = "https://api.edgeml.ai",
    enableGpuAcceleration = true,
    numThreads = 4,
)

// Create trainer
val trainer = TFLiteTrainer(context, config)

// Create personalization manager
val personalization = PersonalizationManager(
    context = context,
    config = config,
    trainer = trainer,
    bufferSizeThreshold = 50,      // Trigger training after 50 samples
    minSamplesForTraining = 10,    // Minimum samples to start training
    trainingIntervalMs = 300_000,  // Wait 5 minutes between sessions
    autoUploadEnabled = true,      // Auto-upload updates
    uploadThreshold = 10,          // Upload after 10 training sessions
)

// Set base model
val model = client.downloadModel(modelId = "my-model", version = "1.0.0")
personalization.setBaseModel(model)
```

### 2. Collect Training Data

As users interact with your app, collect training samples:

```kotlin
// Example: Text prediction app
suspend fun userAcceptedSuggestion(input: String, accepted: String) {
    // Convert to ML features
    val inputFeatures = createInputFeatures(input)
    val targetFeatures = createTargetFeatures(accepted)

    // Add to personalization buffer
    personalization.addTrainingSample(
        input = inputFeatures,
        target = targetFeatures,
        metadata = mapOf(
            "type" to "suggestion_accepted",
            "context" to "keyboard"
        )
    )
}

// Example: Image classification app
suspend fun userCorrectedPrediction(image: Bitmap, correctLabel: String) {
    val inputFeatures = extractImageFeatures(image)
    val targetFeatures = createLabelFeatures(correctLabel)

    personalization.addTrainingSample(
        input = inputFeatures,
        target = targetFeatures,
        metadata = mapOf("type" to "correction")
    )
}
```

### 3. Use Personalized Model

```kotlin
// Get the current model (personalized if available, base otherwise)
val currentModel = personalization.getCurrentModel()

currentModel?.let { model ->
    // Load into trainer
    trainer.loadModel(model)

    // Use for inference
    val result = trainer.runInference(inputData)
}
```

### 4. Monitor Progress

```kotlin
// Get personalization statistics
val stats = personalization.getStatistics()

Log.i(TAG, "Training Sessions: ${stats.totalTrainingSessions}")
Log.i(TAG, "Samples Trained: ${stats.totalSamplesTrained}")
Log.i(TAG, "Buffered Samples: ${stats.bufferedSamples}")
Log.i(TAG, "Average Loss: ${stats.averageLoss}")
Log.i(TAG, "Is Personalized: ${stats.isPersonalized}")

// Get training history
val history = personalization.getTrainingHistory()
history.forEach { session ->
    Log.i(TAG, "Session at ${Date(session.timestampMs)}: ${session.sampleCount} samples")
}
```

## Real-World Examples

### Example 1: Smart Keyboard

```kotlin
class KeyboardMLManager(
    private val personalization: PersonalizationManager,
    private val trainer: TFLiteTrainer,
) {

    suspend fun userTyped(text: String) {
        // Extract context (previous words)
        val context = getRecentContext()

        // Get next word predictions
        val predictions = predictNextWord(context)

        // Show predictions to user
        showPredictions(predictions)
    }

    suspend fun userAcceptedPrediction(prediction: String) {
        // Learn from this interaction
        val context = getRecentContext()

        val inputFeatures = createFeatures(context)
        val targetFeatures = createFeatures(prediction)

        personalization.addTrainingSample(
            input = inputFeatures,
            target = targetFeatures,
            metadata = mapOf("accepted" to "true")
        )
    }

    suspend fun userIgnoredPredictions() {
        // Could optionally learn from rejections too
        // This helps the model understand user preferences
    }
}
```

### Example 2: Photo Auto-Tag

```kotlin
class PhotoTaggingManager(
    private val personalization: PersonalizationManager,
) {

    suspend fun userViewedPhoto(photo: Photo) {
        // Get model predictions
        val predictions = predict(photo)

        // Show suggested tags
        showSuggestedTags(predictions)
    }

    suspend fun userConfirmedTag(photo: Photo, tag: String) {
        // User confirmed/corrected a tag - learn from this
        val imageFeatures = extractFeatures(photo.bitmap)
        val tagFeatures = createTagFeatures(tag)

        personalization.addTrainingSample(
            input = imageFeatures,
            target = tagFeatures,
            metadata = mapOf(
                "photo_id" to photo.id,
                "confirmed" to "true"
            )
        )
    }
}
```

### Example 3: Email Smart Reply

```kotlin
class SmartReplyManager(
    private val personalization: PersonalizationManager,
    private val trainer: TFLiteTrainer,
) {

    suspend fun generateReplies(email: Email): List<String> {
        // Use personalized model to generate replies
        val context = extractEmailContext(email)
        val replies = model.generateReplies(context)
        return replies
    }

    suspend fun userSelectedReply(reply: String, email: Email) {
        // Learn from user's choice
        val emailFeatures = extractEmailContext(email)
        val replyFeatures = createReplyFeatures(reply)

        personalization.addTrainingSample(
            input = emailFeatures,
            target = replyFeatures,
            metadata = mapOf("email_type" to email.category)
        )
    }
}
```

## Advanced Configuration

### Custom Training Triggers

```kotlin
// Manual training control
class CustomPersonalizationManager(
    private val personalization: PersonalizationManager,
) {

    suspend fun trainWhenIdle() {
        // Check if device is idle
        if (!isDeviceIdle()) return

        // Force training
        personalization.forceTraining()
    }

    suspend fun trainOnWiFi() {
        // Check network status
        if (!isConnectedToWiFi()) return

        personalization.forceTraining()
    }

    private fun isDeviceIdle(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isDeviceIdleMode
    }

    private fun isConnectedToWiFi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
```

### Buffer Management

```kotlin
// Clear buffer without training (e.g., if user opts out)
personalization.clearBuffer()

// Reset all personalization (e.g., user requests)
personalization.resetPersonalization()
```

### Background Training with WorkManager

```kotlin
class PersonalizationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val personalization = getPersonalizationManager()

        return try {
            // Force training
            personalization.forceTraining().getOrThrow()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Training failed", e)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(false)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<PersonalizationWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "personalization_training",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
```

### Privacy Controls

```kotlin
// Let users control personalization
class PrivacySettings(
    private val personalization: PersonalizationManager,
) {

    suspend fun disablePersonalization() {
        // Clear buffer
        personalization.clearBuffer()

        // Reset to base model
        personalization.resetPersonalization()
    }

    suspend fun exportPersonalizationData(): PersonalizationStatistics {
        // Let users see what data is used for personalization
        return personalization.getStatistics()
    }
}
```

## Best Practices

### 1. Buffer Size Tuning

- **Small buffers (10-30)**: Faster adaptation, more frequent training
- **Large buffers (50-100)**: More stable updates, less battery use
- **Very large (100+)**: Batch training, suitable for powerful devices

### 2. Training Frequency

- **Frequent (1-5 min)**: Real-time adaptation (e.g., keyboard)
- **Moderate (5-15 min)**: Balanced approach
- **Infrequent (30-60 min)**: Battery-conscious apps

### 3. Learning Rate

```kotlin
// For incremental updates, use small learning rates
val config = TrainingConfig(
    epochs = 1,
    batchSize = 32,
    learningRate = 0.0001f  // Small = stable personalization
)
```

### 4. Battery Optimization

```kotlin
// Train only when charging or battery > 30%
fun shouldAllowTraining(context: Context): Boolean {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val isCharging = batteryManager.isCharging

    return isCharging || batteryLevel > 30
}
```

### 5. Storage Management

```kotlin
// Periodically clean up old personalized models
suspend fun cleanupOldModels(personalization: PersonalizationManager) {
    val stats = personalization.getStatistics()

    // If personalization isn't helping, reset it
    if (stats.averageAccuracy != null && stats.averageAccuracy < baselineAccuracy) {
        personalization.resetPersonalization()
    }
}
```

## Privacy Considerations

### What Stays on Device

✅ **Always Local**:
- Raw training data (user inputs, corrections)
- Personalized model weights
- Training buffer contents
- Individual training samples

### What Can Be Uploaded

⚠️ **Only Aggregated Updates**:
- Weight deltas (not raw data)
- Training statistics (counts, averages)
- Model performance metrics

### User Controls

Provide users with:
- Option to disable personalization
- Ability to reset personalized model
- Visibility into training statistics
- Export of personalization data

```kotlin
// Example settings screen with Jetpack Compose
@Composable
fun PersonalizationSettingsScreen(
    personalization: PersonalizationManager,
) {
    var personalizationEnabled by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf<PersonalizationStatistics?>(null) }

    LaunchedEffect(Unit) {
        stats = personalization.getStatistics()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Personalization", style = MaterialTheme.typography.h5)

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable Model Personalization")
            Switch(
                checked = personalizationEnabled,
                onCheckedChange = { enabled ->
                    personalizationEnabled = enabled
                    if (!enabled) {
                        lifecycleScope.launch {
                            personalization.clearBuffer()
                        }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        stats?.let {
            Text("Statistics", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))

            Text("Training Sessions: ${it.totalTrainingSessions}")
            Text("Samples: ${it.totalSamplesTrained}")
            Text("Buffered: ${it.bufferedSamples}")

            if (it.isPersonalized) {
                Text(
                    "Model: Personalized",
                    color = MaterialTheme.colors.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                lifecycleScope.launch {
                    personalization.resetPersonalization()
                    stats = personalization.getStatistics()
                }
            },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.error
            )
        ) {
            Text("Reset Personalization")
        }
    }
}
```

## Troubleshooting

### Training Not Triggering

**Problem**: Samples are buffered but training doesn't start.

**Check**:
- Buffer size threshold: `stats.bufferedSamples >= bufferSizeThreshold`
- Minimum samples: `stats.bufferedSamples >= minSamplesForTraining`
- Training interval: Check `lastTrainingTimeMs`
- Training in progress: Only one session at a time

### Poor Personalization Quality

**Problem**: Model quality degrades with personalization.

**Solutions**:
- Reduce learning rate (try 0.00001f)
- Increase buffer size for more stable updates
- Reset personalization if accuracy drops too much
- Validate training data quality

### High Battery Usage

**Problem**: Personalization drains battery.

**Solutions**:
- Increase training interval
- Increase buffer size
- Train only when charging
- Reduce epochs (use 1)
- Use WorkManager with battery constraints

## References

- [TensorFlow Lite Documentation](https://www.tensorflow.org/lite)
- [EdgeML Federated Learning Guide](FEDERATED_LEARNING.md)
- [Privacy Best Practices](PRIVACY.md)
- [Android WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
