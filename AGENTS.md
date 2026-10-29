# Log4j Extension — Agent Guide

Maven-based Lucee extension with the Log4j 2 based log engine. It implements the `lucee.commons.io.log.LogEngine` interface of the Lucee loader and registers it with the manifest attribute `log-engine`.

## Folder Structure

- `source/java/src/` — Java source (`org.lucee.extension.log4j.Log4jEngine` and the appenders and layouts below it)
- `source/java/pom.xml` — module POM (dependencies log4j-api and log4j-core, bundled into the extension)
- `pom.xml` — root Maven build, extension manifest values, `.lex` packaging
- `build.xml` — Ant part of the build, writes the extension `MANIFEST.MF` (called from Maven)
- `tests/` — CFML integration tests (label: `log4j`)
- `CHANGELOG.md` — release notes, update for user-visible changes

## Build

Requires JDK 21 (the Lucee 9 jar has Java 21 class files, javac cannot read them with an older JDK) and Maven. The Lucee dependency (`org.lucee:lucee`, scope provided) must be available for the version in `pom.xml` (`luceeCoreVersion`).

```bash
mvn clean install -Dgoal=install
```

Output: `target/*.lex`

Versions and releases are handled by the GitHub workflow, which passes `-Drevision` (see "Versioning and releases" in `README.md`). A manual deploy by maintainers uses a local `maven-deploy.sh` (not committed), which needs `MAVEN_USERNAME`, `MAVEN_PASSWORD` and `GPG_PASSPHRASE` in the environment. Never commit credentials.

## Test

CI (`.github/workflows/main.yml`) builds once and runs the tests with [script-runner](https://github.com/lucee/script-runner) against the Lucee versions defined in the repository variable `LUCEE_TEST_VERSIONS_9`.

## Notes

- The extension only uses the loader API (through `org.lucee.extension.log4j.Util`), never core classes.
- Class definitions written to the configuration keep the names of the former built-in implementation (`lucee.commons.io.log.log4j2.appender.ConsoleAppender` and so on), so a configuration works with and without this extension. Do not change them.
- Minimum Lucee version is in `luceeCoreVersion` in `pom.xml`, it must be a Lucee version that knows the `log-engine` manifest attribute.
- Behaviour must stay identical to the log4j implementation that was in the Lucee core until Lucee 8.
