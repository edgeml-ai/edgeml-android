#!/bin/bash -eu

# Fuzz target using only JDK + Jazzer API (no Android SDK needed)
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

# Compile and package as _deploy.jar (oss-fuzz JVM convention)
javac -cp "$JAZZER_API_PATH" FuzzEdgeML.java
jar cf "$OUT/FuzzEdgeML_deploy.jar" FuzzEdgeML.class
