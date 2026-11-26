package am.ik.servlet.httpserver;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;

/**
 * The deadlines of the asynchronous cycles in hand, and the one thread that waits for the
 * soonest: a request in hand holds no thread of its own (Servlet 6.1, 2.3.3.3, the
 * timeout of {@code AsyncContext}). The cycles are a heap ordered by their deadlines,
 * each knowing its place, so arming and disarming one is a matter of its path in the
 * heap; a cycle whose deadline came is told ({@link Async#timeUp}), which hands its
 * timeout to a thread of the executor. The thread is made by the first deadline and ends
 * with the server.
 */
final class Timeouts implements Runnable {

	private final ReentrantLock lock = new ReentrantLock();

	/** Signalled when the soonest deadline changed, or the server stopped. */
	private final Condition changed = this.lock.newCondition();

	private @Nullable Async[] heap = new Async[16];

	private int size;

	private boolean started;

	private boolean stopped;

	/**
	 * Holds a cycle until its deadline, in {@code System.nanoTime()}'s count, or moves it
	 * to a new one.
	 */
	void arm(Async async, long deadline) {
		this.lock.lock();
		try {
			if (this.stopped) {
				return;
			}
			if (async.at < 0) {
				if (this.size == this.heap.length) {
					this.heap = Arrays.copyOf(this.heap, this.size * 2);
				}
				async.at = this.size;
				this.heap[this.size++] = async;
			}
			async.armed = deadline;
			down(up(async.at));
			this.changed.signal();
			if (!this.started) {
				this.started = true;
				Thread waiting = new Thread(this, "servlet-httpserver timeouts");
				waiting.setDaemon(true);
				waiting.start();
			}
		}
		finally {
			this.lock.unlock();
		}
	}

	/** Lets a cycle go: it ended. */
	void disarm(Async async) {
		this.lock.lock();
		try {
			if (async.at >= 0) {
				boolean soonest = async.at == 0;
				remove(async.at);
				if (soonest) {
					this.changed.signal();
				}
			}
		}
		finally {
			this.lock.unlock();
		}
	}

	/** The server stopped: the thread ends, and no cycle is told any more. */
	void stop() {
		this.lock.lock();
		try {
			this.stopped = true;
			this.changed.signal();
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void run() {
		while (true) {
			Async due = null;
			this.lock.lock();
			try {
				if (this.stopped) {
					return;
				}
				if (this.size == 0) {
					this.changed.await();
				}
				else {
					long left = cycle(0).armed - System.nanoTime();
					if (left <= 0) {
						due = cycle(0);
						remove(0);
					}
					else {
						this.changed.awaitNanos(left);
					}
				}
			}
			catch (InterruptedException ex) {
				// looked at again
			}
			finally {
				this.lock.unlock();
			}
			if (due != null) {
				due.timeUp();
			}
		}
	}

	// ---- the heap, under the lock

	private Async cycle(int at) {
		return Objects.requireNonNull(this.heap[at]);
	}

	private void remove(int at) {
		Async removed = cycle(at);
		removed.at = -1;
		Async last = cycle(--this.size);
		this.heap[this.size] = null;
		if (at < this.size) {
			place(last, at);
			down(up(at));
		}
	}

	/** Moves the cycle at a place towards the root while it is due sooner: its place. */
	private int up(int at) {
		int now = at;
		Async moving = cycle(now);
		while (now > 0) {
			int parent = (now - 1) / 2;
			if (cycle(parent).armed - moving.armed <= 0) {
				break;
			}
			place(cycle(parent), now);
			now = parent;
		}
		place(moving, now);
		return now;
	}

	/** Moves the cycle at a place away from the root while one under it is due sooner. */
	private void down(int at) {
		int now = at;
		Async moving = cycle(now);
		while (true) {
			int child = 2 * now + 1;
			if (child >= this.size) {
				break;
			}
			if (child + 1 < this.size && cycle(child + 1).armed - cycle(child).armed < 0) {
				child++;
			}
			if (moving.armed - cycle(child).armed <= 0) {
				break;
			}
			place(cycle(child), now);
			now = child;
		}
		place(moving, now);
	}

	private void place(Async async, int at) {
		this.heap[at] = async;
		async.at = at;
	}

}
