# Security policy

## Supported versions

No production-supported release exists yet. Security reports for the current `0.1.x` development
line are welcome through GitHub's private vulnerability reporting for this repository.

## Boundary

MCP is a local development tool over stdio. It exposes only properties registered by the
application and provides no shell, filesystem, reflection, script, arbitrary class loading, network
destination, or remote listener.
Declarative assertions are a closed data schema over completed evidence. They do not accept regular
expressions, method names, arbitrary expressions, or executable code.
Simulation waits accept only registered named predicates or the same closed declarative assertion
union. They do not accept scripts, expressions, class names, reflection, or caller-defined code.

Do not publish credentials, tokens, secrets, private user data, or filesystem information as entity
properties, event attributes, decision attributes, names, or diagnostics.

Reports should include affected version, reproducible input, impact, and whether a configured limit
was bypassed. Do not include live secrets in a report.
