package org.lucee.extension.log4j;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.apache.logging.log4j.core.layout.HtmlLayout;
import org.apache.logging.log4j.core.layout.HtmlLayout.Builder;
import org.apache.logging.log4j.core.layout.HtmlLayout.FontSize;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.util.NetUtils;
import org.apache.logging.log4j.status.StatusLogger;

import lucee.commons.io.log.Log;
import lucee.commons.io.log.LogEngine;
import org.lucee.extension.log4j.appender.ConsoleAppender;
import org.lucee.extension.log4j.appender.DatasourceAppender;
import org.lucee.extension.log4j.appender.ResourceAppender;
import org.lucee.extension.log4j.appender.TaskAppender;
import org.lucee.extension.log4j.layout.ClassicLayout;
import org.lucee.extension.log4j.layout.DataDogLayout;
import org.lucee.extension.log4j.layout.JsonLayout;
import org.lucee.extension.log4j.layout.XMLLayout;
import lucee.commons.io.res.Resource;
import lucee.commons.io.retirement.RetireListener;
import lucee.runtime.config.Config;
import lucee.runtime.db.ClassDefinition;
import lucee.runtime.exp.PageException;

public final class Log4jEngine implements LogEngine {

	public static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024;
	public static final int DEFAULT_MAX_BACKUP_INDEX = 10;

	// what the configuration contains: the class names of this extension, the names of the former built-in
	// implementation and the simple names are all accepted and translated to these
	private static final String APPENDER_CONSOLE = ConsoleAppender.class.getName();
	private static final String APPENDER_RESOURCE = ResourceAppender.class.getName();
	private static final String APPENDER_DATASOURCE = DatasourceAppender.class.getName();
	private static final String LAYOUT_CLASSIC = ClassicLayout.class.getName();
	private static final String LAYOUT_XML = XMLLayout.class.getName();
	private static final String LAYOUT_JSON = JsonLayout.class.getName();
	private static final String LAYOUT_DATADOG = DataDogLayout.class.getName();
	private static final String LAYOUT_HTML = HtmlLayout.class.getName();
	private static final String LAYOUT_PATTERN = PatternLayout.class.getName();

	private static final String DEFAULT_PATTERN = "%d{dd.MM.yyyy HH:mm:ss,SSS} %-5p [%c] %m%n";

	private static Map<String, LogAdapter> loggers = new ConcurrentHashMap<>();
	private Config config;
	private String version;
	private static Appender fallback;

	public Log4jEngine() {
	}

	@Override
	public void init(Config config) {
		this.config = config;
		Util.setDefaultConfig(config);
		// performance settings, they have to be set before log4j is used the first time
		System.setProperty("log4j2.hostName", "localhost"); // prevents DNS lookup
		System.setProperty("log4j2.disable.jmx", "true");
		System.setProperty("log4j2.asyncLoggerConfigRingBufferSize", "262144");
		init();
		// force localhost resolution early
		Thread t = new Thread(() -> {
			try {
				NetUtils.getLocalHostname();
			}
			catch (Exception e) {
			}
		}, "Log4jPrepare");
		t.setDaemon(true);
		t.start();
	}

	@Override
	public Log getConsoleLog(boolean errorStream, String name, int level) {
		PrintWriter pw = errorStream ? config.getErrWriter() : config.getOutWriter();
		if (pw == null) pw = new PrintWriter(errorStream ? System.err : System.out);

		return _getLogger(config, getConsoleAppender(name, pw, PatternLayout.newBuilder().withPattern(getDefaultPattern(config)).build(), true), name, level);
	}

	@Override
	public Log getResourceLog(Resource res, Charset charset, String name, int level, int timeout, RetireListener listener, boolean async) throws PageException {
		Appender a = toResourceAppender(name, res, new ClassicLayout(config), charset, DEFAULT_MAX_BACKUP_INDEX, DEFAULT_MAX_FILE_SIZE, timeout, true);
		if (async) {
			a = new TaskAppender(config, a);
		}
		return _getLogger(config, a, name, level);
	}

	private static ClassDefinition cd(String className) {
		return Util.engine().getCreationUtil().createClassDefinition(className);
	}

	private static ClassDefinition cd(Class<?> clazz) {
		return Util.engine().getCreationUtil().createClassDefinition(clazz);
	}

