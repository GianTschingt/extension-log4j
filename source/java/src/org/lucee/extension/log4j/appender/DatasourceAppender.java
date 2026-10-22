package org.lucee.extension.log4j.appender;

import org.lucee.extension.log4j.Util;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.TimeZone;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;

import lucee.loader.engine.CFMLEngine;
import lucee.runtime.PageContext;
import lucee.runtime.util.Creation;
import lucee.runtime.util.DBUtil;
import lucee.runtime.config.Config;
import lucee.runtime.config.ConfigWeb;
import lucee.runtime.db.DataSource;
import lucee.runtime.db.DatasourceConnection;
import lucee.runtime.db.SQL;
import lucee.runtime.db.SQLItem;
import lucee.runtime.exp.PageException;

public final class DatasourceAppender extends AbstractAppender {

	// private DatasourceManagerImpl manager;
	private DataSource datasource;
	private final String datasourceName;
	private final String username;
	private final String password;
	private final String tableName;
	private final Config config;
	private String custom;
	private Appender fallback;
	private boolean isInit;
	private Object token = new Object();

	public DatasourceAppender(Config config, Appender fallback, String name, Filter filter, String datasource, String username, String password, String tableName, String custom) {
		super(name, filter, null);
		this.datasourceName = datasource;
		this.username = username;
		this.password = password;
		this.config = config;
		this.tableName = tableName;
		this.custom = custom;
		this.fallback = fallback;
	}

	public String getTableName() {
		return tableName;
	}

	@Override
	public void append(LogEvent event) {
		if (!isInit) {
			init();
			if (!isInit) {
				fallback.append(event);
				return;
			}
		}

		DatasourceConnection conn = null;
		try {

			// ththreadId=
			String threadId = event.getThreadName();
			if (threadId.length() > 64) threadId = threadId.substring(0, 63);

			// split application->message
			String application;
			String msg = Util.toString(event.getMessage(), null);
			int index = msg.indexOf("->");
			if (index > -1) {
				application = msg.substring(0, index);
				if (application.length() > 64) application = application.substring(0, 63);
				msg = msg.substring(index + 2);
			}
			else application = "";

			if (msg.length() > 512) msg = msg.substring(0, 508) + "...";

			// get Exception
			String exception = "";
			Throwable t = event.getThrown();
			if (t != null) {
				String em = t.getMessage();
				if (Util.isEmpty(em, true)) em = t.getClass().getName();
				if (Util.isEmpty(msg)) msg = em;
				else msg += ";" + em;

				exception = Util.getStacktrace(t, false);
				if (exception == null) exception = "";
				else if (exception.length() > 2048) exception = exception.substring(0, 2044) + "...";
			}

			// id
			String id = "";
			Config c = Util.currentConfig(null);
			if (c != null) {
				if (c instanceof ConfigWeb) id = ((ConfigWeb) c).getLabel();
				else id = c.getIdentification().getId();
			}

			conn = getConnection();
			PageContext optionalPC = Util.engine().getThreadPageContext();

			CFMLEngine engine = Util.engine();
			Creation creator = engine.getCreationUtil();
			DBUtil db = engine.getDBUtil();
			SQL sql = db.toSQL("INSERT INTO " + tableName + " (id,name,severity,threadid,time,application,message,exception,custom) values(?,?,?,?,?,?,?,?,?)",
					new SQLItem[] { db.toSQLItem(id, Types.VARCHAR), db.toSQLItem(getName(), Types.VARCHAR), db.toSQLItem(event.getLevel().name(), Types.VARCHAR),
							db.toSQLItem(threadId, Types.VARCHAR), db.toSQLItem(creator.createDateTime(event.getTimeMillis()), Types.TIMESTAMP), db.toSQLItem(application, Types.VARCHAR),
							db.toSQLItem(msg, Types.VARCHAR), db.toSQLItem(exception, Types.VARCHAR), db.toSQLItem(custom == null ? "" : custom, Types.VARCHAR) });
			insert(optionalPC, conn, sql, Util.getTimeZone(config));
		}
		catch (PageException pe) {
			Util.logGlobal(config, "log-loading", pe);
		}
		finally {
			try {
				relConnection(conn);
			}
			catch (PageException pee) {
				Util.logGlobal(config, "log-loading", pee);
			}
		}
	}

