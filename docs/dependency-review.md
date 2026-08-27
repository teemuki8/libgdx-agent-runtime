# Dependency and license review

Reviewed from Maven Central metadata, resolved POMs, dependency reports, and committed lockfiles on
2026-08-27:

| Scope | Dependency | Version | Declared license |
| --- | --- | --- | --- |
| libGDX adapter | `com.badlogicgames.gdx:gdx` | 1.14.2 | Apache-2.0 |
| Box2D adapter | `com.badlogicgames.gdx:gdx-box2d` | 3.1.1-0 | Apache-2.0 |
| protocol | Jackson databind/JDK8/JSR310 | 2.22.1 | Apache-2.0 |
| MCP | Java MCP SDK | 2.0.0 | MIT |
| tests | JUnit Jupiter | 6.1.2 | EPL-2.0 |
| MCP/fixture logging | SLF4J | 2.0.17 | MIT (parent project declaration) |

Maven Central metadata confirms the reviewed libGDX core 1.14.2, official standalone Box2D
3.1.1-0, Jackson 2.22.1, Java MCP SDK 2.0.0, and JUnit 6.1.2 coordinates. `runtime-box2d` exposes
the Box2D 3 API plus its official jnigen 3.1.0 transitive runtime and has no dependency on protocol,
MCP, `runtime-libgdx`, or the legacy Box2D 1.14.2 artifact. Its desktop native is test-only.

The MCP SDK resolves Reactor 3.7, JSON Schema Validator 3, Jackson 3.0.3, SLF4J, and their bounded
support graph. Protocol uses Jackson 2 under `com.fasterxml`; MCP SDK 2 uses Jackson 3 under
`tools.jackson`, so both lines coexist without package collision. MCP SDK and Reactor types are part
of the `runtime-mcp` public hosting API, making that dependency graph intentional for 1.0 rather
than hidden core functionality.

`runtime-core` has no production dependency beyond `java.base`, verified with Gradle and `jdeps`.
All resolved configurations are captured in per-module lockfiles. The committed Gradle dependency
verification metadata pins SHA-256 checksums for 267 artifacts across 137 components, including
build plugins and metadata. Fixture and test dependencies are not published as runtime API
artifacts.

libGDX core 1.14.2 resolves LWJGL 3.3.3 for desktop fixtures. The independently versioned official
Box2D 3.1.1-0 native uses jnigen 3.1.0 and is isolated from the legacy Box2D coordinate. On JDK 25
the upstream native stacks may emit platform warnings outside the warning-fail Gradle gates. Native
access is explicitly enabled for the isolated Xvfb fixtures.
