# Sonatype Central compliance

This project publishes the four library modules as community open-source artifacts under the
Apache License 2.0. This checklist records the release controls reviewed on 2026-07-30. It is a
technical release checklist, not legal advice.

## Automated controls

- Release coordinates are under the verified `io.github.teemuki8` namespace and never use a
  `SNAPSHOT` version.
- Every Maven publication contains a primary JAR, sources JAR, Javadoc JAR, POM, checksums, and
  detached OpenPGP signatures.
- Every primary, sources, and Javadoc JAR contains `META-INF/LICENSE` and `META-INF/NOTICE`.
- Every POM contains the project name, description, project URL, Apache-2.0 license, maintainer
  identity and profile URL, and SCM coordinates.
- Publication archives are reproducible and `check` verifies their licensing files.
- Published JARs are thin: dependencies are declared in POM metadata rather than bundled.
- The release workflow stages a user-managed deployment. It cannot make an immutable Central
  publication automatically.

The release workflow and protocol expose no Sonatype credentials. Credentials and the private
signing key remain encrypted GitHub environment secrets.

## Maintainer confirmations before Publish

The maintainer must confirm for every candidate that:

1. they have the rights and authority needed to publish all included material;
2. the repository and artifacts contain no secrets, confidential information, private user data,
   unlawful content, or third-party material lacking compatible distribution rights;
3. the project metadata, maintainer identity, license, and namespace remain accurate;
4. the project remains a community open-source library without a required commercial service;
5. they have reviewed and accept the then-current Sonatype Producer Terms, Terms of Service,
   Acceptable Use Policy, Privacy Policy, and publishing limits; and
6. the Central Portal reports the deployment as validated and the staged contents have been
   inspected before selecting **Publish**.

If any statement is false or uncertain, drop the staged deployment and resolve it. Sonatype may
change its terms, policies, tiers, and limits; this dated review does not replace a review at each
release.

## Authoritative references

- [Central Producer Terms](https://central.sonatype.org/publish/producer-terms/)
- [Central publishing requirements](https://central.sonatype.org/publish/requirements/)
- [Central Terms of Service](https://central.sonatype.org/terms.html)
- [Maven Central publishing limits](https://central.sonatype.org/publish/maven-central-publishing-limits/)
- [Maven Central immutability](https://central.sonatype.org/publish/requirements/immutability/)