	@Override
	public ClassDefinition appenderClassDefintion(String className) {
		if (LegacyNames.isConsoleAppender(className)) return cd(ConsoleAppender.class);
		if (LegacyNames.isResourceAppender(className)) return cd(ResourceAppender.class);
		if (LegacyNames.isDatasourceAppender(className)) return cd(DatasourceAppender.class);
		return cd(className);
	}

	@Override
	public ClassDefinition layoutClassDefintion(String className) {
		if (LegacyNames.isClassicLayout(className)) return cd(ClassicLayout.class);
		if (LegacyNames.isHtmlLayout(className)) return cd(HtmlLayout.class);
		if (LegacyNames.isXmlLayout(className)) return cd(XMLLayout.class);
		if (LegacyNames.isJsonLayout(className)) return cd(JsonLayout.class);
		if (LegacyNames.isPatternLayout(className)) return cd(PatternLayout.class);
		if (LegacyNames.isDataDogLayout(className)) return cd(DataDogLayout.class);
		return cd(className);
	}

	@Override
	public final Object getLayout(ClassDefinition cd, Map<String, String> layoutArgs, ClassDefinition cdAppender, String name) throws PageException {
		if (layoutArgs == null) layoutArgs = new HashMap<String, String>();
		lowerCase(layoutArgs);
		// Layout
		Layout layout = null;

		if (cd != null && cd.hasClass()) {
			// Classic Layout
			if (LAYOUT_CLASSIC.equalsIgnoreCase(cd.getClassName())) {
				layout = new ClassicLayout(config);
			}
			// HTML Layout
			else if (LAYOUT_HTML.equalsIgnoreCase(cd.getClassName())) {
				Builder builder = HtmlLayout.newBuilder();

				// Location Info
				Boolean locInfo = Util.toBoolean(layoutArgs.get("locationinfo"), null);
				if (locInfo != null) builder.withLocationInfo(locInfo.booleanValue());
				else locInfo = Boolean.FALSE;
				layoutArgs.put("locationinfo", locInfo.toString());

				// Title
				String title = Util.toString(layoutArgs.get("title"), "");
				if (!Util.isEmpty(title, true)) builder.withTitle(title);
				layoutArgs.put("title", title);

				// font name
				String fontName = Util.toString(layoutArgs.get("fontname"), "");
				if (!Util.isEmpty(fontName, true)) builder.withFontName(fontName);
				layoutArgs.put("fontname", fontName);

				// font size
				FontSize fontSize = toFontSize(Util.toString(layoutArgs.get("fontsize"), null));
				if (fontSize != null) builder.withFontSize(fontSize);
				layoutArgs.put("fontsize", fontSize == null ? "" : fontSize.name());

				layout = builder.build();

			}
			// XML Layout
			else if (LAYOUT_XML.equalsIgnoreCase(cd.getClassName())) {
				// Charset
				Charset charset = Util.toCharset(layoutArgs.get("charset"), StandardCharsets.UTF_8);

				// Location Info
				boolean locInfo = Util.toBooleanValue(layoutArgs.get("locationinfo"), false);
				layoutArgs.put("locationinfo", locInfo + "");

				// Properties TODO
				Boolean props = Util.toBoolean(layoutArgs.get("properties"), Boolean.FALSE);
				layoutArgs.put("properties", props.toString());
				// TODO add more attribute

				return new XMLLayout(charset, true, locInfo);
			}
			// JSON Layout
			else if (LAYOUT_JSON.equalsIgnoreCase(cd.getClassName())) {
				// enviroment variables
				String[] envNames = null;
				String tmp = layoutArgs.get("envnames");
				if (!Util.isEmpty(tmp, true)) {
					List<String> list = java.util.Arrays.asList(tmp.split(","));
					List<String> list2 = new ArrayList<>();
					for (String el: list) {
						if (!Util.isEmpty(el, true)) list2.add(el);
					}

					if (!list2.isEmpty()) envNames = list2.toArray(new String[list2.size()]);
				}
				// charset
				Charset charset = Util.toCharset(layoutArgs.get("charset"), StandardCharsets.UTF_8);
				// complete
				boolean complete = Util.toBooleanValue(layoutArgs.get("complete"), false);
				// includeStacktrace
				boolean includeStacktrace = Util.toBooleanValue(layoutArgs.get("includestacktrace"), true);
				// includeTimeMillis
				boolean includeTimeMillis = Util.toBooleanValue(layoutArgs.get("includetimemillis"), true);
				// stacktraceAsString
				boolean stacktraceAsString = Util.toBooleanValue(layoutArgs.get("stacktraceasstring"), false);
				// locationInfo
				boolean locationInfo = Util.toBooleanValue(layoutArgs.get("locationinfo"), false);
				// properties
				boolean properties = Util.toBooleanValue(layoutArgs.get("properties"), true);
				// compact
				boolean compact = Util.toBooleanValue(layoutArgs.get("compact"), false);

				return new JsonLayout(charset, complete, compact, includeStacktrace, includeTimeMillis, stacktraceAsString, locationInfo, properties, envNames);
			}

			// Pattern Layout
			else if (LAYOUT_PATTERN.equalsIgnoreCase(cd.getClassName())) {
				org.apache.logging.log4j.core.layout.PatternLayout.Builder builder = PatternLayout.newBuilder();

				// pattern
				String pattern = Util.toString(layoutArgs.get("pattern"), null);
				if (!Util.isEmpty(pattern, true) && !DEFAULT_PATTERN.equals(pattern)) builder.withPattern(pattern);
				else {
					builder.withPattern(getDefaultPattern(config));
					layoutArgs.put("pattern", DEFAULT_PATTERN);
				}

				layout = builder.build();
			}
			// DataDog Layout
			else if (cd.getClassName().indexOf(".DataDogLayout") != -1) {
				layout = new DataDogLayout();
			}
			// class definition
			else {
				try {
					Class<?> clazz = cd.getClazz(null);
					Object obj = clazz != null ? Util.engine().getClassUtil().loadInstance(clazz) : null;
					if (obj instanceof Layout) {
						Util.callSetter(obj, "name", name);
						for (Entry<String, String> entry: layoutArgs.entrySet()) {
							Util.callSetter(obj, entry.getKey(), entry.getValue());
						}
						layout = (Layout) obj;
					}
				}
				catch (Exception e) {
					throw Util.toPageException(e);
				}
			}
		}
		if (layout != null) return layout;
		return new ClassicLayout(config);
	}

