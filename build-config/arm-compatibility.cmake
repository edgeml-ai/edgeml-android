# ARM compatibility overlay for Octomil Android builds.
# Disables KLEIDIAI optimized kernels which crash on budget ARM SoCs
# (e.g. Galaxy A17 / Dimensity 7025).
#
# Injected via: cmake -C arm-compatibility.cmake
# Controlled by Gradle property: octomil.disableKleidiai (default: true)

set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
