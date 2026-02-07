# TensorFlow Lite Weight Extraction for Federated Learning

This guide explains how to extract model weights and deltas from TensorFlow Lite models for federated learning.

## The Challenge

TensorFlow Lite provides access to model tensors through the Interpreter API, but extracting weights for federated learning requires careful handling of tensor data and serialization.

## Solution Approaches

### 1. **TensorFlow Lite Interpreter API** (Recommended)

The SDK uses TensorFlow Lite's Interpreter API to access model tensors directly:

```kotlin
val trainer = TFLiteTrainer(context, config)

// Load model
trainer.loadModel(model).getOrThrow()

// Prepare training data
val trainingData = listOf(
    floatArrayOf(/* input */) to floatArrayOf(/* label */),
    // ... more samples
)
val dataProvider = InMemoryTrainingDataProvider(trainingData)

// Train model
val trainingConfig = TrainingConfig(
    epochs = 3,
    batchSize = 32,
    learningRate = 0.001f,
)
val result = trainer.train(dataProvider, trainingConfig).getOrThrow()

// Extract weight update (delta or full weights)
val weightUpdate = trainer.extractWeightUpdate(result).getOrThrow()

// Upload to server
client.uploadWeights(weightUpdate)
```

### 2. **Delta Extraction**

The SDK automatically computes weight deltas (updated - original) when possible:

```kotlin
// The extractWeightUpdate method automatically:
// 1. Tries to extract delta (updated - original) if original model is available
// 2. Falls back to full weights if delta extraction fails
val weightUpdate = trainer.extractWeightUpdate(result).getOrThrow()

// weightUpdate.weightsData contains either:
// - Delta (updated - original) if extraction succeeded
// - Full weights if delta extraction not supported
```

### 3. **Custom Weight Extraction**

For advanced use cases, extend `WeightExtractor`:

```kotlin
class CustomWeightExtractor : WeightExtractor() {
    suspend fun extractCustomWeights(modelPath: String): ByteArray {
        // Custom implementation using your model's structure
        val interpreter = Interpreter(File(modelPath))

        // Extract parameters based on your model architecture
        val weights = mutableMapOf<String, FloatArray>()
        // ... extraction logic ...

        interpreter.close()
        return serializeToPyTorch(weights)
    }
}
```

## Serialization Format

The SDK serializes weights to a PyTorch-compatible format:

```
Header:
  - Magic number: 0x50545448 ("PTTH")
  - Version: 1
  - Parameter count: uint32

For each parameter:
  - Name length: uint32
  - Name: UTF-8 string
  - Shape count: uint32
  - Shape dimensions: uint32[]
  - Data type: uint32 (0=float32, 1=float64, 2=int32)
  - Data length: uint32
  - Data: raw bytes
```

Server-side deserialization (Python):

```python
import struct
import torch

def deserialize_android_weights(data: bytes) -> dict:
    """Deserialize weights from Android SDK format."""
    offset = 0

    # Read header
    magic, version, param_count = struct.unpack('>III', data[offset:offset+12])
    offset += 12

    if magic != 0x50545448:
        raise ValueError("Invalid magic number")

    weights = {}
    for _ in range(param_count):
        # Read parameter name
        name_len, = struct.unpack('>I', data[offset:offset+4])
        offset += 4
        name = data[offset:offset+name_len].decode('utf-8')
        offset += name_len

        # Read shape
        shape_count, = struct.unpack('>I', data[offset:offset+4])
        offset += 4
        shape = struct.unpack(f'>{shape_count}I', data[offset:offset+shape_count*4])
        offset += shape_count * 4

        # Read data type
        dtype, = struct.unpack('>I', data[offset:offset+4])
        offset += 4

        # Read data
        data_len, = struct.unpack('>I', data[offset:offset+4])
        offset += 4
        tensor_data = data[offset:offset+data_len]
        offset += data_len

        # Convert to torch tensor
        import numpy as np
        array = np.frombuffer(tensor_data, dtype=np.float32)
        tensor = torch.from_numpy(array).reshape(shape)
        weights[name] = tensor

    return weights
```

## Best Practices

### 1. Model Preparation

- Use TensorFlow Lite models with accessible tensors
- Test weight extraction before deploying to devices
- Consider model quantization for smaller updates

### 2. Efficient Training

- Use small batch sizes (8-32) for on-device training
- Limit epochs (1-3) to reduce battery drain
- Extract deltas immediately after training

### 3. Bandwidth Optimization

- Deltas are ~10-50% the size of full weights
- Apply quantization (float32 → float16) for 50% size reduction
- Use compression (gzip) for additional 30-40% reduction