	@Override
	public final Object getAppender(Config config, Object layout, String name, ClassDefinition cd, Map<String, String> appenderArgs) {
		if (appenderArgs == null) appenderArgs = new HashMap<String, String>();
		// Appender
		Appender appender = null;
		if (cd != null && cd.hasClass()) {
			// Console Appender
			if (APPENDER_CONSOLE.equalsIgnoreCase(cd.getClassName())) {
				// stream-type
				boolean doError = false;
				String st = Util.toString(appenderArgs.get("streamtype"), null);
				if (!Util.isEmpty(st, true)) {
					st = st.trim().toLowerCase();
					if (st.equals("err") || st.equals("error")) doError = true;
				}
				appenderArgs.put("streamtype", doError ? "error" : "output");

				// get print writer
				PrintWriter pw;
				if (doError) {
					if (config.getErrWriter() == null) pw = new PrintWriter(System.err);
					else pw = config.getErrWriter();
				}
				else {
					if (config.getOutWriter() == null) pw = new PrintWriter(System.out);
					else pw = config.getOutWriter();
				}
				Layout l;
				try {
					l = toLayout(layout);
				}
				catch (Exception e) {
					Util.logGlobal(config, "loading-log", e);
					l = new ClassicLayout(config);
				}
				appender = getConsoleAppender(name, pw, l, true);
			}
			else if (APPENDER_DATASOURCE.equalsIgnoreCase(cd.getClassName())) {
				// datasource
				String dsn = Util.toString(appenderArgs.get("datasource"), null);
				if (Util.isEmpty(dsn, true)) dsn = Util.toString(appenderArgs.get("datasourceName"), null);
				if (!Util.isEmpty(dsn, true)) dsn = dsn.trim();
				appenderArgs.put("datasource", dsn);

				// username
				String user = Util.toString(appenderArgs.get("username"), null);
				if (Util.isEmpty(user, true)) user = Util.toString(appenderArgs.get("user"), null);
				if (!Util.isEmpty(user, true)) user = user.trim();
				else user = null;
				appenderArgs.put("username", user);

				// password
				String pass = Util.toString(appenderArgs.get("password"), null);
				if (Util.isEmpty(pass, true)) pass = Util.toString(appenderArgs.get("pass"), null);
				if (!Util.isEmpty(pass, true)) pass = pass.trim();
				else pass = null;
				appenderArgs.put("password", pass);

				// table
				String table = Util.toString(appenderArgs.get("table"), null);
				if (!Util.isEmpty(table, true)) table = table.trim();
				else table = "LOGS";
				appenderArgs.put("table", table);

				// custom
				String custom = Util.toString(appenderArgs.get("custom"), null);
				if (!Util.isEmpty(custom, true)) custom = custom.trim();
				else custom = null;
				appenderArgs.put("custom", custom);
				// load appender
				try {
					appender = getDatasourceAppender(config, name, dsn, user, pass, table, custom, true);
				}
				catch (Exception e) {
					Util.logGlobal(config, "loading-log", e);
				}
			}
			else if (APPENDER_RESOURCE.equalsIgnoreCase(cd.getClassName())) {

				// path
				Resource res = Util.toResource(config, Util.toString(appenderArgs.get("path"), null), name);

				// charset
				Charset charset = Util.toCharset(Util.toString(appenderArgs.get("charset"), null), null);
				if (charset == null) {
					charset = config.getResourceCharset();
					appenderArgs.put("charset", charset.name());
				}

				// maxfiles
				int maxfiles = Util.toIntValue(appenderArgs.get("maxfiles"), 10);
				appenderArgs.put("maxfiles", String.valueOf(maxfiles));

				// maxfileSize
				long maxfilesize = Util.toLongValue(appenderArgs.get("maxfilesize"), 1024 * 1024 * 10);
				appenderArgs.put("maxfilesize", String.valueOf(maxfilesize));

				// timeout
				int timeout = Util.toIntValue(appenderArgs.get("timeout"), 60); // timeout in seconds
				appenderArgs.put("timeout", String.valueOf(timeout));
				try {
					appender = toResourceAppender(name, res, toLayout(layout), charset, maxfiles, maxfilesize, timeout, true);
				}
				catch (Exception e) {
					Util.logGlobal(config, "loading-log", e);
				}

			}
			// class definition
			else {
				try {
					Class<?> clazz = cd.getClazz(null);
					Object obj = clazz != null ? Util.engine().getClassUtil().loadInstance(clazz) : null;

					if (obj instanceof Appender) {
						appender = (Appender) obj;
						Util.callSetter(obj, "name", name);
						Util.callSetter(obj, "layout", layout);
						Iterator<Entry<String, String>> it = appenderArgs.entrySet().iterator();
						Entry<String, String> entry;
						while (it.hasNext()) {
							entry = it.next();
							try {
							Util.callSetter(obj, entry.getKey(), entry.getValue());
						}
						catch (Exception e) {
							throw Util.toPageException(e);
						}
						}
					}
				}
				catch (Exception e) {
					Util.logGlobal(config, "loading-log", e);
					appender = null;
				}
			}
		}
		// if (appender instanceof AppenderSkeleton) {
		// TODO ((AppenderSkeleton) appender).activateOptions();
		// }
		if (appender == null) {
			PrintWriter pw;
			if (config.getOutWriter() == null) pw = new PrintWriter(System.out);
			else pw = config.getOutWriter();
			Layout l;
			try {
				l = toLayout(layout);
			}
			catch (Exception e) {
				Util.logGlobal(config, "loading-log", e);
				appender = null;
				l = new ClassicLayout(config);
			} // l = new ClassicLayout();
			appender = getConsoleAppender(name, pw, l, true);
		}

		return appender;
	}