	// not done with QueryImpl, that binds the time with the request timezone, but the log table is shared by all applications
	private static void insert(PageContext pc, DatasourceConnection dc, SQL sql, TimeZone tz) throws PageException {
		PreparedStatement ps = null;
		try {
			ps = dc.getPreparedStatement(sql, false, false);
			SQLItem[] items = sql.getItems();
			for (int i = 0; i < items.length; i++) {
				Util.engine().getDBUtil().setValue(pc, tz, ps, i + 1, items[i]);
			}
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw Util.toPageException(e);
		}
		finally {
			Util.engine().getDBUtil().closeSilent(ps);
		}
	}

	private void init() {
		if (!isInit) {
			synchronized (token) {
				if (!isInit) {
					DatasourceConnection conn = null;
					PageContext optionalPC = Util.engine().getThreadPageContext();
					SQL sql = Util.engine().getDBUtil().toSQL("select 1 from " + tableName + " where 1=0", new SQLItem[0]);
					try {
						conn = getConnection();
						try {
							Util.engine().getCreationUtil().createQuery(conn, sql, -1, -1, -1, "query");
							isInit = true;
						}
						catch (PageException pe) {
							// SystemOut.printDate(pe);
							try {
								Util.engine().getCreationUtil().createQuery(conn, createSQL(conn), -1, -1, -1, "query");
								isInit = true;
							}
							catch (Exception e2) {
								// SystemOut.printDate(e2);
								throw pe;
							}
						}
						finally {
							relConnection(conn);
						}
					}
					catch (PageException pe) {
						Util.logGlobal(config, "log-loading", pe);
						isInit = false;
					}
				}
			}
		}
	}

	private SQL createSQL(DatasourceConnection dc) throws PageException {
		String product = product(dc);
		StringBuilder sb = new StringBuilder("CREATE TABLE ");
		if (is(product, "microsoft", "sqlserver")) sb.append("dbo.");
		sb.append(tableName).append(" ( ");

		if (is(product, "microsoft", "sqlserver")) sb.append("pid INT PRIMARY KEY IDENTITY (1, 1), ");
		else if (is(product, "mysql")) sb.append("pid INT AUTO_INCREMENT PRIMARY KEY, ");
		else if (is(product, "hsql")) sb.append("pid INTEGER IDENTITY PRIMARY KEY, ");
		else if (is(product, "postgresql")) sb.append("id SERIAL PRIMARY KEY, ");

		sb.append("id varchar(32) NOT NULL, ");
		sb.append("name varchar(128) NOT NULL, ");
		sb.append("severity varchar(16) NOT NULL, ");
		sb.append("threadid varchar(64) NOT NULL, ");
		sb.append("time datetime NOT NULL, ");
		sb.append("application varchar(64) NOT NULL, ");
		sb.append("message varchar(512) NOT NULL, ");
		sb.append("exception varchar(2048) NOT NULL, ");
		sb.append("custom varchar(2048) NOT NULL ");
		sb.append(");");
		return Util.engine().getDBUtil().toSQL(sb.toString(), new SQLItem[0]);

	}

	private static String product(DatasourceConnection dc) throws PageException {
		try {
			return (dc.getConnection().getMetaData().getDatabaseProductName() + " " + dc.getConnection().getMetaData().getDriverName()).toLowerCase();
		}
		catch (SQLException e) {
			throw Util.toPageException(e);
		}
	}

	private static boolean is(String product, String... names) {
		for (String name: names) {
			if (product.indexOf(name) != -1) return true;
		}
		return false;
	}

	private DatasourceConnection getConnection() throws PageException {
		if (datasource == null) datasource = config.getDataSource(datasourceName);
		return Util.engine().getDBUtil().getDatasourceConnection(config, datasource, username, password);
	}

	protected void relConnection(DatasourceConnection conn) throws PageException {
		if (conn != null) Util.engine().getDBUtil().releaseDatasourceConnection(config, conn);
	}
}
