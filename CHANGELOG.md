# Changelog

## Unreleased

- [LDEV-2248](https://luceeserver.atlassian.net/browse/LDEV-2248) — `json` layout: new ISO-8601 `timestamp` field, `thread` keeps the thread name (the id and priority are now written as `threadId` and `threadPriority`, they used to replace the name), and the cause chain is written to `thrown.cause`

## 1.0.0.0-BETA

- [LDEV-6626](https://luceeserver.atlassian.net/browse/LDEV-6626) — first BETA, published to Maven Central and as a GitHub pre-release with the .lex files, tested with the Lucee 9 versions from `LUCEE_TEST_VERSIONS_9`

## 1.0.0.0-ALPHA

- [LDEV-6626](https://luceeserver.atlassian.net/browse/LDEV-6626) — initial version, the Log4j based log engine moved out of the Lucee core, registered with the manifest attribute `log-engine`
