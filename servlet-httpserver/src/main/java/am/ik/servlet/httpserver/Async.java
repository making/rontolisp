package am.ik.servlet.httpserver;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

/**
 * The asynchronous cycles of a request (Servlet 6.1, 2.3.3.3).
 *
 * <p>
 * No thread waits for a request in hand. Once the dispatch that started a cycle returned,
 * the request is in no thread's hand, as a JDK {@code HttpExchange} answered after its
 * handler returned is: what the container owes the cycle -- the {@code ASYNC} dispatch,
 * the listeners, the answer to a timeout or an error, the end of the response -- runs on
 * a thread of the executor, which {@code complete()}, a dispatch, a listener of reads or
 * writes set from another thread and the timeout (which the context's {@link Timeouts}
 * tell) hand it to -- a dispatch is performed on a container managed thread (the javadoc)
 * --, or where it is asked: an error another thread reports, the stop. One thread at a
 * time runs what is asked, and what is asked during a dispatch of the container takes
 * effect once the dispatch returned, as the specification asks.
 *
 * <p>
 * The listeners of non-blocking reads and writes are called once each: the exchange's
 * streams block, so {@code isReady()} is always true.
 *
 * <p>
 * The state is guarded by a lock, not by the object's monitor: on a JDK before 24 a
 * virtual thread waiting on a monitor holds its carrier.
 */
final class Async implements AsyncContext {

	// What the cycle was asked to end with, and what runs next. Not enums: an enum is
	// some 3.5 KB of every compiled program that reaches this class.

	/** Nothing has ended the cycle yet. */
	private static final int OPEN = 0;

	/** {@link #complete()} was called. */
	private static final int COMPLETE = 1;

	/** A dispatch was called and is not run yet. */
	private static final int DISPATCH = 2;

	/** The dispatch was run: the cycle is over, unless its target started another. */
	private static final int DISPATCHED = 3;

	/**
	 * What runs besides what is asked: nothing yet, the timeout, an error another thread
	 * reported, the listeners of reads and writes, what a step threw.
	 */
	private static final int NONE = 4;

	private static final int TIMEOUT = 5;

	private static final int FAILURE = 6;

	private static final int READ = 7;

	private static final int WRITE = 8;

	/** What a step of the container threw is answered. */
	private static final int ERROR = 9;

	private final ReentrantLock lock = new ReentrantLock();

	private final WebContext context;

	private final Request base;

	private final Response out;

	private ServletRequest request;

	private ServletResponse response;

	private boolean original;

	/**
	 * Where {@code dispatch()} goes for a cycle started with
	 * {@code startAsync(request, response)}: the URI and the query string the request
	 * answered then; else null, for where the container dispatched last.
	 */
	private @Nullable String uri;

	private @Nullable String query;

	private final List<Listening> listeners = new ArrayList<>();

	private long timeout = 30_000;

	/** When the cycle times out, in {@code System.nanoTime()}'s count; 0 for never. */
	private long deadline;

	private int asked = OPEN;

	private @Nullable Target target;

	/** An error another thread reported: a task of {@link #start}, or the stop. */
	private @Nullable Throwable failure;

	/** The dispatch of the container that started the cycle has not returned yet. */
	private boolean starting;

	private @Nullable ReadListener reader;

	private boolean readerCalled;

	private @Nullable WriteListener writer;

	private boolean writerCalled;

	private boolean ended;

	/** A thread runs what is asked of the cycle. */
	private boolean running;

	/**
	 * What a step of the container threw -- the dispatch that started the cycle, an
	 * {@code ASYNC} dispatch, a listener of reads or writes --: the error answered next.
	 */
	private @Nullable Throwable thrown;

	/** What ends the request once its cycles completed it; set once the first began. */
	private @Nullable Ending ending;

	/** The deadline the context's timeouts hold of this cycle, 0 for none. */
	long armed;

	/** Where the context's timeouts hold it, -1 for nowhere. */
	int at = -1;

	Async(WebContext context, Request base, Response out) {
		this.context = context;
		this.base = base;
		this.out = out;
		// the first cycle's are set by its start
		this.request = base;
		this.response = out;
	}

	/** A listener and what it was added with. */
	private record Listening(AsyncListener listener, @Nullable ServletRequest request,
			@Nullable ServletResponse response) {
	}

