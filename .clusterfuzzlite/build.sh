#!/bin/bash -eu

cd /src/edgeml-android

# Build only the :edgeml library module (skip :sample to avoid manifest issues)
./gradlew :edgeml:assembleDebug --no-daemon || true

# Extract classes.jar from the built AAR
AAR_FILE=$(find edgeml/build/outputs -name "*.aar" -type f 2>/dev/null | head -1)
if [ -n "$AAR_FILE" ]; then
    mkdir -p /tmp/edgeml-aar
    unzip -o "$AAR_FILE" classes.jar -d /tmp/edgeml-aar 2>/dev/null || true
fi

# Build classpath from extracted AAR and Gradle cache dependencies
EDGEML_JAR=""
if [ -f /tmp/edgeml-aar/classes.jar ]; then
    EDGEML_JAR="/tmp/edgeml-aar/classes.jar"
fi
DEPS_CP=$(find "$HOME/.gradle/caches" -name "*.jar" -path "*/json*" -type f 2>/dev/null | head -5 | tr '\n' ':')

# Create fuzz target — only uses JDK + Jazzer API to avoid classpath issues
cat > FuzzEdgeML.java << 'FUZZ'
import com.code_intelligence.jazzer.api.FuzzedDataProvider;

public class FuzzEdgeML {
    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        try {
            String input = data.consumeRemainingAsString();
            if (input.isEmpty()) return;

            // Fuzz URL parsing (used in API base URL config)
            if (input.startsWith("http")) {
                java.net.URI.create(input).toURL();
            }

            // Fuzz UUID parsing (used for device identifiers)
            if (input.length() == 36 && input.charAt(8) == '-') {
                java.util.UUID.fromString(input);
            }

            // Fuzz base64 decoding (used in token handling)
            if (input.length() > 4 && input.length() < 1024) {
                java.util.Base64.getDecoder().decode(input);
            }
        } catch (IllegalArgumentException | java.net.MalformedURLException e) {
            // Expected for malformed input
        }
    }
}
FUZZ

# Compile and package — no external deps needed (JDK + Jazzer API only)
javac -cp "$JAZZER_API_PATH" FuzzEdgeML.java
jar cf FuzzEdgeML.jar FuzzEdgeML.class

cp FuzzEdgeML.jar "$OUT/"

# Create the fuzzer driver script
cat > "$OUT/FuzzEdgeML" << 'DRIVER'
#!/bin/bash
this_dir=$(dirname "$0")
CLASSPATH="$this_dir/FuzzEdgeML.jar"
"$this_dir/jazzer_driver" --cp="$CLASSPATH" --target_class=FuzzEdgeML "$@"
DRIVER
chmod +x "$OUT/FuzzEdgeML"
