package ai.octomil.sdk

class OctomilNotInitializedError : IllegalStateException(
    "Octomil client is not initialized. Call client.initialize() first."
)
