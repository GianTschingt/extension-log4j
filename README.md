# Lucee Log4j Extension

With Lucee 9, the Log4j based logging has moved from the core into this extension.

Without the extension Lucee uses a simple built-in log engine. It supports the `console` and `resource` (file) appender with size based rotation and the `classic` and `pattern` layout. A log configured with another appender or layout (for example `datasource`, or the layouts `json`, `xml`, `html`, `datadog`) keeps its configuration but is written with the console appender (a warning is logged once), until this extension is installed.

With the extension installed, the log engine is Apache Log4j 2 and all appenders and layouts of Lucee 8 are available:

* appenders: `console`, `resource`, `datasource`, and any Log4j appender class
* layouts: `classic`, `pattern`, `json`, `xml`, `html`, `datadog`, and any Log4j layout class

Existing logging configuration keeps working, the configuration is the same with and without the extension.

## JSON layout

The `json` layout writes every log entry as a JSON document ([LDEV-2248](https://luceeserver.atlassian.net/browse/LDEV-2248)).

```json
{
  "timestamp": "2026-10-29T02:58:58.463Z",
  "timeMillis": 1793242738463,
  "thread": "main",
  "level": "ERROR",
  "loggerName": "mylog",
  "context": "",
  "application": "myapp",
  "message": "hello",
  "thrown": {
    "commonElementCount": 0,
    "message": "outer",
    "name": "java.lang.RuntimeException",
    "cause": {
      "message": "inner",
      "name": "java.lang.IllegalStateException",
      "extendedStackTrace": [ ... ],
      "cause": { ... }
    }
  },
  "extendedStackTrace": [ ... ],
  "endOfBatch": false,
  "threadId": 1,
  "threadPriority": 5
}
```

* `timestamp` is the time of the event as an ISO-8601 string in UTC with milliseconds. With `includeTimeMillis=false` the `instant` struct (`epochSecond`, `nanoOfSecond`) is written instead of `timeMillis`, `timestamp` is always written.
* `thread` is the name of the thread that wrote the entry, `threadId` and `threadPriority` are its id and priority.
* `thrown` describes the exception, the causes follow in the nested `cause` keys (up to 20 levels). Every cause has `message`, `name` and, with `includeStacktrace=true` (default), its `extendedStackTrace`. The stack trace of the exception itself is `extendedStackTrace` in the root.
* Other keys such as `marker`, `source`, `contextMap` and the keys for the web context are only written when they have a value.

Layout arguments: `charset`, `complete` (write a surrounding JSON array), `compact`, `includeStacktrace`, `includeTimeMillis`, `stacktraceAsString`, `locationInfo`, `properties`.

Issues: https://luceeserver.atlassian.net/browse/LDEV-6626
