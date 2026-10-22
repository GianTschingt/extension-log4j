package org.lucee.extension.log4j.appender;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lucee.commons.io.res.Resource;
import lucee.commons.io.retirement.RetireListener;

/**
 * An output stream to a resource that closes the connection to the resource when it was not used for
 * a given time and opens it again with the next write, so a log file is not locked while nothing is
 * logged.
 */
public final class IdleOutputStream extends OutputStream {

	private static final Set<IdleOutputStream> open = ConcurrentHashMap.newKeySet();
	private static ScheduledExecutorService cleaner;

	private final Resource res;
	private boolean append;
	private final long idleMillis;
	private final RetireListener listener;
	private final Object sync = new Object();
	private OutputStream os;
	private long lastAccess;

	public IdleOutputStream(Resource res, boolean append, int idleSeconds, RetireListener listener) {
		this.res = res;
		this.append = append;
		this.idleMillis = idleSeconds > 0 ? idleSeconds * 1000L : 0;
		this.listener = listener;
	}

	private static synchronized void start() {
		if (cleaner != null) return;
		cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Log4jIdleOutputStreamCleaner");
			t.setDaemon(true);
			return t;
		});
		cleaner.scheduleWithFixedDelay(() -> {
			for (IdleOutputStream s: open) {
				try {
					s.retire();
				}
				catch (IOException e) {
				}
			}
		}, 1, 1, TimeUnit.SECONDS);
	}

	private OutputStream stream() throws IOException {
		if (os == null) {
			os = res.getOutputStream(append);
			if (os == null) throw new IOException("could not open a connection to [" + res + "]");
			open.add(this);
			start();
		}
		lastAccess = System.currentTimeMillis();
		return os;
	}

	private void retire() throws IOException {
		synchronized (sync) {
			if (os == null || lastAccess + idleMillis > System.currentTimeMillis()) return;
			append = true;
			close();
		}
	}

	private void retireNow() throws IOException {
		synchronized (sync) {
			if (os == null) return;
			append = true;
			close();
		}
	}

	@Override
	public void close() throws IOException {
		synchronized (sync) {
			if (os != null) {
				if (listener != null) listener.retire(this);
				try {
					os.close();
				}
				finally {
					open.remove(this);
					os = null;
				}
			}
		}
	}

	@Override
	public void flush() throws IOException {
		synchronized (sync) {
			if (os != null) {
				os.flush();
				if (idleMillis == 0) retireNow();
			}
		}
	}

	@Override
	public void write(int b) throws IOException {
		synchronized (sync) {
			stream().write(b);
			if (idleMillis == 0) retireNow();
		}
	}

	@Override
	public void write(byte[] b) throws IOException {
		synchronized (sync) {
			stream().write(b);
			if (idleMillis == 0) retireNow();
		}
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		synchronized (sync) {
			stream().write(b, off, len);
			if (idleMillis == 0) retireNow();
		}
	}
}
