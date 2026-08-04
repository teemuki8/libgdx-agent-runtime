# Dependency and license review

Reviewed from Maven Central metadata, resolved POMs, dependency reports, and committed lockfiles on
2026-08-04:

| Scope | Dependency | Version | Declared license |
| --- | --- | --- | --- |
| libGDX adapter | `com.badlogicgames.gdx:gdx` | 1.14.2 | Apache-2.0 |
| protocol | Jackson databind/JDK8/JSR310 | 2.22.1 | Apache-2.0 |
| MCP | Java MCP SDK | 2.0.0 | MIT |
| tests | JUnit Jupiter | 6.1.2 | EPL-2.0 |
| MCP/fixture logging | SLF4J | 2.0.17 | MIT (parent project declaration) |

Maven Central metadata confirms that libGDX 1.14.2, Jackson 2.22.1, Java MCP SDK 2.0.0, and JUnit
6.1.2 are their current releases. The fixture reuses the SDK graph's SLF4J 2.0.17 line with the
no-op provider and publishes neither dependency.

The MCP SDK resolves Reactor 3.7, JSON Schema Validator 3, Jackson 3.0.3, SLF4J, and their bounded
support graph. Protocol uses Jackson 2 under `com.fasterxml`; MCP SDK 2 uses Jackson 3 under
`tools.jackson`, so both lines coexist without package collision. MCP SDK and Reactor types are part
of the `runtime-mcp` public hosting API, making that dependency graph intentional for 1.0 rather
than hidden core functionality.

`runtime-core` has no production dependency beyond `java.base`, verified with Gradle and `jdeps`.
All resolved configurations are captured in per-module lockfiles. The committed Gradle dependency
verification metadata pins SHA-256 checksums for 263 artifacts across 135 components, including
build plugins and metadata. Fixture and test dependencies are not published as runtime API
artifacts.

libGDX 1.14.2 resolves LWJGL 3.3.3 for the desktop fixture. On JDK 25 that upstream native stack can
emit `Unsafe` and unsupported-JNI-version warnings. The isolated Xvfb lifecycle and stdio MCP
fixtures complete successfully with native access explicitly enabled. The project does not
override libGDX's LWJGL dependency independently; this qualified upstream combination is the 1.0
desktop boundary.
