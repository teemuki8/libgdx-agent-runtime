# Contributing

Use Java 25 and the committed Gradle Wrapper. Keep core JDK-only, avoid reflection and arbitrary
objects, add an ADR before a lasting architectural change, and begin behavior changes with focused
JUnit tests.

Run:

```bash
./gradlew clean check javadoc --warning-mode=fail
```

Linux native changes must also pass the LWJGL3 smoke test under Xvfb. Keep commits focused and do
not add unbounded fields, collections, logs, protocol commands, or MCP tools.
