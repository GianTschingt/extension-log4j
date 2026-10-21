package org.lucee.extension.log4j;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.TimeZone;

import lucee.commons.io.log.Log;
import lucee.commons.io.res.Resource;
import lucee.loader.engine.CFMLEngine;
import lucee.loader.engine.CFMLEngineFactory;
import lucee.runtime.config.Config;
import lucee.runtime.exp.PageException;
import lucee.runtime.util.Cast;

/**
 * Access to the functionality of the Lucee core that is needed by the log engine, everything goes
 * through the interfaces of the loader.
 */
public final class Util {

	private Util() {
	}

	public static CFMLEngine engine() {
		return CFMLEngineFactory.getInstance();
	}

	public static Cast cast() {
		return engine().getCastUtil();
	}

	public static boolean isEmpty(String str) {
		return str == null || str.length() == 0;
	}

	public static boolean isEmpty(String str, boolean trim) {
		return str == null || (trim ? str.trim().length() == 0 : str.length() == 0);
	}

	public static String toString(Object obj, String defaultValue) {
		return cast().toString(obj, defaultValue);
	}

	public static boolean toBooleanValue(Object obj, boolean defaultValue) {
		return cast().toBooleanValue(obj, defaultValue);
	}

	public static Boolean toBoolean(Object obj, Boolean defaultValue) {
		return cast().toBoolean(obj, defaultValue);
	}

	public static int toIntValue(Object obj, int defaultValue) {
		return cast().toIntValue(obj, defaultValue);
	}

	public static long toLongValue(Object obj, long defaultValue) {
		return cast().toLongValue(obj, defaultValue);
	}

	/**
	 * a lock object shared by everything asking for the same type and name
	 */
	public static String createToken(String type, String name) {
		return (type + ":" + name).intern();
	}

	public static String getSystemPropOrEnvVar(String name, String defaultValue) {
		String value = System.getProperty(name);
		if (value == null) value = System.getenv(name);
		if (value == null) value = System.getenv(name.replace('.', '_').toUpperCase());
		return value != null ? value : defaultValue;
	}

	public static boolean isWindows() {
		return engine().getSystemUtil().isWindows();
	}


	private static lucee.runtime.ext.function.BIF serializeBIF;
	private static lucee.runtime.ext.function.BIF deserializeBIF;

	private static lucee.runtime.PageContext[] pageContext(CFMLEngine engine) throws PageException {
		lucee.runtime.PageContext pc = engine.getThreadPageContext();
		if (pc != null) return new lucee.runtime.PageContext[] { pc, null };
		try {
			pc = engine.createPageContext(engine.getCastUtil().toFile(engine.getResourceUtil().getTempDirectory()), "localhost", "/", "", null, null, null, null,
					new java.io.ByteArrayOutputStream(), -1, true);
			return new lucee.runtime.PageContext[] { pc, pc };
		}
		catch (Exception e) {
			throw toPageException(e);
		}
	}

	/**
	 * serializes to JSON using the SerializeJSON function
	 */
	public static String serializeJSON(Object value, boolean compact) throws PageException {
		CFMLEngine engine = engine();
		lucee.runtime.PageContext[] pcs = pageContext(engine);
		try {
			if (serializeBIF == null) serializeBIF = engine.getClassUtil().loadBIF(pcs[0], "lucee.runtime.functions.conversion.SerializeJSON");
			return cast().toString(serializeBIF.invoke(pcs[0], new Object[] { value, "struct", Boolean.FALSE, Boolean.valueOf(compact) }));
		}
		catch (PageException pe) {
			throw pe;
		}
		catch (Exception e) {
			throw toPageException(e);
		}
		finally {
			if (pcs[1] != null) engine.releasePageContext(pcs[1], true);
		}
	}

	/**
	 * parses JSON using the DeserializeJSON function
	 */
	public static Object deserializeJSON(String json) throws PageException {
		CFMLEngine engine = engine();
		lucee.runtime.PageContext[] pcs = pageContext(engine);
		try {
			if (deserializeBIF == null) deserializeBIF = engine.getClassUtil().loadBIF(pcs[0], "lucee.runtime.functions.conversion.DeserializeJSON");
			return deserializeBIF.invoke(pcs[0], new Object[] { json, Boolean.FALSE });
		}
		catch (PageException pe) {
			throw pe;
		}
		catch (Exception e) {
			throw toPageException(e);
		}
		finally {
			if (pcs[1] != null) engine.releasePageContext(pcs[1], true);
		}
	}

	public static Charset toCharset(String charset, Charset defaultValue) {
		if (isEmpty(charset, true)) return defaultValue;
		try {
			return Charset.forName(charset.trim());
		}
		catch (Exception e) {
			return defaultValue;
		}
	}

