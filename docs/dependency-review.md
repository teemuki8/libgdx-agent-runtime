# Dependency and license review

Reviewed from the resolved POMs and committed lockfiles on 2026-07-30:

| Scope | Dependency | Version | Declared license |
| --- | --- | --- | --- |
| libGDX adapter | `com.badlogicgames.gdx:gdx` | 1.14.2 | Apache-2.0 |
| protocol | Jackson databind/JDK8/JSR310 | 2.22.1 | Apache-2.0 |
| MCP | Java MCP SDK | 2.0.0 | MIT |
| tests | JUnit Jupiter | 6.1.2 | EPL-2.0 |
| fixture runtime only | SLF4J no-op | 2.0.17 | MIT (parent project declaration) |

The MCP SDK also resolves Reactor, JSON Schema Validator, Jackson 3, SLF4J, and their small
transitive support graph. Protocol uses Jackson 2 under `com.fasterxml`; MCP SDK 2 uses Jackson 3
under `tools.jackson`, so both lines are present without package collision. This increases the MCP
artifact's dependency footprint and should be re-reviewed before release.

`runtime-core` resolves no dependency beyond `java.base`, verified with Gradle and `jdeps`.
All resolved versions are captured in per-module lockfiles. The committed Gradle dependency
verification metadata pins SHA-256 checksums for 263 artifacts across 135 components, including
build plugins and metadata. Fixture and test dependencies are not published as runtime API
artifacts.

libGDX 1.14.2 is the latest upstream release reviewed for this candidate. Its LWJGL3 backend
resolves LWJGL 3.3.3. On JDK 25 that native stack can emit `Unsafe` and unsupported-JNI-version
warnings while the qualified smoke test still completes successfully. The project does not
override libGDX's LWJGL dependency independently; that compatibility boundary must be reviewed
again before the final release.