	@Override
	public Log getLogger(Config config, Object appender, String name, int level) throws PageException {
		return _getLogger(config, toAppender(appender), name, level);
	}

	private static final LogAdapter _getLogger(Config config, Appender appender, String name, int level) {
		Level le = LogAdapter.toLevel(level);
		if (!(LogManager.getFactory() instanceof org.apache.logging.log4j.core.impl.Log4jContextFactory)) {
			init();
		}

		String fullname = name;

		// fullname

		Logger l = LogManager.getLogger(fullname);
		if (l instanceof org.apache.logging.log4j.core.Logger) {
			org.apache.logging.log4j.core.Logger cl = (org.apache.logging.log4j.core.Logger) l;
			for (Appender a: cl.getAppenders().values()) {
				cl.removeAppender(a);
			}

			cl.setAdditive(false);
			cl.addAppender(appender);
			cl.setLevel(LogAdapter.toLevel(level));
		}
		else {
			l.atLevel(LogAdapter.toLevel(level));
		}
		LogAdapter la = new LogAdapter(l, le);
		loggers.put(fullname, la);

		// rest the log level of all existing new, because they get lost when creating a new one
		for (LogAdapter tmp: loggers.values()) {
			tmp.validate();
		}

		return la;
	}

	private static String createFullName(String name) {
		return name;
	}

