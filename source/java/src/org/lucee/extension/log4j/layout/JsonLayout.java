/**
 *
 * Copyright (c) 2014, the Railo Company Ltd. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 * 
 **/
package org.lucee.extension.log4j.layout;

import org.lucee.extension.log4j.Util;
import java.nio.charset.Charset;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.ThreadContext.ContextStack;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.core.time.Instant;
import org.apache.logging.log4j.message.Message;

import org.lucee.extension.log4j.ContextualMessage;
import org.lucee.extension.log4j.Util;
import org.lucee.extension.log4j.LogAdapter;
import lucee.loader.engine.CFMLEngineFactory;
import lucee.runtime.PageContext;
import lucee.runtime.exp.PageException;
import lucee.runtime.security.Credential;
import lucee.runtime.type.Array;
import lucee.runtime.type.Struct;
import lucee.runtime.util.Creation;

public final class JsonLayout extends AbstractStringLayout { // TODO <Serializable>

	private static final int DEFAULT_SIZE = 256;
	private static final String[] FORMATS = new String[] { "json" };
	private static final String NL = System.lineSeparator();
	// LDEV-2248: ISO-8601 timestamp in UTC with millisecond precision, e.g. 2026-10-29T02:54:03.809Z
	private static final DateTimeFormatter ISO_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
	// LDEV-2248: maximum number of causes written to "thrown", protects against very long or cyclic cause chains
	private static final int MAX_CAUSES = 20;

	private final boolean includeStacktrace;
	private final boolean includeTimeMillis;
	private final boolean stacktraceAsString;
	private final boolean locationInfo;
	private final boolean properties;
	private boolean doComma = true;
	private final Charset charset;
	private boolean compact;
	private boolean hasEnvLoaded;
	private Object token = new Object();
	private Struct envs;
	private boolean complete;
	private String[] envNames;

	// private static final DateFormat dateFormat = new DateFormat(Locale.US);
	// private static final TimeFormat timeFormat = new TimeFormat(Locale.US);

	public JsonLayout(Charset charset, boolean complete, boolean compact, boolean includeStacktrace, boolean includeTimeMillis, boolean stacktraceAsString, boolean locationInfo,
			boolean properties, String[] envNames) {
		super(charset, createHeader(charset, complete), createFooter(charset, complete));

		this.charset = charset;
		this.includeStacktrace = includeStacktrace;
		this.includeTimeMillis = includeTimeMillis;
		this.stacktraceAsString = stacktraceAsString;
		this.locationInfo = locationInfo;
		this.properties = properties;
		this.compact = compact;
		this.complete = complete;
		this.envNames = envNames;

	}

	@Override
	public byte[] getHeader() {
		doComma = false;
		return super.getHeader();

	}

	private static byte[] createHeader(Charset cs, boolean complete) {
		if (!complete) return new byte[] {};
		return "[".getBytes(cs);
	}

	private static byte[] createFooter(Charset cs, boolean complete) {
		if (!complete) return new byte[] {};
		return "]".getBytes(cs);
	}

	@Override
	public String getContentType() {
		return "application/json; charset=" + this.getCharset();
	}

	@Override
	public Map<String, String> getContentFormat() {
		final Map<String, String> result = new HashMap<>();
		result.put("version", "2.0");
		return result;
	}

