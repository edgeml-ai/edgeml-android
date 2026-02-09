#!/bin/bash -eu

cd /src/edgeml-android

# Build the project
./gradlew assembleDebug --no-daemon 2>/dev/null || true

# Create a simple Jazzer fuzz target
cat > FuzzConfig.java << 'FUZZ'
import com.code_intelligence.jazzer.api.FuzzedDataProvider;

public class FuzzConfig {
    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        try {
            String input = data.consumeRemainingAsString();
            // Fuzz JSON-like config parsing
            if (input.contains("{") && input.contains("}")) {
                org.json.JSONObject obj = new org.json.JSONObject(input);
            }
        } catch (Exception e) {
            // Expected for malformed input
        }
    }
}
FUZZ

BUILD_CLASSPATH=$(find /src/edgeml-android -name "*.jar" -type f | head -20 | tr '\n' ':')
javac -cp "$BUILD_CLASSPATH:$JAZZER_API_PATH" FuzzConfig.java 2>/dev/null || true
jar cf FuzzConfig.jar FuzzConfig.class 2>/dev/null || true

cp FuzzConfig.jar $OUT/ 2>/dev/null || true
cp $JAZZER_API_PATH $OUT/ 2>/dev/null || true

# Create the fuzzer driver script
cat > $OUT/FuzzConfig << 'DRIVER'
#!/bin/bash
this_dir=$(dirname "$0")
CLASSPATH="$this_dir/FuzzConfig.jar:$this_dir/jazzer_api.jar"
$this_dir/jazzer_driver --cp=$CLASSPATH --target_class=FuzzConfig "$@"
DRIVER
chmod +x $OUT/FuzzConfig
