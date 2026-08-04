# ADR 0011: Stable 1.x compatibility and support

- Status: Accepted
- Date: 2026-08-04

## Context

Version 1.0.0 turns the currently published preview into a production-supported library. Consumers
need to know which artifacts and surfaces remain compatible, while protocol clients need stronger
guarantees than an ordinary Java minor release can provide.

The repository publishes four libraries from one versioned release. `runtime-fixtures` exists only
to qualify those libraries. Protocol requests select an exact protocol version, and each supported
version has a closed command, response, JSON, and MCP contract.

## Decision

The `agent-runtime-core`, `agent-runtime-libgdx`, `agent-runtime-protocol`, and
`agent-runtime-mcp` artifacts follow Semantic Versioning from 1.0.0. All four artifacts share one
version and require Java 25.

Within the 1.x artifact line:

- public and protected types and members in the published Javadocs remain binary and source
  compatible;
- documented lifecycle, ordering, bounds, thread ownership, error, and security behavior remains
  compatible;
- additions are allowed only when existing callers retain their behavior; and
- an incompatible Java or behavioral change requires a new artifact major version.

The compatibility promise excludes `runtime-fixtures`, repository build tasks, test utilities,
snapshot builds, and undocumented implementation details. Upgrading an exposed third-party API
major version also requires a new artifact major version when it would break consumers.

Every protocol version from 1.0 through 1.13 is an immutable exact-version contract. A compatible
release may add a protocol only at a new minor number and must continue accepting every earlier 1.x
protocol version. Existing command unions, JSON/MCP schemas, result shapes, error codes, bounds, and
semantics do not change. A narrowly scoped correctness or security fix may reject input that the
published contract already declared invalid; the changelog must identify such a fix.

The eight protocol-1.0 MCP tools remain the base catalog. Optional tools may be added only with a
new protocol minor and a concrete registered capability as defined by ADR 0005.

Only the latest 1.x artifact release receives fixes. Security support and reporting are defined in
`SECURITY.md`. A 2.0.0 release may remove 1.x compatibility but does not alter the frozen 1.x
protocol contracts in already published artifacts.

## Consequences

A release review must treat public Java signatures, published dependency majors, exact protocol
fixtures, generated Javadocs, and release notes as compatibility evidence. Minor releases can grow
the bounded explicit model, but cannot silently repurpose an existing API or wire shape.

The 0.1.0 preview is not a compatibility baseline. Version 1.0.0 establishes the first supported
Java, behavior, and protocol baseline.
