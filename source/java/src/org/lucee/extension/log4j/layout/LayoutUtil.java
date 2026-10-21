package org.lucee.extension.log4j.layout;

import org.apache.logging.log4j.core.LogEvent;

public final class LayoutUtil {
	public static Object getLoggerName(LogEvent event) {
		String name = event.getLoggerName();
		if (org.lucee.extension.log4j.Util.isEmpty(name)) {
			return "root";
		}
		if (name.startsWith("web.")) {
			int index = name.indexOf('.', 4);
			if (index != -1) name = name.substring(index + 1);
		}
		else if (name.startsWith("server.")) {
			name = name.substring(7);
		}

		return name;
	}
}
