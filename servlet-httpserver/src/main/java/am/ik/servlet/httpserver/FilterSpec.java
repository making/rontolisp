package am.ik.servlet.httpserver;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import am.ik.servlet.httpserver.ServletHttpServer.FilterOptions;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletException;
import org.jspecify.annotations.Nullable;

/**
 * A filter's registration as a builder was given it: its mappings and the settings its
 * lambda gave, fixed once the lambda returned. Its mappings go ahead of those the
 * application declares.
 */
final class FilterSpec implements FilterOptions {

	private final String name;

	private final List<String> urlPatterns = new ArrayList<>();

	private final List<String> servletNames = new ArrayList<>();

	private @Nullable EnumSet<DispatcherType> dispatcherTypes;

	private final List<Consumer<FilterRegistration.Dynamic>> settings = new ArrayList<>();

	private boolean given;

	private FilterSpec(String name) {
		this.name = name;
	}

	static FilterSpec of(String name, Consumer<FilterOptions> options) {
		FilterSpec spec = new FilterSpec(Registrations.named(name));
		options.accept(spec);
		spec.given = true;
		return spec;
	}

	private FilterOptions add(List<String> to, String[] values) {
		Registrations.open(this.given);
		for (String value : values) {
			to.add(Objects.requireNonNull(value));
		}
		return this;
	}

	private FilterOptions set(Consumer<FilterRegistration.Dynamic> setting) {
		Registrations.open(this.given);
		this.settings.add(setting);
		return this;
	}

	@Override
	public FilterOptions urlPatterns(String... urlPatterns) {
		return add(this.urlPatterns, urlPatterns);
	}

	@Override
	public FilterOptions servletNames(String... servletNames) {
		return add(this.servletNames, servletNames);
	}

	@Override
	public FilterOptions dispatcherTypes(DispatcherType... dispatcherTypes) {
		Registrations.open(this.given);
		EnumSet<DispatcherType> types = EnumSet.noneOf(DispatcherType.class);
		for (DispatcherType type : dispatcherTypes) {
			types.add(Objects.requireNonNull(type, "dispatcherTypes"));
		}
		if (types.isEmpty()) {
			throw new IllegalArgumentException("a filter takes part in one kind of dispatch at least");
		}
		this.dispatcherTypes = types;
		return this;
	}

	@Override
	public FilterOptions asyncSupported() {
		return set(registration -> registration.setAsyncSupported(true));
	}

	@Override
	public FilterOptions initParameter(String name, String value) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(value, "value");
		return set(registration -> Registrations.initParameter(registration, name, value));
	}

	/** Registers the filter in a context that is starting. */
	void register(WebContext context, Filter filter) throws ServletException {
		FilterRegistration.Dynamic registration = context.addFilter(this.name, filter);
		if (registration == null) {
			throw new ServletException("a filter is named " + this.name + " already");
		}
		// not after: ahead of the mappings the application declares, in the builder's
		// order
		if (!this.urlPatterns.isEmpty()) {
			registration.addMappingForUrlPatterns(this.dispatcherTypes, false, this.urlPatterns.toArray(new String[0]));
		}
		if (!this.servletNames.isEmpty()) {
			registration.addMappingForServletNames(this.dispatcherTypes, false,
					this.servletNames.toArray(new String[0]));
		}
		for (Consumer<FilterRegistration.Dynamic> setting : this.settings) {
			setting.accept(registration);
		}
	}

}
