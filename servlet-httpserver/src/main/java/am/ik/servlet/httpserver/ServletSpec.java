package am.ik.servlet.httpserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import am.ik.servlet.httpserver.ServletHttpServer.ServletOptions;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;

/**
 * A servlet's registration as a builder was given it: its URL patterns and the settings
 * its lambda gave, fixed once the lambda returned. A setting is a function of the
 * registration, so what an option sets is reached only by a program that gives it (a
 * multipart configuration brings the reading of parts).
 */
final class ServletSpec implements ServletOptions {

	private final String name;

	private final List<String> urlPatterns = new ArrayList<>();

	private final List<Consumer<ServletRegistration.Dynamic>> settings = new ArrayList<>();

	private boolean given;

	private ServletSpec(String name) {
		this.name = name;
	}

	static ServletSpec of(String name, Consumer<ServletOptions> options) {
		ServletSpec spec = new ServletSpec(Registrations.named(name));
		options.accept(spec);
		spec.given = true;
		return spec;
	}

	private ServletOptions set(Consumer<ServletRegistration.Dynamic> setting) {
		Registrations.open(this.given);
		this.settings.add(setting);
		return this;
	}

	@Override
	public ServletOptions urlPatterns(String... urlPatterns) {
		Registrations.open(this.given);
		for (String pattern : urlPatterns) {
			this.urlPatterns.add(Objects.requireNonNull(pattern, "urlPatterns"));
		}
		return this;
	}

	@Override
	public ServletOptions loadOnStartup(int order) {
		return set(registration -> registration.setLoadOnStartup(order));
	}

	@Override
	public ServletOptions asyncSupported() {
		return set(registration -> registration.setAsyncSupported(true));
	}

	@Override
	public ServletOptions initParameter(String name, String value) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(value, "value");
		return set(registration -> Registrations.initParameter(registration, name, value));
	}

	@Override
	public ServletOptions multipartConfig(MultipartConfigElement config) {
		Objects.requireNonNull(config, "config");
		return set(registration -> registration.setMultipartConfig(config));
	}

	/** Registers the servlet in a context that is starting. */
	void register(WebContext context, Servlet servlet) throws ServletException {
		ServletRegistration.Dynamic registration = context.addServlet(this.name, servlet);
		if (registration == null) {
			throw new ServletException("a servlet is named " + this.name + " already");
		}
		if (!this.urlPatterns.isEmpty()) {
			Set<String> taken = registration.addMapping(this.urlPatterns.toArray(new String[0]));
			if (!taken.isEmpty()) {
				throw new ServletException("the servlet " + this.name + " maps what another servlet has: " + taken);
			}
		}
		for (Consumer<ServletRegistration.Dynamic> setting : this.settings) {
			setting.accept(registration);
		}
	}

}
