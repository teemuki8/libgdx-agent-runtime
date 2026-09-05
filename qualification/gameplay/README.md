# Gameplay controlled-capture qualification

This GL-free external consumer exercises a real `GameWorld` and `GameplayRuntimeBridge`, registered
runtime input, one controlled tick and its projected health. Input must reach the world's command
drain on the requested tick. It adds no gameplay dependency to published runtime modules.

Build the changed runtime with `./gradlew :runtime-core:jar`. Supply the resulting runtime-core JAR
and trusted gameplay-core/gameplay-runtime JARs (qualified with gameplay 1.2.0) explicitly:

```bash
java --class-path "$runtime_qualification_jar:$gameplay_core_qualification_jar:$gameplay_runtime_qualification_jar" \
  qualification/gameplay/GameplayControlledCapture.java
```

Use Java 25 and absolute paths for these three task-specific variables. Java source-file launch
compiles in memory and adds no dependency overrides, local publication or generated files to the
checkout. Expected output:

```text
PASS gameplay input -> world tick 0 -> runtime frame 1, health=2
```

This requires the **unreleased** runtime capture-ownership API. It is not evidence that runtime
3.0.0 contains the fix, nor a rendered game/physics/platform qualification. Core regressions cover
replay, scenario reset, cancellation, invalid frame ownership and callback failures separately.
