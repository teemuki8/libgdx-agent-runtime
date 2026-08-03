# ADR 0005: Capability and protocol evolution

- Status: Accepted
- Date: 2026-08-03

## Context

Protocol 1.0 is a published exact-version contract with eight read-only commands and MCP tools.
Planned control and automation features are optional, application-owned extensions. Adding their
commands or metadata silently to 1.0 would break strict clients, while accepting arbitrary
application-declared capability strings could advertise implementations that do not exist.

MCP tool discovery is server-wide, but runtime capabilities are session-specific. A single server
may eventually publish sessions with different optional implementations.

## Decision

Keep protocol 1.0 frozen. Introduce protocol 1.1 as the first extension-aware version. A request
selects one exact supported version and a successful response echoes it. Unsupported versions fail
before command execution and report the complete supported-version set.

The 1.0 capabilities result retains its existing encoded shape. Protocol 1.1 adds a bounded
capability report containing the runtime library version and deterministically ordered descriptors.
Each descriptor identifies its stable capability/version, availability and reason, access mode,
Java APIs, protocol commands, MCP tools, limits, modes, and dependencies. Descriptors are derived
from concrete registered implementations; applications cannot advertise arbitrary capability IDs.

The existing MCP tools continue to use protocol 1.0 by default. `runtime_capabilities` accepts an
optional closed `protocolMinor` selector so extension-aware callers can request 1.1 without changing
the output seen by existing callers.

Future optional MCP tools use a catalog fixed when the MCP server starts. The catalog contains the
base tools plus the deterministic union of tools backed by concrete registered extensions. A tool
that is present server-wide but unavailable for the selected session returns
`CAPABILITY_UNAVAILABLE`; it never falls through to an unknown-command or internal error. Adding an
optional command requires a new supported protocol version and a closed typed command subtype; no
generic extension command or arbitrary payload is permitted.

## Consequences

Existing protocol and MCP clients retain the 1.0 contract. New clients can negotiate 1.1 explicitly
and distinguish available, unavailable, disabled, and dependency-limited features using bounded
metadata. Every future extension must supply Java, protocol, MCP, disabled, partial-configuration,
and compatibility tests before its implementation can be advertised.
