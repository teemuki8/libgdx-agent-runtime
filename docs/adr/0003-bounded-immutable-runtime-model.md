# ADR 0003: Bounded immutable runtime model

- Status: Accepted
- Date: 2026-07-29

## Context

Snapshots cross thread and trust boundaries. Unbounded or polymorphic data can exhaust memory,
produce misleading partial evidence, or instantiate arbitrary Java types.

## Decision

Use validated records and a sealed value union: null, boolean, integer, decimal, string, enum,
vector two, list, and object. Decimal values use canonical `BigDecimal` text and reject non-finite
floating-point inputs. Lists and objects are deeply copied, depth-checked, ordered, and bounded.
Objects and entity properties use ordered entry lists, not caller maps.

Every capture limit has explicit stats and truncation flags. Entity/property iteration is stable:
entities sort by ID, properties sort by name, events and decisions use monotonically increasing IDs,
and query results sort by frame then local sequence. The first baseline snapshot does not emit
property-added changes. A later new entity emits `ENTITY_ADDED`; removal emits `ENTITY_REMOVED`
with its prior entity snapshot as evidence. Duplicate IDs across all static/dynamic providers are
retained as diagnostics and omitted after the first deterministic owner.

Provider failures become bounded diagnostics with provider, optional entity/property, exception
class, a stable category, and a deterministic correlation identifier; raw application messages and
stack traces are never externally serialized. Applications may opt into bounded sanitized detail
through an application-owned sanitizer that fails closed when it throws. Truncation always reports
the dimension, observed count, retained count, and limit.

## Consequences

Completed frames can be queried from any thread without locks around their contents. Retention
eviction removes the frame and its frame-owned events/decisions from queries; query metadata reports
the oldest/newest retained frame and whether the requested range was partially evicted.
