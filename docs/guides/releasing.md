# Releasing to Maven Central

Releases use the GitHub `maven-central` environment and Sonatype's Portal OSSRH Staging API.
Publishing is deliberately split into two steps:

1. GitHub Actions checks, signs, and uploads a tagged candidate.
2. A maintainer inspects and publishes or drops the user-managed deployment in the
   [Central Publisher Portal](https://central.sonatype.com/publishing/deployments).

The workflow never automatically releases an immutable Maven Central version.

## One-time Sonatype setup

Create a Central Publisher Portal account, verify ownership of the `io.github.teemuki8` namespace,
and generate a Portal user token. Generate a signing key whose public key is available from a
public key server accepted by Maven Central.

Configure these encrypted secrets in the repository's `maven-central` environment:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | username from the generated Portal token |
| `MAVEN_CENTRAL_PASSWORD` | password from the generated Portal token |
| `MAVEN_SIGNING_KEY` | ASCII-armored private PGP key |
| `MAVEN_SIGNING_PASSWORD` | private-key passphrase |

The environment variable `MAVEN_CENTRAL_NAMESPACE` is configured as `io.github.teemuki8`.
Do not store these values in repository files, ordinary environment variables, workflow inputs, or
build logs.

## Stage a candidate

1. Confirm `main` is green and the version has never been published.
2. Complete the maintainer confirmations in the
   [Sonatype Central compliance checklist](../sonatype-central-compliance.md), including reviewing
   Sonatype's then-current terms and publishing limits.
3. Create and push a version tag such as `v0.1.0`.
4. Publish a GitHub release for that tag.
5. Approve the waiting `Stage Maven Central` environment deployment.
6. Inspect the deployment in the Central Publisher Portal.
7. Publish it only after all Central validations pass; otherwise drop it.

For recovery, the workflow can be dispatched manually only when the selected Git ref is a `v*`
version tag. The environment does not permit branches to access its secrets.

Maven Central versions are immutable. Never retry a changed build with a version that was already
published.