```kotlin
// Enable compression in EdgeMLConfig
val config = EdgeMLConfig(
    apiKey = "your-api-key",
    serverUrl = "https://api.edgeml.ai",
    enableGpuAcceleration = true,
    numThreads = 4,
)
```

### 4. Error Handling

Always handle weight extraction failures gracefully:

```kotlin
try {
    val weightUpdate = trainer.extractWeightUpdate(result).getOrThrow()
    client.uploadWeights(weightUpdate)
} catch (e: WeightExtractionException) {
    // Log failure, retry with different approach
    Log.e(TAG, "Weight extraction failed: ${e.message}")

    // Optionally: upload metrics without weights
    client.uploadMetrics(result)
}
```

## TensorFlow Lite Training Support

**Note**: TensorFlow Lite's on-device training support is currently limited. The SDK provides:

1. **Tensor Access**: Extract weights from any TFLite model
2. **Delta Computation**: Compute weight differences automatically
3. **Training Placeholder**: Framework for integrating custom training loops

For production on-device training, you can:

- Use **TensorFlow Lite Model Maker** to create trainable models
- Integrate **custom training loops** with gradient computation
- Use **transfer learning** with frozen base layers

Example with TFLite Model Maker:

```python
import tensorflow as tf
from tensorflow_lite_support.custom_ops import register_custom_ops

# Create a trainable TFLite model
model = tf.keras.Sequential([
    tf.keras.layers.Dense(128, activation='relu', input_shape=(784,)),
    tf.keras.layers.Dense(10, activation='softmax'),
])

# Convert to TFLite with training support
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS,
    tf.lite.OpsSet.SELECT_TF_OPS,
]
converter.experimental_enable_resource_variables = True

tflite_model = converter.convert()

# Save model
with open('model.tflite', 'wb') as f:
    f.write(tflite_model)
```

## Troubleshooting

### "Failed to extract weight delta"

**Cause**: Model tensors cannot be accessed or have incompatible formats.

**Solution**: Check that your model is a standard TFLite model and not encrypted or obfuscated.

### "Tensor extraction not supported"

**Cause**: TFLite model format doesn't expose tensors.

**Fallback**: SDK automatically falls back to full weight serialization.

### Large Upload Sizes

**Problem**: Full weights are too large for cellular upload.

**Solutions**:
1. Use delta extraction (requires original model)
2. Enable compression in configuration
3. Wait for Wi-Fi using `WorkManager` constraints

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .setRequiresCharging(false)
    .setRequiresBatteryNotLow(true)
    .build()

WorkManager.getInstance(context)
    .enqueueUniqueWork(
        "weight_upload",
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<WeightUploadWorker>()
            .setConstraints(constraints)
            .build()
    )
```

## Testing Weight Extraction

Test your model's weight extraction capability:

```kotlin
val extractor = WeightExtractor()

try {
    // Test delta extraction
    val delta = extractor.extractWeightDelta(
        originalModelPath = originalPath,
        updatedModelPath = updatedPath,
    )
    Log.i(TAG, "✅ Delta extraction works: ${delta.size} bytes")
} catch (e: Exception) {
    Log.w(TAG, "⚠️ Delta extraction failed: ${e.message}")

    // Test full weight extraction
    try {
        val weights = extractor.extractFullWeights(
            modelPath = updatedPath,
        )
        Log.i(TAG, "✅ Full weight extraction works: ${weights.size} bytes")
    } catch (e: Exception) {
        Log.e(TAG, "❌ Weight extraction not supported")
    }
}
```

## Performance Considerations

### Memory Usage

Weight extraction requires loading the entire model into memory:

- **Small models** (< 10 MB): No issues on modern devices
- **Medium models** (10-50 MB): Watch for memory pressure
- **Large models** (> 50 MB): Consider extraction in background

```kotlin
// Extract weights in background
lifecycleScope.launch(Dispatchers.IO) {
    val weightUpdate = trainer.extractWeightUpdate(result).getOrThrow()
    // Upload...
}
```

### Battery Impact

Training and weight extraction consume battery:

- **Training**: 2-5% per session (1-3 epochs)
- **Weight Extraction**: < 1% (typically 100-500ms)
- **Upload**: 1-3% (depends on size and network)

Minimize impact:

```kotlin
// Check battery level before training
val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

if (batteryLevel > 30) {
    trainer.train(dataProvider, config)
}
```

## References

- [TensorFlow Lite Documentation](https://www.tensorflow.org/lite)
- [TensorFlow Lite Model Maker](https://www.tensorflow.org/lite/models/modify/model_maker)
- [On-Device Training Guide](https://www.tensorflow.org/lite/examples/on_device_training/overview)
- [EdgeML Federated Learning Guide](../docs/FEDERATED_LEARNING.md)
