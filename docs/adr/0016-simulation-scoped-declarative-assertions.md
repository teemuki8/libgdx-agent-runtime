# ADR 0016: Simulation-scoped declarative assertions

- Status: Accepted
- Date: 2026-08-09

## Context

The frozen frame-scoped assertion API cannot express approximate vectors, wrapped angles, exact
contact endpoints, or predicates that must hold for every authoritative simulation tick. Treating a
runtime-frame range as a simulation-tick range would be dishonest: ticks may be missing, failed,
evicted, correlated to no completed frame, or incomplete in an adapter-specific way.

Putting Box2D types or native queries in the evaluator would break the JDK-only core boundary and
make assertion results depend on mutable native state instead of retained evidence. Executable
predicates, expressions, reflection, and property paths would also open the trust boundary.

## Decision

`runtime-core` adds a separate closed `SimulationAssertion` union, `SimulationAssertionSpec`,
`SimulationAssertionScope`, immutable evidence/result records, and
`AssertionEvaluator.evaluateSimulation`. The frozen `RuntimeAssertion` API and protocols 1.0-1.13
remain unchanged.

A scope names one execution epoch and a closed inclusive range of at most 1,000 epoch-relative
simulation ticks. The evaluator first resolves each attempted timeline tick to its completed runtime
frame. Evidence is complete only when the tick outcome is known-completed, its frame exists in the
same epoch without relevant capture loss, and every explicit top-level boolean evidence requirement
is true. Missing, evicted, failed, unknown, uncorrelated, truncated, or requirement-failing evidence
is incomplete.

The union contains exact entity/property checks, deterministic decimal scalar/vector approximation,
closed-area and magnitude predicates, vector distance, wrapped angle, exact event selectors,
bounded object-list subset matching, and a non-nested bounded conjunction. Selectors are recursive
object subsets without property paths, lists, code, or expressions. Caller-supplied and returned
`RuntimeValue` trees have independent hard depth, node, collection, and string bounds.

Final predicates read only the last requested tick. Every-tick predicates inspect the complete
range in ascending tick order and retain the first complete violation. A complete decisive mismatch
is `FAIL`, even if unrelated later evidence is unavailable. A negative or temporal result is `PASS`
only when every relevant tick is complete. Otherwise the answer is `INCONCLUSIVE`; absence from
incomplete evidence is never success.

`runtime-box2d` adds only data-only `Box2dAssertions` factories. They compile stable body, fixture,
world, property, contact-event, and active-contact selectors into the generic immutable model and
never retain or query native objects. Every contact factory requires
`box2d.contacts.<worldId>.complete == true`; contact continuity reads the `activeContacts` snapshot
at every tick rather than inferring continuity from begin/end adjacency.

Protocol 2.3 adds the closed `simulationAssert` command and `simulationAssertion` result. MCP adds
`runtime_simulation_assert` with a closed bounded natural-JSON schema. Ambiguous enum and vector
values use reserved exact `$runtimeValue` tags so MCP preserves canonical equality with Java and
protocol values. The MCP handler validates the complete raw tree's depth, node, collection, and
string bounds before constructing immutable runtime values. Protocol 2.2 and earlier reject the new
command before evaluation. Capability metadata advertises exact tick, evidence, requirement, and
conjunction bounds.

## Consequences

Agents can verify physics from selected immutable observations and exact tick correlation without
screenshots or native access. Results identify the first relevant tick/frame, property, expected
and observed values, and whether incompleteness affected the answer.

The evaluator proves only the selected registered observations. It does not prove whole-program or
cross-platform Box2D determinism and never infers gameplay causality. Applications still own input,
stepping, capture scheduling, rendering, contact-listener installation, and native disposal.

Every later public API or schema change must update the agent cookbook in the same change so its
Java, protocol, MCP, bounds, and failure examples remain executable documentation.
