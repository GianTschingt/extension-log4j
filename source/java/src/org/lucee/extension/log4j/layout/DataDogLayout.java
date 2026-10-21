package org.lucee.extension.log4j.layout;

import java.nio.charset.StandardCharsets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.message.Message;

import java.lang.reflect.Method;
import java.time.Instant;
import org.lucee.extension.log4j.Util;

import org.lucee.extension.log4j.ContextualMessage;
import org.lucee.extension.log4j.LogAdapter;
import lucee.loader.engine.CFMLEngine;
import lucee.loader.engine.CFMLEngineFactory;
import lucee.runtime.PageContext;
import lucee.runtime.exp.PageException;
import lucee.runtime.ext.function.BIF;
import lucee.runtime.type.Struct;
import lucee.runtime.util.Cast;

public final class DataDogLayout extends AbstractStringLayout {
	private static final String LINE_SEPARATOR = System.getProperty("line.separator");
	private static final Class[] EMPTY_CLASS = new Class[0];
	private static final Object[] EMPTY_OBJ = new Object[0];
	private DateTimeFormatter format;
	private CFMLEngine engine;
	private Cast caster;

	private static Class<?> correlationIdentifierClass;
	private static Method getTraceId;
	private static Method getSpanId;
	private BIF serializeJSONBIF;
	private static Object[] ids;
	private static long idsTimestamp;
	private static int idsTries = 0;
	private static boolean idsValid;

	public DataDogLayout() {
		super(StandardCharsets.UTF_8, new byte[0], new byte[0]);
		engine = CFMLEngineFactory.getInstance();
		caster = engine.getCastUtil();
		format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	}

	@Override
	public String getContentType() {
		return super.getContentType();
	}

	@Override
	public String toSerializable(final LogEvent event) {

		StringBuilder data = new StringBuilder();

		data.append(format.withZone(Util.getTimeZone(Util.currentConfig(null)).toZoneId()).format(Instant.now()));
		data.append(' ');
		data.append(event.getLevel().toString());
		data.append(' ');
		data.append(LayoutUtil.getLoggerName(event));
		data.append(':');
		data.append(caster.toString(getLineNumber()));
		data.append(" - ");

		Object[] id = getCorrelationIdentifier();
		data.append(id[0]);
		data.append(' ');
		data.append(id[1]);
		data.append(" - ");

		String msg, application, context;
		Message message = event.getMessage();
		if (message instanceof ContextualMessage) {
			ContextualMessage cm = (ContextualMessage) message;
			msg = cm.getFormattedMessage();
			application = cm.getApplication();
			context = cm.getContext();
		}
		else {
			msg = message != null ? message.getFormattedMessage() : "";
			application = "";
			context = "";
		}

		// Throwable
		Throwable t = event.getThrown();
		Struct sct = engine.getCreationUtil().createStruct(Struct.TYPE_LINKED);
		if (LogAdapter.logWebContextInfo) sct.setEL("context", context);
		sct.setEL("application", application);
		sct.setEL("message", ClassicLayout.getFormattedMessage(t, msg));
		sct.setEL("stack", t != null ? getStacktrace(t, false, true) : "");
		sct.setEL("kind", t != null ? t.getClass().getName() : "");
		try {
			data.append(serializeJSON(sct));
		}
		catch (PageException e) {
			data.append(msg);
		}

		return data.append(LINE_SEPARATOR).toString();

	}

	private String serializeJSON(Struct sct) throws PageException {
		return Util.serializeJSON(sct, true);
	}

	private static Object[] getCorrelationIdentifier() {

		if (idsValid) return ids;

		long now = System.currentTimeMillis();
		if (ids != null) {
			// if we have less than 300 tries, we try once a second (so for 5 minutes) after that every minute
			if (idsTries < 300 && idsTimestamp + 1000 > now) return ids;
			if (idsTries > 300 && idsTimestamp + 300000 > now) return ids;

		}

		idsTries++;
		idsTimestamp = now;
		try {
			if (correlationIdentifierClass == null) {
				getTraceId = null;
				correlationIdentifierClass = CFMLEngineFactory.getInstance().getClassUtil().loadClass("datadog.trace.api.CorrelationIdentifier");
			}

			// CorrelationIdentifier.getTraceId()
			if (getTraceId == null) {
				getTraceId = correlationIdentifierClass.getMethod("getTraceId", EMPTY_CLASS);
			}

			// CorrelationIdentifier.getSpanId()
			if (getSpanId == null) {
				getSpanId = correlationIdentifierClass.getMethod("getSpanId", EMPTY_CLASS);
			}
			Object[] tmp = new Object[] { getTraceId.invoke(null, EMPTY_OBJ), getSpanId.invoke(null, EMPTY_OBJ) };

			if (!"0".equals(tmp[0])) {
				ids = tmp;
				idsValid = true;
				return ids;
			}
			return ids = new Object[] { "0", "0" };
		}
		catch (Exception e) {
			// we cannot send this to a logger, because that could cause an infiniti loop
			IOException ioe = new IOException(
					"Datadog classes not found - this is optional and does not affect application functionality. Logging will continue with placeholder TraceId/SpanId values (-1/-1). To enable Datadog tracing, add the datadog-trace-api JAR to the classpath.");
			try {
				ioe.initCause(e);
				Util.logGlobal((lucee.runtime.config.Config) null, "datadog", ioe);
			}
			catch (Exception ee) {
				ioe.printStackTrace();
			}
		}

		return ids = new Object[] { "-1", "-1" };
	}

	public static Object[] getCorrelationIdentifierWhenValid() {
		Object[] _ids = getCorrelationIdentifier();
		if (idsValid) return _ids;
		return null;
	}

	public int getLineNumber() {
		int line = 0;
		String template;

		for (StackTraceElement trace: Thread.currentThread().getStackTrace()) {
			template = trace.getFileName();
			if (trace.getLineNumber() <= 0 || template == null || engine.getResourceUtil().getExtension(template, "").equals("java")) continue;
			line = trace.getLineNumber();
			if (line > 0) return line;
		}
		return 0;
	}

	public static String getStacktrace(Throwable t, boolean addMessage, boolean onlyLuceePart) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		pw.close();
		String st = sw.toString();
		// shrink the stacktrace
		if (onlyLuceePart && st.indexOf("Caused by:") == -1) {
			int index = st.indexOf("lucee.loader.servlet.CFMLServlet.service(");
			if (index == -1) index = st.indexOf("lucee.runtime.jsr223.ScriptEngineImpl.eval(");

			if (index != -1) {
				index = st.indexOf(")", index + 1);
				if (index != -1) {
					st = st.substring(0, index + 1) + "\n...";
				}
			}
		}

		String msg = t.getMessage();
		if (addMessage && !Util.isEmpty(msg) && !st.startsWith(msg.trim())) st = msg + "\n" + st;
		return st;

	}
}