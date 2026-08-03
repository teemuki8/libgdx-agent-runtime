# Contributing

Use Java 25 and the committed Gradle Wrapper. Keep core JDK-only, avoid reflection and arbitrary
objects, add an ADR before a lasting architectural change, and begin behavior changes with focused
JUnit tests.

Run:

```bash
./gradlew clean check javadoc --warning-mode=fail
```

Linux development requires `xvfb-run` from `xorg-x11-server-Xvfb` on Fedora/Nobara or `xvfb` on
Debian/Ubuntu. The official Linux fixture and full gates must run under Xvfb; an active desktop
display is not a substitute. Use:

```bash
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh full
```

Keep commits focused and do not add unbounded fields, collections, logs, protocol commands, or MCP
tools.
