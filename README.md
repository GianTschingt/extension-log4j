# Lucee Log4j Extension

With Lucee 9, the Log4j based logging has moved from the core into this extension.

Without the extension Lucee uses a simple built-in log engine. It supports the `console` and `resource` (file) appender with size based rotation and the `classic` and `pattern` layout. A log configured with another appender or layout (for example `datasource`, or the layouts `json`, `xml`, `html`, `datadog`) keeps its configuration but is written with the console appender (a warning is logged once), until this extension is installed.

With the extension installed, the log engine is Apache Log4j 2 and all appenders and layouts of Lucee 8 are available:

* appenders: `console`, `resource`, `datasource`, and any Log4j appender class
* layouts: `classic`, `pattern`, `json`, `xml`, `html`, `datadog`, and any Log4j layout class

Existing logging configuration keeps working, the configuration is the same with and without the extension.

Issues: https://luceeserver.atlassian.net/browse/LDEV-6626
