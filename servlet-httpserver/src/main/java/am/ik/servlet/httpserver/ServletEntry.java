package am.ik.servlet.httpserver;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.UnavailableException;

/**
 * A registered servlet: its registration while the context is open, its configuration
 * once it serves. It is initialized at the start when loaded on startup, else by its
 * first request.
 */
final class ServletEntry extends Registered implements ServletRegistration.Dynamic, ServletConfig {

	final Servlet instance;

	private final Set<String> mappings = new LinkedHashSet<>();

	private int loadOnStartup = -1;

	private String runAsRole;

	private volatile boolean initialized;

	private boolean unavailable;

	ServletEntry(WebContext context, String name, Servlet servlet) {
		super(context, name, servlet.getClass().getName());
		this.instance = servlet;
	}

	/**
	 * The servlet, initialized by the first caller.
	 * @return the servlet in service
	 * @throws ServletException if the servlet refuses to initialize (the next caller asks
	 * it again), or was taken out of service
	 */
	Servlet servlet() throws ServletException {
		if (!this.initialized) {
			synchronized (this) {
				if (this.unavailable) {
					throw new UnavailableException("the servlet " + getName() + " is out of service");
				}
				if (!this.initialized) {
					try {
						this.instance.init(this);
					}
					catch (UnavailableException ex) {
						this.unavailable = ex.isPermanent();
						throw ex;
					}
					this.initialized = true;
				}
			}
		}
		return this.instance;
	}

	/**
	 * Takes the servlet out of service for good (Servlet 6.1, 2.3.3.2): it is destroyed
	 * and every later request of it is refused.
	 */
	synchronized void unavailable() {
		if (!this.unavailable) {
			this.unavailable = true;
			destroy();
		}
	}

	int loadOnStartup() {
		return this.loadOnStartup;
	}

	synchronized void destroy() {
		if (this.initialized) {
			this.initialized = false;
			this.instance.destroy();
		}
	}

	@Override
	public Set<String> addMapping(String... urlPatterns) {
		if (urlPatterns == null || urlPatterns.length == 0) {
			throw new IllegalArgumentException("a mapping needs a URL pattern");
		}
		this.context.open();
		Set<String> conflicts = this.context.mapServlet(this, urlPatterns);
		if (conflicts.isEmpty()) {
			Collections.addAll(this.mappings, urlPatterns);
		}
		return conflicts;
	}

	@Override
	public Collection<String> getMappings() {
		return Collections.unmodifiableSet(this.mappings);
	}

	@Override
	public String getRunAsRole() {
		return this.runAsRole;
	}

	@Override
	public void setLoadOnStartup(int loadOnStartup) {
		this.context.open();
		this.loadOnStartup = loadOnStartup;
	}

	@Override
	public Set<String> setServletSecurity(ServletSecurityElement constraint) {
		if (constraint == null) {
			throw new IllegalArgumentException("a security constraint is not null");
		}
		this.context.open();
		throw new UnsupportedOperationException("security constraints are not supported");
	}

	@Override
	public void setMultipartConfig(MultipartConfigElement multipartConfig) {
		if (multipartConfig == null) {
			throw new IllegalArgumentException("a multipart configuration is not null");
		}
		this.context.open();
		throw new UnsupportedOperationException("multipart requests are not supported");
	}

	@Override
	public void setRunAsRole(String roleName) {
		if (roleName == null) {
			throw new IllegalArgumentException("a role name is not null");
		}
		this.context.open();
		this.runAsRole = roleName;
	}

	@Override
	public String getServletName() {
		return getName();
	}

	@Override
	public ServletContext getServletContext() {
		return this.context;
	}

}