	@Override
	public String toSerializable(final LogEvent event) {
		Creation util = CFMLEngineFactory.getInstance().getCreationUtil();
		try {
			Struct root = util.createStruct("linked");
			long now = event.getTimeMillis();
			// LDEV-2248: ISO-8601 timestamp, the instant/timeMillis below stay for compatibility
			root.setEL("timestamp", ISO_TIMESTAMP.format(java.time.Instant.ofEpochMilli(now)));
			// timeMillis
			if (includeTimeMillis) {
				root.setEL("timeMillis", now);
			}
			// instant
			else {
				Instant instant = event.getInstant();
				Struct sct = util.createStruct("linked");
				root.setEL("instant", sct);
				sct.set("epochSecond", instant.getEpochSecond());
				sct.set("nanoOfSecond", instant.getNanoOfSecond());
			}

			// LDEV-2248: "thread" holds the thread name, id and priority are written as threadId and threadPriority
			root.setEL("thread", event.getThreadName());
			root.setEL("level", event.getLevel().name());

			// name
			root.setEL("loggerName", LayoutUtil.getLoggerName(event));

			// marker
			Marker marker = event.getMarker();
			if (marker != null) {
				root.setEL("marker", createMarker(util, marker));

			}

			String msg, application, context;
			Message message = event.getMessage();
			if (message instanceof ContextualMessage) {
				ContextualMessage cm = (ContextualMessage) message;
				msg = cm.getFormattedMessage();
				application = cm.getApplication();
				context = cm.getContext();
			}
			else {
				msg = message.getFormattedMessage();
				application = "";
				context = "";
			}

			Throwable thrown = event.getThrown();
			if (LogAdapter.logWebContextInfo) root.setEL("context", context);
			root.setEL("application", application);
			root.setEL("message", toJson(msg, msg));

			// Thrown
			if (thrown != null) {
				Struct sct = createThrown(util, thrown, includeStacktrace, stacktraceAsString);
				root.setEL("thrown", sct);
				if (includeStacktrace) {
					root.setEL("extendedStackTrace", createStacktrace(util, thrown.getStackTrace(), stacktraceAsString));
				}
			}

			// context stack
			{
				Array contextStack = util.createArray();
				ContextStack stack = event.getContextStack();
				if (stack.getDepth() > 0) {
					for (String cse: stack.asList()) {
						contextStack.append(cse);
					}

				}
				if (contextStack.size() > 0) root.setEL("contextStack", contextStack);
			}
			// end of batch
			root.setEL("endOfBatch", event.isEndOfBatch());

			// TODO "loggerFqcn" : "org.apache.logging.log4j.spi.AbstractLogger",

			// context map
			if (this.properties) {
				Map<String, String> map = event.getContextMap();
				if (map.size() > 0) {
					Struct contextMap = util.createStruct("linked");
					root.setEL("contextMap", contextMap);
					for (Entry<String, String> e: map.entrySet()) {
						contextMap.setEL(e.getKey(), e.getValue());
					}
				}
			}

			// thread id and priority
			root.setEL("threadId", event.getThreadId());
			root.setEL("threadPriority", event.getThreadPriority());

			if (this.locationInfo) {
				StackTraceElement data = null;
				for (StackTraceElement ste: Thread.currentThread().getStackTrace()) {
					if (ste.getClassName().startsWith("lucee.commons.io.log.")) continue;
					if (ste.getClassName().startsWith("org.apache.logging.log4j.")) continue;
					if (ste.getClassName().equals("lucee.runtime.tag.Log")) continue;
					data = ste;
				}
				if (data != null) {
					Struct source = util.createStruct("linked");
					root.setEL("source", source);
					source.setEL("class", data.getClassName());
					source.setEL("method", data.getMethodName());
					source.setEL("file", data.getFileName());
					source.setEL("line", data.getLineNumber());
				}
			}
			// datadog
			Object[] ddids = DataDogLayout.getCorrelationIdentifierWhenValid();
			if (ddids != null && ddids.length == 2) {
				Struct ids = util.createStruct("linked");
				ids.set("trace_id", ddids[0]);
				ids.set("span_id", ddids[1]);
				root.setEL("dd", ids);
			}

			// auth user
			PageContext pc = CFMLEngineFactory.getInstance().getThreadPageContext();
			if (pc != null) {
				String user = null;
				Credential remoteUser = pc.getRemoteUser();
				if (remoteUser == null) {
					user = pc.getHttpServletRequest().getRemoteUser();
				}
				else user = remoteUser.getUsername();
				if (!Util.isEmpty(user, true)) root.setEL("authUser", user);

				root.setEL("pageContextId", pc.getId());
				try {
					root.setEL("requestId", pc.getClass().getMethod("getRequestId").invoke(pc));
				}
				catch (Exception e) {
				}
			}

			// env var
			if (envNames != null && !hasEnvLoaded) {
				synchronized (token) {
					if (envNames != null && !hasEnvLoaded) {
						envs = CFMLEngineFactory.getInstance().getCreationUtil().createStruct();
						for (String v: envNames) {
							if (!Util.isEmpty(v, true)) {
								if (!Util.isEmpty(v, true)) {
									envs.setEL(v, Util.getSystemPropOrEnvVar(v.trim(), null));
								}
							}
						}
						hasEnvLoaded = true;
					}
				}
			}
			if (envs != null && !envs.isEmpty()) {
				root.setEL("environment", envs);
			}

			// Properties
			/*
			 * if (this.properties && event.getContextMap().size() > 0) { Array arr = util.createArray();
			 * sct.setEL("Properties", arr);
			 * 
			 * Set<Entry<String, String>> entrySet = event.getContextMap().entrySet(); int i = 1; for (final
			 * Map.Entry<String, String> entry: entrySet) { Struct s = util.createStruct("linked");
			 * arr.appendEL(s); sct.setEL("name", entry.getKey()); sct.setEL("value", entry.getValue()); } }
			 */

			try {
				String result = Util.serializeJSON(root, compact);
				if (doComma) {
					if (complete) return "," + NL + result;
					return NL + result;
				}
				else doComma = true;
				return result;
			}
			catch (PageException e) {
				throw e;
			}

		}
		catch (PageException e) {
			throw CFMLEngineFactory.getInstance().getExceptionUtil().createPageRuntimeException(e);
		}

	}

