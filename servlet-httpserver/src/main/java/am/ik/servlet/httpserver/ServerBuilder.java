package am.ik.servlet.httpserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

import am.ik.servlet.httpserver.ServletHttpServer.Binding;
import am.ik.servlet.httpserver.ServletHttpServer.Builder;
import am.ik.servlet.httpserver.ServletHttpServer.Definition;
import am.ik.servlet.httpserver.ServletHttpServer.FilterHandler;
import am.ik.servlet.httpserver.ServletHttpServer.FilterOptions;
import am.ik.servlet.httpserver.ServletHttpServer.Handler;
import am.ik.servlet.httpserver.ServletHttpServer.Registration;
import am.ik.servlet.httpserver.ServletHttpServer.ServletOptions;
import am.ik.servlet.httpserver.ServletHttpServer.SessionOptions;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import org.jspecify.annotations.Nullable;

/**
 * The builder of a definition once it knows where the server listens: the registrations
 * in their order, each a part made of the context when a server starts. What a method
 * makes is reached only by a program that calls it.
 */
final class ServerBuilder implements Builder {

	private final InetSocketAddress address;

	private String contextPath = "";

	private final List<Registration> registrations = new ArrayList<>();

	private @Nullable Registration discovery;

	private @Nullable Executor executor;

	private @Nullable Binding secure;

	ServerBuilder(InetSocketAddress address) {
		this.address = address;
	}

	private Builder add(Registration registration) {
		this.registrations.add(registration);
		return this;
	}

	@Override
	public Builder contextPath(String contextPath) {
		String path = contextPath.equals("/") ? "" : contextPath;
		if (!path.isEmpty() && (!path.startsWith("/") || path.endsWith("/"))) {
			throw new IllegalArgumentException(
					"a context path starts with a slash and does not end with one: " + contextPath);
		}
		this.contextPath = path;
		return this;
	}

	@Override
	public Builder servlet(String name, Servlet servlet, Consumer<ServletOptions> options) {
		Objects.requireNonNull(servlet, "servlet");
		ServletSpec spec = ServletSpec.of(name, options);
		return add(context -> spec.register(context, servlet));
	}

	@Override
	public Builder servlet(String name, Class<? extends Servlet> servletClass, Consumer<ServletOptions> options) {
		Objects.requireNonNull(servletClass, "servletClass");
		ServletSpec spec = ServletSpec.of(name, options);
		return add(context -> spec.register(context, context.createServlet(servletClass)));
	}

	@Override
	public Builder servlet(String name, Handler handler, Consumer<ServletOptions> options) {
		Objects.requireNonNull(handler, "handler");
		ServletSpec spec = ServletSpec.of(name, options);
		return add(context -> spec.register(context, new HandlerServlet(handler)));
	}

	@Override
	public Builder filter(String name, Filter filter, Consumer<FilterOptions> options) {
		Objects.requireNonNull(filter, "filter");
		FilterSpec spec = FilterSpec.of(name, options);
		return add(context -> spec.register(context, filter));
	}

	@Override
	public Builder filter(String name, Class<? extends Filter> filterClass, Consumer<FilterOptions> options) {
		Objects.requireNonNull(filterClass, "filterClass");
		FilterSpec spec = FilterSpec.of(name, options);
		return add(context -> spec.register(context, context.createFilter(filterClass)));
	}

	@Override
	public Builder filter(String name, FilterHandler filter, Consumer<FilterOptions> options) {
		return filter(name, (Filter) filter, options);
	}

	@Override
	public Builder listener(EventListener listener) {
		Objects.requireNonNull(listener, "listener");
		return add(context -> context.addListener(listener));
	}

	@Override
	public Builder listener(Class<? extends EventListener> listenerClass) {
		Objects.requireNonNull(listenerClass, "listenerClass");
		return add(context -> context.addListener(context.createListener(listenerClass)));
	}

	@Override
	public Builder onStartup(Consumer<ServletContext> action) {
		Objects.requireNonNull(action, "action");
		return add(context -> context.addListener(new ServletContextListener() {
			@Override
			public void contextInitialized(ServletContextEvent event) {
				action.accept(event.getServletContext());
			}
		}));
	}

	@Override
	public Builder onShutdown(Consumer<ServletContext> action) {
		Objects.requireNonNull(action, "action");
		return add(context -> context.addListener(new ServletContextListener() {
			@Override
			public void contextDestroyed(ServletContextEvent event) {
				action.accept(event.getServletContext());
			}
		}));
	}

	@Override
	public Builder discover() {
		this.discovery = context -> Discovery.discover(context, context.getClassLoader());
		return this;
	}

	@Override
	public Builder documentRoot(Path directory) {
		Objects.requireNonNull(directory, "directory");
		return add(context -> context.documentRoot(directory));
	}

	@Override
	public Builder sessions(Consumer<SessionOptions> options) {
		SessionSpec spec = SessionSpec.of(options);
		return add(spec::register);
	}

	@Override
	public Builder errorPage(int status, String location) {
		if (status < 400 || status > 599) {
			throw new IllegalArgumentException("an error page answers a status of 400 to 599: " + status);
		}
		ErrorPages.check(location);
		return add(context -> context.errorPages().status(status, location));
	}

	@Override
	public Builder errorPage(Class<? extends Throwable> type, String location) {
		Objects.requireNonNull(type, "type");
		ErrorPages.check(location);
		return add(context -> context.errorPages().type(type, location));
	}

	@Override
	public Builder errorPage(String location) {
		ErrorPages.check(location);
		return add(context -> context.errorPages().otherwise(location));
	}

	@Override
	public Builder maxFormSize(int bytes) {
		return add(context -> context.maxFormSize(bytes));
	}

	@Override
	public Builder log(BiConsumer<String, @Nullable Throwable> log) {
		Objects.requireNonNull(log, "log");
		return add(context -> context.log(log));
	}

	@Override
	public Builder https(SSLContext sslContext) {
		Objects.requireNonNull(sslContext, "sslContext");
		this.secure = address -> {
			HttpsServer server = HttpsServer.create(address, Definition.BACKLOG);
			server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
			return server;
		};
		return this;
	}

	@Override
	public Builder executor(Executor executor) {
		this.executor = Objects.requireNonNull(executor, "executor");
		return this;
	}

	@Override
	public Definition build() {
		// a copy of the definition's own, which nothing changes
		return new Definition(this.address, this.contextPath, this.executor, new ArrayList<>(this.registrations),
				this.discovery, this.secure);
	}

	@Override
	public ServletHttpServer start() throws IOException, ServletException {
		return build().start();
	}

}
