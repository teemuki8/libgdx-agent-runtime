# Security policy

## Supported versions

| Version | Supported |
| --- | --- |
| Latest `1.x` release | Yes |
| `0.x` and older `1.x` releases | No |

Security fixes are released on the latest 1.x line. Reports for every version are welcome through
GitHub's private vulnerability reporting; the maintainer will assess whether a supported release is
affected.

## Boundary

MCP is a local development tool over stdio. It exposes only properties registered by the
application and provides no shell, filesystem, reflection, script, arbitrary class loading, network
destination, or remote listener.
Stdio input is framed before parsing: each newline-terminated JSON-RPC frame is bounded to
`ProtocolJson.MAX_REQUEST_BYTES` (1 MiB) of raw bytes, oversized frames are drained through their
newline without retention, malformed UTF-8 is rejected with a strict decoder, and nesting, string,
and number tokens are capped at depth 32, 16,384 characters, and 128 digits before schema
validation. A rejected frame produces one bounded JSON-RPC parse error with a null id and does not
terminate later valid requests; only an unterminated frame at EOF ends the reader. Local peers are
still trusted code with filesystem access; these limits prevent accidental or adversarial input from
materializing unbounded lines or forcing deep parser work.
Declarative assertions are a closed data schema over completed evidence. They do not accept regular
expressions, method names, arbitrary expressions, or executable code.
Simulation waits accept only registered named predicates or the same closed declarative assertion
union. They do not accept scripts, expressions, class names, reflection, or caller-defined code.
Registered input injection accepts only application-declared IDs and closed scalar parameters on
paused controlled ticks. It cannot address arbitrary classes or operating-system input, and
application-selected redaction can omit parameter values from retained evidence.
Recording captures only explicitly registered semantic inputs/actions, including validated closed
action parameters, and bounded completed-runtime evidence. Applications must omit secrets from action
schemas and allowlisted configuration; input recording policy can omit or redact input values. The
runtime installs no global or operating-system input hook, serializes no application object, and
provides no replay executor. Manifest configuration is closed scalar data; opaque checkpoint handles,
callbacks, and arbitrary payloads never cross Java, protocol, or MCP boundaries.
Determinism comparison accepts only a registered scenario ID, an application-acknowledged seed,
closed scalar configuration, exact controlled ticks, and a closed observable profile. It executes
no caller code, expression, reflection, or replay manifest. `EQUAL` covers only registered,
configured evidence and is not a claim about hidden application or platform state.
Checkpoint payloads remain opaque application-owned handles inside the runtime. MCP exposes only
bounded descriptors and operation evidence; it cannot serialize, traverse, name, or restore an
arbitrary object supplied by a caller.
Runtime/UI correlation accepts only explicit bounded semantic identifiers, validity constraints,
and frame mappings registered by application code. It performs no DOM, scene-graph, accessibility,
widget-object, pixel, reflection, or arbitrary object traversal.

Do not publish credentials, tokens, secrets, private user data, or filesystem information as entity
properties, event attributes, decision attributes, names, or diagnostics.

Reports should include affected version, reproducible input, impact, and whether a configured limit
was bypassed. Do not include live secrets in a report.