	// LDEV-2248: name and message of the exception, followed by its cause chain as nested "cause" elements
	private Struct createThrown(Creation util, Throwable thrown, boolean withStacktrace, boolean stacktraceAsString) throws PageException {
		Struct root = util.createStruct("linked");
		root.setEL("commonElementCount", 0D); // TODO
		root.setEL("message", thrown.getMessage());
		root.setEL("name", thrown.getClass().getName());

		Map<Throwable, Boolean> seen = new IdentityHashMap<>();
		seen.put(thrown, Boolean.TRUE);
		Struct parent = root;
		Throwable cause = thrown.getCause();
		int count = 0;
		while (cause != null && count < MAX_CAUSES && seen.put(cause, Boolean.TRUE) == null) {
			Struct sct = util.createStruct("linked");
			sct.setEL("message", cause.getMessage());
			sct.setEL("name", cause.getClass().getName());
			if (withStacktrace) {
				sct.setEL("extendedStackTrace", createStacktrace(util, cause.getStackTrace(), stacktraceAsString));
			}
			parent.setEL("cause", sct);
			parent = sct;
			cause = cause.getCause();
			count++;
		}
		return root;
	}

	private static Object toJson(String strJson, Object defaultValue) {
		if (Util.isEmpty(strJson, true)) {
			return defaultValue;
		}
		strJson = strJson.trim();
		if ((!strJson.startsWith("{") && !strJson.startsWith("[")) || (!strJson.endsWith("}") && !strJson.endsWith("]"))) {
			return defaultValue;
		}

		try {
			return Util.deserializeJSON(strJson);
		}
		catch (PageException e) {
			return defaultValue;
		}
	}

	private Object createStacktrace(Creation util, StackTraceElement[] stackTraces, boolean stacktraceAsString) throws PageException {

		// Stacktrace
		//
		if (stacktraceAsString) {
			StringBuilder sb = new StringBuilder();
			for (StackTraceElement ste: stackTraces)
				sb.append("\tat ").append(ste).append('\n');
			return sb.toString();
		}
		else {
			Array arr = util.createArray();
			Struct sct;
			for (StackTraceElement ste: stackTraces) {
				sct = util.createStruct("linked");
				arr.appendEL(sct);
				sct.setEL("class", ste.getClassName());
				sct.setEL("method", ste.getMethodName());
				sct.setEL("class", ste.getClassName());
				sct.setEL("file", ste.getFileName());
				sct.setEL("line", ste.getLineNumber());
				sct.setEL("exact", Boolean.TRUE);
				sct.setEL("location", "classes/");
				sct.setEL("version", "?");
				// TODO exact location version
			}
			return arr;
		}
	}

	private Struct createMarker(Creation util, Marker marker) throws PageException {
		Struct sct = util.createStruct("linked");
		sct.setEL("name", marker.getName());
		Marker[] parents = marker.getParents();
		if (parents != null && parents.length > 0) {
			Array arr = util.createArray();
			sct.setEL("parents", arr);
			for (Marker p: parents) {
				arr.appendEL(createMarker(util, p));
			}
		}
		return sct;
	}
}