	private static void init() {
		StatusLogger.getLogger().setLevel(Level.FATAL);
		LogManager.setFactory(new org.apache.logging.log4j.core.impl.Log4jContextFactory());
		PluginManager.addPackage("");
	}

	@Override
	public void closeAppender(Object appender) throws PageException {
		toAppender(appender).stop();
	}

	private Appender toAppender(Object l) throws PageException {
		if (l instanceof Appender) return (Appender) l;
		throw Util.engine().getExceptionUtil().createApplicationException("cannot convert [" + l + "] to an Appender");
	}

	private Layout toLayout(Object l) throws PageException {
		if (l instanceof Layout) return (Layout) l;
		throw Util.engine().getExceptionUtil().createApplicationException("cannot convert [" + l + "] to a Layout");
	}

	@Override
	public Object getDefaultLayout() {

		return PatternLayout.newBuilder().withPattern(getDefaultPattern(config)).build();
	}

	// %d without a timezone uses the JVM default, the log has to use the timezone of the server config
	private static String getDefaultPattern(Config config) {
		return "%d{dd.MM.yyyy HH:mm:ss,SSS}{" + Util.getTimeZone(config).getID() + "} %-5p [%c] %m%n";
	}

	@Override
	public Object getClassicLayout() {
		return new ClassicLayout(config);
	}

	private static Appender getDatasourceAppender(Config config, String name, String dsn, String user, String pass, String table, String custom, boolean start) {

		DatasourceAppender appender = new DatasourceAppender(config, getFallback(config), name, null, dsn, user, pass, table, custom);

		if (start) appender.start();
		return appender;
	}

	private static Appender getConsoleAppender(String name, PrintWriter pw, Layout<?> layout, boolean start) {
		WriterAppender appender = WriterAppender.newBuilder()

				.setName(name)

				.setTarget(pw)

				.setLayout(layout)

				.build();
		if (start) appender.start();
		return appender;
	}

	private Appender toResourceAppender(String name, Resource res, Layout<?> layout, Charset charset, int maxfiles, long maxFileSize, int timeout, boolean start) {
		ResourceAppender appender = new ResourceAppender(name, null, layout, res, charset, true, timeout, maxFileSize, maxfiles, null);
		if (start) appender.start();
		return appender;

	}

	private FontSize toFontSize(String str) {
		if (Util.isEmpty(str, true)) return null;
		str = str.trim();
		if ("large".equalsIgnoreCase(str)) return FontSize.LARGE;
		if ("larger".equalsIgnoreCase(str)) return FontSize.LARGER;
		if ("medium".equalsIgnoreCase(str)) return FontSize.MEDIUM;
		if ("small".equalsIgnoreCase(str)) return FontSize.SMALL;
		if ("smaller".equalsIgnoreCase(str)) return FontSize.SMALLER;
		if ("xlarge".equalsIgnoreCase(str)) return FontSize.XLARGE;
		if ("xsmall".equalsIgnoreCase(str)) return FontSize.XSMALL;
		if ("xxlarge".equalsIgnoreCase(str)) return FontSize.XXLARGE;
		if ("xxsmall".equalsIgnoreCase(str)) return FontSize.XXSMALL;

		return null;
	}

	@Override
	public String getVersion() {
		if (version == null) {
			String v = LogManager.class.getPackage().getImplementationVersion();
			version = Util.isEmpty(v, true) ? "2" : v;
		}
		return version;
	}

	private static Appender getFallback(Config config) {
		if (fallback == null) {
			PrintWriter pw;
			if (config.getErrWriter() == null) pw = new PrintWriter(System.err);
			else pw = config.getErrWriter();
			fallback = getConsoleAppender("fallback", pw, PatternLayout.newBuilder().withPattern(getDefaultPattern(config)).build(), true);
		}
		return fallback;
	}

	private static void lowerCase(Map<String, String> map) {
		List<String> changes = null;
		for (String k: map.keySet()) {
			if (hasUpperCase(k)) {
				if (changes == null) changes = new ArrayList<>();
				changes.add(k);
			}
		}
		if (changes != null) {
			synchronized (map) {
				if (changes != null) {
					for (String k: changes) {
						map.put(k.toLowerCase(), map.remove(k));
					}
				}
			}
		}
	}

	private static boolean hasUpperCase(String str) {
		if (Util.isEmpty(str, true)) return false;
		for (int i = str.length() - 1; i >= 0; i--) {
			if (Character.isUpperCase(str.charAt(i))) return true;
		}
		return false;
	}
}