	/**
	 * Where a dispatch goes: the servlet, the path inside the context, the URI, the
	 * query.
	 */
	record Target(@Nullable Match match, @Nullable String path, String requestUri, @Nullable String query) {
	}

	/** The end of a request once its cycles completed it (WebContext). */
	@FunctionalInterface
	interface Ending {

		/**
		 * Ends the request.
		 * @param handler whether the calling thread is the one the server called the
		 * handler on, which alone can cut the exchange
		 */
		void end(boolean handler) throws IOException;

	}

	// ---- the request's side

	/**
	 * Starts a cycle, from inside a dispatch of the container: the listeners of the cycle
	 * before are told, and forgotten.
	 * @param uri the URI {@code dispatch()} goes to, or null for the one the container
	 * dispatched last
	 */
	void start(ServletRequest request, ServletResponse response, boolean original, @Nullable String uri,
			@Nullable String query) {
		List<Listening> before;
		this.lock.lock();
		try {
			before = new ArrayList<>(this.listeners);
			this.listeners.clear();
			this.request = request;
			this.response = response;
			this.original = original;
			this.uri = uri;
			this.query = query;
			this.asked = OPEN;
			this.target = null;
			this.starting = true;
		}
		finally {
			this.lock.unlock();
		}
		for (Listening listening : before) {
			try {
				listening.listener().onStartAsync(event(listening, null));
			}
			catch (Throwable ex) {
				this.context.log("an asynchronous listener failed", ex);
			}
		}
	}

	/** Whether startAsync was called in the dispatch of the container now running. */
	boolean starting() {
		this.lock.lock();
		try {
			return this.starting;
		}
		finally {
			this.lock.unlock();
		}
	}

	/** Whether the request is asynchronous: a cycle is open, or its start is in hand. */
	boolean started() {
		this.lock.lock();
		try {
			return !this.ended && (this.asked == OPEN || this.starting);
		}
		finally {
			this.lock.unlock();
		}
	}

	/** The request and the response of the cycle, as it was started. */
	ServletRequest request() {
		this.lock.lock();
		try {
			return this.request;
		}
		finally {
			this.lock.unlock();
		}
	}

	ServletResponse response() {
		this.lock.lock();
		try {
			return this.response;
		}
		finally {
			this.lock.unlock();
		}
	}

	// ---- AsyncContext

