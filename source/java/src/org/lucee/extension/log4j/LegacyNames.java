package org.lucee.extension.log4j;

/**
 * Recognizes the names a log appender or layout was known by over time: the simple name, the class
 * names of the former implementations in the Lucee core, the names of this extension and the class
 * names of the built-in JUL engine of the core. A configuration can contain any of them, no matter
 * which log engine wrote it.
 */
public final class LegacyNames {

	private LegacyNames() {
	}

	private static final String JUL = "lucee.commons.io.log.JULLogEngine$";

	public static boolean isConsoleAppender(String name) {
		return "console".equalsIgnoreCase(name) || (JUL + "ConsoleAppenderType").equals(name) || "lucee.commons.io.log.log4j.appender.ConsoleAppender".equals(name)
				|| "lucee.commons.io.log.log4j2.appender.ConsoleAppender".equals(name) || "org.lucee.extension.log4j.appender.ConsoleAppender".equals(name);
	}

	public static boolean isResourceAppender(String name) {
		return "resource".equalsIgnoreCase(name) || (JUL + "ResourceAppenderType").equals(name) || "lucee.commons.io.log.log4j.appender.RollingResourceAppender".equals(name)
				|| "lucee.commons.io.log.log4j2.appender.ResourceAppender".equals(name) || "org.lucee.extension.log4j.appender.ResourceAppender".equals(name);
	}

	public static boolean isDatasourceAppender(String name) {
		return "datasource".equalsIgnoreCase(name) || "lucee.commons.io.log.log4j.appender.DatasourceAppender".equals(name)
				|| "lucee.commons.io.log.log4j2.appender.DatasourceAppender".equals(name) || "org.lucee.extension.log4j.appender.DatasourceAppender".equals(name);
	}

	public static boolean isClassicLayout(String name) {
		return Util.isEmpty(name, true) || "classic".equalsIgnoreCase(name) || "datasource".equalsIgnoreCase(name) || (JUL + "ClassicLayoutType").equals(name)
				|| "lucee.commons.io.log.log4j.layout.ClassicLayout".equals(name) || "lucee.commons.io.log.log4j2.layout.ClassicLayout".equals(name)
				|| "lucee.commons.io.log.log4j.layout.DatasourceLayout".equals(name) || "org.lucee.extension.log4j.layout.ClassicLayout".equals(name);
	}

	public static boolean isPatternLayout(String name) {
		return "pattern".equalsIgnoreCase(name) || (JUL + "PatternLayoutType").equals(name) || "org.apache.log4j.PatternLayout".equals(name)
				|| "org.apache.logging.log4j.core.layout.PatternLayout".equals(name);
	}

	public static boolean isHtmlLayout(String name) {
		return "html".equalsIgnoreCase(name) || "org.apache.log4j.HTMLLayout".equals(name) || "org.apache.logging.log4j.core.layout.HtmlLayout".equals(name);
	}

	public static boolean isXmlLayout(String name) {
		return "xml".equalsIgnoreCase(name) || "org.apache.log4j.xml.XMLLayout".equalsIgnoreCase(name) || "org.apache.logging.log4j.core.layout.XmlLayout".equalsIgnoreCase(name)
				|| "lucee.commons.io.log.log4j2.layout.XMLLayout".equals(name) || "org.lucee.extension.log4j.layout.XMLLayout".equals(name);
	}

	public static boolean isJsonLayout(String name) {
		return "json".equalsIgnoreCase(name) || "org.apache.log4j.json.JsonTemplateLayout".equalsIgnoreCase(name) || "org.apache.logging.log4j.core.layout.JsonLayout".equalsIgnoreCase(name)
				|| "lucee.commons.io.log.log4j2.layout.JsonLayout".equals(name) || "org.lucee.extension.log4j.layout.JsonLayout".equals(name);
	}

	public static boolean isDataDogLayout(String name) {
		return "datadog".equalsIgnoreCase(name) || (name != null && name.indexOf(".DataDogLayout") != -1);
	}
}
