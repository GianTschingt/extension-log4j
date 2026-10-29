# Lucee Log4j Extension

With Lucee 9, the Log4j based logging has moved from the core into this extension.

Without the extension Lucee uses a simple built-in log engine. It supports the `console` and `resource` (file) appender with size based rotation and the `classic` and `pattern` layout. A log configured with another appender or layout (for example `datasource`, or the layouts `json`, `xml`, `html`, `datadog`) keeps its configuration but is written with the console appender (a warning is logged once), until this extension is installed.

With the extension installed, the log engine is Apache Log4j 2 and all appenders and layouts of Lucee 8 are available:

* appenders: `console`, `resource`, `datasource`, and any Log4j appender class
* layouts: `classic`, `pattern`, `json`, `xml`, `html`, `datadog`, and any Log4j layout class

Existing logging configuration keeps working, the configuration is the same with and without the extension.

Issues: https://luceeserver.atlassian.net/browse/LDEV-6626

## Versioning and releases

Works like Lucee core (LDEV-6516): every code change gets its own version number. The version is not edited by hand.
`pom.xml` uses a CI-friendly `${revision}` built from `extension.version.base`, a build number and
`extension.version.qualifier` (`-SNAPSHOT`).

* **Every push to `main`** (a merged PR or a direct commit) takes the highest existing build of the base, from the git tags,
  Maven Central and the Central snapshot repository, adds 1, builds, tests, publishes that version (e.g. `1.0.0.N-SNAPSHOT`)
  to the [Central snapshot repository](https://central.sonatype.com/repository/maven-snapshots/) and creates the tag `1.0.0.N`.
* Pushes run one after another (workflow concurrency queue), so two quick merges get two different numbers.
* PR builds, forks and runs without the deploy secrets build and test `1.0.0.0-SNAPSHOT` and never use up a number.
* **Release candidate or final release:** run the workflow by hand (Actions > Java CI Combined > Run workflow), choose the branch
  `main`, tick `deploy` and pick `RC`, `BETA`, `ALPHA` or `release` as qualifier. That publishes the current code once with the
  next build number (e.g. `1.0.0.N-RC` or `1.0.0.N`) to Maven Central and creates a GitHub release with the `.lex` files,
  without a commit; the pom keeps `-SNAPSHOT`.
* To start a new line change `extension.version.base`.