	@Override
	public ServletRequest getRequest() {
		this.lock.lock();
		try {
			if (this.asked != OPEN || this.ended) {
				throw new IllegalStateException("complete() or a dispatch has been called in this asynchronous cycle");
			}
			return this.request;
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public ServletResponse getResponse() {
		this.lock.lock();
		try {
			if (this.asked != OPEN || this.ended) {
				throw new IllegalStateException("complete() or a dispatch has been called in this asynchronous cycle");
			}
			return this.response;
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public boolean hasOriginalRequestAndResponse() {
		this.lock.lock();
		try {
			return this.original;
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void dispatch() {
		Target to;
		this.lock.lock();
		try {
			open();
			// null: where the container dispatched last, once its dispatch returned
			to = this.uri != null ? at(this.uri, this.query) : null;
		}
		finally {
			this.lock.unlock();
		}
		ask(to);
	}

	@Override
	public void dispatch(String path) {
		dispatch(this.context, path);
	}

	@Override
	public void dispatch(ServletContext context, String path) {
		if (context != this.context) {
			throw new IllegalArgumentException("not the context of the request: a server has one");
		}
		if (path == null || !path.startsWith("/")) {
			throw new IllegalArgumentException("a path of the context starts with a slash: " + path);
		}
		int question = path.indexOf('?');
		String canonical = UrlCodec.canonicalPath(question < 0 ? path : path.substring(0, question));
		if (canonical == null) {
			throw new IllegalArgumentException("not a path of the context: " + path);
		}
		ask(new Target(this.context.match(canonical), canonical, this.context.getContextPath() + canonical,
				question < 0 ? null : path.substring(question + 1)));
	}

	/** The target of a URI a request of the application answered. */
	private Target at(String uri, @Nullable String query) {
		String contextPath = this.context.getContextPath();
		String inside = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
		String canonical = UrlCodec.canonicalPath(inside.isEmpty() ? "/" : inside);
		return new Target(canonical == null ? null : this.context.match(canonical), canonical, uri, query);
	}

	private void ask(@Nullable Target to) {
		this.lock.lock();
		try {
			open();
			this.asked = DISPATCH;
			this.target = to;
		}
		finally {
			this.lock.unlock();
		}
		// on a container managed thread (the javadoc)
		kick(false);
	}

	/** Refuses what ends a cycle once something has ended it. */
	private void open() {
		if (this.ended || this.asked == COMPLETE) {
			throw new IllegalStateException("complete() has been called");
		}
		if (this.asked != OPEN) {
			throw new IllegalStateException("a dispatch has been called in this asynchronous cycle");
		}
	}

	@Override
	public void complete() {
		this.lock.lock();
		try {
			open();
			this.asked = COMPLETE;
		}
		finally {
			this.lock.unlock();
		}
		kick(false);
	}

	@Override
	public void start(Runnable run) {
		if (run == null) {
			throw new NullPointerException("run");
		}
		this.context.execute(() -> {
			try {
				run.run();
			}
			catch (Throwable ex) {
				this.context.log("an asynchronous task failed", ex);
				fail(ex);
			}
		});
	}

	/**
	 * Reports an error of another thread: what a task of {@link #start} threw ends an
	 * open cycle as an error does; the stop ends every request in hand so, on the thread
	 * that stops.
	 * @param always whether the cycle need not be open
	 */
	void fail(Throwable ex, boolean always) {
		this.lock.lock();
		try {
			if (this.ended || this.failure != null || !(always || this.asked == OPEN)) {
				return;
			}
			this.failure = ex;
		}
		finally {
			this.lock.unlock();
		}
		kick(true);
	}

	private void fail(Throwable ex) {
		fail(ex, false);
	}

	@Override
	public void addListener(AsyncListener listener) {
		add(new Listening(listener, null, null));
	}

	@Override
	public void addListener(AsyncListener listener, ServletRequest servletRequest, ServletResponse servletResponse) {
		add(new Listening(listener, servletRequest, servletResponse));
	}

	private void add(Listening listening) {
		this.lock.lock();
		try {
			if (listening.listener() == null) {
				throw new NullPointerException("listener");
			}
			if (!this.starting) {
				throw new IllegalStateException(
						"a listener is added in the dispatch that started the asynchronous cycle");
			}
			this.listeners.add(listening);
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
		return Instances.create(clazz);
	}

	@Override
	public void setTimeout(long timeout) {
		this.lock.lock();
		try {
			if (!this.starting) {
				throw new IllegalStateException(
						"the timeout is set in the dispatch that started the asynchronous cycle");
			}
			this.timeout = timeout;
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public long getTimeout() {
		this.lock.lock();
		try {
			return this.timeout;
		}
		finally {
			this.lock.unlock();
		}
	}

	// ---- the non-blocking streams

	void reader(ReadListener listener) {
		this.lock.lock();
		try {
			if (this.reader != null) {
				throw new IllegalStateException("a read listener has been set for this request");
			}
			this.reader = listener;
		}
		finally {
			this.lock.unlock();
		}
		kick(false);
	}

	void writer(WriteListener listener) {
		this.lock.lock();
		try {
			if (this.writer != null) {
				throw new IllegalStateException("a write listener has been set for this request");
			}
			this.writer = listener;
		}
		finally {
			this.lock.unlock();
		}
		kick(false);
	}

	// ---- what the container owes the cycle

	/**
	 * The dispatch of the container that started the first cycle returned: the cycles
	 * take the request over.
	 * @param thrown what the dispatch threw, or null
	 * @param ending what ends the request once its cycles completed it
	 */
	void takeOver(@Nullable Throwable thrown, Ending ending) {
		this.lock.lock();
		try {
			this.ending = ending;
			this.thrown = thrown;
			this.running = true;
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * On the thread the server called the handler on, once the dispatch that started the
	 * first cycle returned: what was asked meanwhile runs on it, and then the request is
	 * in no thread's hand until something is asked of it.
	 * @throws IOException where the response failed once committed: the exchange is cut
	 */
	void begin() throws IOException {
		this.context.hold(this);
		steps(true);
	}

	/**
	 * Runs what is asked, unless a thread does or the dispatch of the container that
	 * started the cycle has not returned yet (which then runs it): on this thread, or on
	 * a thread of the executor.
	 */
	private void kick(boolean here) {
		this.lock.lock();
		try {
			if (this.running || this.starting || this.ending == null || this.ended) {
				return;
			}
			this.running = true;
		}
		finally {
			this.lock.unlock();
		}
		if (!here) {
			try {
				this.context.execute(this::stepsElsewhere);
				return;
			}
			catch (RuntimeException ex) {
				// an executor that takes no task, the server stopping: here then
			}
		}
		stepsElsewhere();
	}

	/** What the server owes a request where no thread of its handler is left. */
	void timeUp() {
		kick(false);
	}

	private void stepsElsewhere() {
		try {
			steps(false);
		}
		catch (IOException ex) {
			// not reached: only the thread of the handler cuts the exchange
		}
	}

	/**
	 * Runs what is asked of the cycles until nothing is: the thread that does holds the
	 * request meanwhile.
	 * @param handler whether this is the thread the server called the handler on
	 */
	private void steps(boolean handler) throws IOException {
		while (true) {
			int step;
			Throwable error;
			long deadline;
			this.lock.lock();
			try {
				returned();
				step = next();
				error = this.thrown;
				this.thrown = null;
				deadline = this.ended ? 0 : this.deadline;
				if (step == NONE) {
					this.running = false;
				}
			}
			finally {
				this.lock.unlock();
			}
			switch (step) {
				case NONE -> {
					if (deadline != 0) {
						this.context.arm(this, deadline);
					}
					return;
				}
				case ERROR -> error(Objects.requireNonNull(error));
				case COMPLETE -> {
					finish(handler);
					return;
				}
				case DISPATCH -> {
					Throwable failed = dispatch(taken());
					if (failed != null) {
						threw(failed);
					}
					else if (!starting()) {
						// the target started no cycle: the request is complete
						finish(handler);
						return;
					}
				}
				case TIMEOUT -> timedOut();
				case READ -> threw(read());
				case WRITE -> threw(write());
				default -> threw(failure());
			}
		}
	}

	/** What a step threw, answered next. */
	private void threw(@Nullable Throwable failed) {
		if (failed != null) {
			this.lock.lock();
			try {
				this.thrown = failed;
			}
			finally {
				this.lock.unlock();
			}
		}
	}

	/**
	 * The dispatch of the container that ran last returned: a cycle it started begins.
	 * Under the lock.
	 */
	private void returned() {
		if (this.starting) {
			this.starting = false;
			this.deadline = this.timeout > 0 ? Math.max(System.nanoTime() + this.timeout * 1_000_000L, 1) : 0;
		}
	}

	private @Nullable Target taken() {
		this.lock.lock();
		try {
			this.asked = DISPATCHED;
			return this.target;
		}
		finally {
			this.lock.unlock();
		}
	}

	private @Nullable Throwable failure() {
		this.lock.lock();
		try {
			Throwable taken = this.failure;
			this.failure = null;
			return taken;
		}
		finally {
			this.lock.unlock();
		}
	}

	/** What the request is to do next, or NONE. Under the lock. */
	private int next() {
		if (this.ended) {
			return NONE;
		}
		// what a step threw first, then a completion asked: an error that comes after it
		// is too late
		if (this.thrown != null) {
			return ERROR;
		}
		if (this.asked == COMPLETE) {
			return COMPLETE;
		}
		if (this.failure != null) {
			return FAILURE;
		}
		if (this.asked == DISPATCH) {
			return DISPATCH;
		}
		if (this.reader != null && !this.readerCalled) {
			this.readerCalled = true;
			return READ;
		}
		if (this.writer != null && !this.writerCalled) {
			this.writerCalled = true;
			return WRITE;
		}
		if (this.deadline != 0 && this.deadline - System.nanoTime() <= 0) {
			return TIMEOUT;
		}
		return NONE;
	}

	/** Runs a dispatch on this thread: its target takes the request over. */
	private @Nullable Throwable dispatch(@Nullable Target asked) {
		Request base = this.base;
		Target to = asked != null ? asked : base.lastDispatched();
		base.asyncAttributes();
		Match match = to.match();
		if (match == null) {
			this.out.fail(HttpServletResponse.SC_NOT_FOUND, null);
			return null;
		}
		base.dispatchAsync(to);
		base.dispatching(true);
		try {
			this.context.invoke(base, request(), response(), match, DispatcherType.ASYNC, to.path());
			return null;
		}
		catch (Throwable ex) {
			return ex;
		}
		finally {
			base.dispatching(false);
		}
	}

	/**
	 * What an error asks of the container: the listeners are told, and unless one of them
	 * completed or dispatched, the request is answered as one that failed and completed.
	 * What was asked before the error is dropped.
	 */
	private void error(Throwable thrown) {
		List<Listening> told;
		this.lock.lock();
		try {
			this.asked = OPEN;
			this.target = null;
			told = new ArrayList<>(this.listeners);
		}
		finally {
			this.lock.unlock();
		}
		for (Listening listening : told) {
			try {
				listening.listener().onError(event(listening, thrown));
			}
			catch (Throwable ex) {
				this.context.log("an asynchronous listener failed", ex);
			}
		}
		if (unanswered()) {
			this.context.fail(this.out, thrown);
		}
	}

	/** What a timeout asks: the listeners, then a 500 unless one of them answered. */
	private void timedOut() {
		List<Listening> told;
		this.lock.lock();
		try {
			this.deadline = 0;
			told = new ArrayList<>(this.listeners);
		}
		finally {
			this.lock.unlock();
		}
		for (Listening listening : told) {
			try {
				listening.listener().onTimeout(event(listening, null));
			}
			catch (Throwable ex) {
				this.context.log("an asynchronous listener failed", ex);
			}
		}
		if (unanswered()) {
			this.out.fail(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null);
		}
	}

	/** Whether no listener completed or dispatched: the container completes then. */
	private boolean unanswered() {
		this.lock.lock();
		try {
			if (this.asked != OPEN) {
				return false;
			}
			this.asked = COMPLETE;
			return true;
		}
		finally {
			this.lock.unlock();
		}
	}

	/** The read listener: the data while the body is not read, then its end. */
	private @Nullable Throwable read() {
		ReadListener listener;
		this.lock.lock();
		try {
			// what next() found set
			listener = Objects.requireNonNull(this.reader);
		}
		finally {
			this.lock.unlock();
		}
		RequestBody body = this.base.body();
		try {
			if (!body.isFinished()) {
				listener.onDataAvailable();
			}
			if (body.isFinished()) {
				listener.onAllDataRead();
			}
			return null;
		}
		catch (Throwable ex) {
			try {
				listener.onError(ex);
			}
			catch (Throwable again) {
				this.context.log("a read listener failed", again);
			}
			return ex;
		}
	}

	private @Nullable Throwable write() {
		WriteListener listener;
		this.lock.lock();
		try {
			// what next() found set
			listener = Objects.requireNonNull(this.writer);
		}
		finally {
			this.lock.unlock();
		}
		try {
			listener.onWritePossible();
			return null;
		}
		catch (Throwable ex) {
			try {
				listener.onError(ex);
			}
			catch (Throwable again) {
				this.context.log("a write listener failed", again);
			}
			return ex;
		}
	}

	/**
	 * Ends the response, then tells the listeners, then ends the request: its listeners,
	 * the exchange closed.
	 * @param handler whether this is the thread the server called the handler on
	 */
	private void finish(boolean handler) throws IOException {
		this.out.complete();
		List<Listening> told;
		Ending end;
		this.lock.lock();
		try {
			this.asked = COMPLETE;
			this.ended = true;
			told = new ArrayList<>(this.listeners);
			end = Objects.requireNonNull(this.ending);
		}
		finally {
			this.lock.unlock();
		}
		this.context.disarm(this);
		for (Listening listening : told) {
			try {
				listening.listener().onComplete(event(listening, null));
			}
			catch (Throwable ex) {
				this.context.log("an asynchronous listener failed", ex);
			}
		}
		this.context.release(this);
		end.end(handler);
	}

	/**
	 * The event of a listener: the request and the response it was added with, null for
	 * one added without them (the javadoc of {@link AsyncEvent#getSuppliedRequest()}).
	 */
	private AsyncEvent event(Listening listening, @Nullable Throwable thrown) {
		return new AsyncEvent(this, listening.request(), listening.response(), thrown);
	}

}
