package am.ik.servlet.httpserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.jspecify.annotations.Nullable;

/**
 * A session as a request, or an accessor, holds it: its data as the store answered it.
 * Every change is handed back to the store.
 */
final class Session implements HttpSession {

	private final Sessions sessions;

	final SessionData data;

	Session(Sessions sessions, SessionData data) {
		this.sessions = sessions;
		this.data = data;
	}

	boolean valid() {
		return this.data.state == 0;
	}

	private void check() {
		if (this.data.state == SessionData.INVALID) {
			throw new IllegalStateException("the session has been invalidated");
		}
	}

	@Override
	public long getCreationTime() {
		check();
		return this.data.getCreationTime();
	}

	@Override
	public String getId() {
		return this.data.getId();
	}

	@Override
	public long getLastAccessedTime() {
		check();
		return this.data.getLastAccessedTime();
	}

	@Override
	public ServletContext getServletContext() {
		return this.sessions.context();
	}

	@Override
	public void setMaxInactiveInterval(int interval) {
		this.data.setMaxInactiveInterval(interval);
		this.sessions.save(this.data);
	}

	@Override
	public int getMaxInactiveInterval() {
		return this.data.getMaxInactiveInterval();
	}

	@Override
	public @Nullable Object getAttribute(String name) {
		check();
		return name == null ? null : this.data.getAttributes().get(name);
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		check();
		return Collections.enumeration(new ArrayList<>(this.data.getAttributes().keySet()));
	}

	@Override
	public void setAttribute(String name, Object value) {
		check();
		if (name == null) {
			throw new IllegalArgumentException("an attribute has a name");
		}
		if (value == null) {
			removeAttribute(name);
			return;
		}
		Object old = this.data.getAttributes().put(name, value);
		try {
			this.sessions.save(this.data);
		}
		catch (RuntimeException ex) {
			// The store did not take it (a value it cannot keep): the session stays as it
			// was.
			if (old == null) {
				this.data.getAttributes().remove(name);
			}
			else {
				this.data.getAttributes().put(name, old);
			}
			throw ex;
		}
		if (value != old && value instanceof HttpSessionBindingListener bound) {
			bound.valueBound(new HttpSessionBindingEvent(this, name, value));
		}
		if (old != value && old instanceof HttpSessionBindingListener unbound) {
			unbound.valueUnbound(new HttpSessionBindingEvent(this, name, old));
		}
		List<HttpSessionAttributeListener> listeners = this.sessions.context().sessionAttributeListeners;
		if (!listeners.isEmpty()) {
			HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, old == null ? value : old);
			for (int i = 0; i < listeners.size(); i++) {
				if (old == null) {
					listeners.get(i).attributeAdded(event);
				}
				else {
					listeners.get(i).attributeReplaced(event);
				}
			}
		}
	}

	@Override
	public void removeAttribute(String name) {
		check();
		unbind(name);
	}

	/** Removes an attribute and tells its value and the listeners, valid or not. */
	void unbind(String name) {
		Object old = name == null ? null : this.data.getAttributes().remove(name);
		if (old == null) {
			return;
		}
		this.sessions.save(this.data);
		HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, old);
		if (old instanceof HttpSessionBindingListener unbound) {
			unbound.valueUnbound(event);
		}
		List<HttpSessionAttributeListener> listeners = this.sessions.context().sessionAttributeListeners;
		for (int i = 0; i < listeners.size(); i++) {
			listeners.get(i).attributeRemoved(event);
		}
	}

	@Override
	public void invalidate() {
		this.sessions.invalidate(this.data);
	}

	@Override
	public boolean isNew() {
		check();
		return this.data.isFresh();
	}

	@Override
	public Accessor getAccessor() {
		check();
		Sessions sessions = this.sessions;
		String id = this.data.getId();
		return new Accessor() {
			@Override
			public void access(Consumer<HttpSession> sessionConsumer) {
				SessionData current = sessions.access(id, false);
				if (current == null) {
					throw new IllegalStateException("the session " + id + " is invalid or has another id");
				}
				sessionConsumer.accept(new Session(sessions, current));
			}
		};
	}

}