	public static TimeZone getTimeZone(Config config) {
		if (config != null) {
			TimeZone tz = config.getTimeZone();
			if (tz != null) return tz;
		}
		return TimeZone.getDefault();
	}

	/**
	 * the timezone of the server config, never the timezone of the current request, a log file is
	 * shared by all applications
	 */
	public static Config currentConfig(Config config) {
		if (config != null) return config;
		try {
			Config c = engine().getThreadConfig();
			if (c != null) return c;
		}
		catch (Throwable t) {
		}
		return defaultConfig;
	}

	private static volatile Config defaultConfig;

	/**
	 * the config used when the current thread has none, for example when the log entry is written by a
	 * background thread
	 */
	public static void setDefaultConfig(Config config) {
		defaultConfig = config;
	}

	public static Resource toResource(Config config, String path, String name) {
		Resource res = null;
		short type = lucee.runtime.util.ResourceUtil.TYPE_FILE;
		if (!isEmpty(path, true)) {
			res = engine().getResourceUtil().toConfigResource(config, config.getConfigDir(), path.trim(), type);
			if (res != null && res.isDirectory()) res = res.getRealResource(name + ".log");
		}
		if (res == null) res = engine().getResourceUtil().toConfigResource(config, config.getConfigDir(), "logs/" + name + ".log", type);
		return res;
	}

	public static void rethrowIfNecessary(Throwable t) {
		if (t instanceof ThreadDeath) throw (ThreadDeath) t;
		if (t instanceof OutOfMemoryError) throw (OutOfMemoryError) t;
	}

	public static PageException toPageException(Throwable t) {
		return cast().toPageException(t);
	}

	/**
	 * errors of the log engine itself cannot be logged with the logger they come from, they go to the
	 * application log of the config and as last resort to the error stream
	 */
	public static void logGlobal(int level, String type, String msg) {
		logInternal(null, level, type, msg);
	}

	public static void logGlobal(Config config, String type, Throwable t) {
		logInternal(config, type, null, t);
	}

	public static void logGlobal(String type, String msg, Throwable t) {
		logInternal(null, type, msg, t);
	}

	public static void logInternal(Config config, String type, Throwable t) {
		logInternal(config, type, null, t);
	}

	public static void logInternal(Config config, String type, String msg, Throwable t) {
		try {
			config = currentConfig(config);
			if (config != null) {
				Log log = config.getLog("application");
				if (log != null) {
					log.log(Log.LEVEL_ERROR, type, msg, t);
					return;
				}
			}
		}
		catch (Throwable th) {
			rethrowIfNecessary(th);
		}
		System.err.println(type + (msg != null ? ": " + msg : ""));
		if (t != null) t.printStackTrace();
	}

	public static void logInternal(Config config, int level, String type, String msg) {
		try {
			config = currentConfig(config);
			if (config != null) {
				Log log = config.getLog("application");
				if (log != null) {
					log.log(level, type, msg);
					return;
				}
			}
		}
		catch (Throwable th) {
			rethrowIfNecessary(th);
		}
		System.err.println(type + ": " + msg);
	}

	public static String getStacktrace(Throwable t, boolean onlyLuceePart) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		pw.close();
		String st = sw.toString();
		if (onlyLuceePart && st.indexOf("Caused by:") == -1) {
			int index = st.indexOf("lucee.loader.servlet.CFMLServlet.service(");
			if (index == -1) index = st.indexOf("lucee.runtime.jsr223.ScriptEngineImpl.eval(");
			if (index != -1) {
				index = st.indexOf(")", index + 1);
				if (index != -1) st = st.substring(0, index + 1) + "\n...";
			}
		}
		return st;
	}

	/**
	 * calls the setter for the given property with a value converted to the type of the setter
	 *
	 * @return true if a matching setter was found and called
	 */
	public static boolean callSetter(Object obj, String property, Object value) throws Exception {
		String name = "set" + property;
		for (Method m: obj.getClass().getMethods()) {
			if (m.getParameterCount() != 1 || !m.getName().equalsIgnoreCase(name)) continue;
			Class<?> type = m.getParameterTypes()[0];
			Object arg;
			if (type == String.class) arg = toString(value, null);
			else if (type == boolean.class || type == Boolean.class) arg = Boolean.valueOf(toBooleanValue(value, false));
			else if (type == int.class || type == Integer.class) arg = Integer.valueOf(toIntValue(value, 0));
			else if (type == long.class || type == Long.class) arg = Long.valueOf(toLongValue(value, 0));
			else if (type.isInstance(value)) arg = value;
			else continue;
			m.invoke(obj, arg);
			return true;
		}
		return false;
	}
}
