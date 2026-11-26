package am.ik.servlet.httpserver;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.Registration;
import org.jspecify.annotations.Nullable;

/**
 * What a servlet's and a filter's registration share: the name, the class name, the
 * initialization parameters, all fixed once the context is initialized.
 */
abstract sealed class Registered implements Registration.Dynamic permits FilterEntry, ServletEntry {

	final WebContext context;

	private final String name;

	/** The servlet or the filter: its class's name is asked only where it is wanted. */
	private final Object instance;

	private final Map<String, String> initParameters = new LinkedHashMap<>();

	boolean asyncSupported;

	Registered(WebContext context, String name, Object instance) {
		this.context = context;
		this.name = name;
		this.instance = instance;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public String getClassName() {
		return this.instance.getClass().getName();
	}

	@Override
	public boolean setInitParameter(String name, String value) {
		if (name == null || value == null) {
			throw new IllegalArgumentException("an initialization parameter has a name and a value");
		}
		this.context.open();
		if (this.initParameters.containsKey(name)) {
			return false;
		}
		this.initParameters.put(name, value);
		return true;
	}

	@Override
	public @Nullable String getInitParameter(String name) {
		return this.initParameters.get(name);
	}

	@Override
	public Set<String> setInitParameters(Map<String, String> initParameters) {
		this.context.open();
		Set<String> conflicts = new LinkedHashSet<>();
		for (Map.Entry<String, String> parameter : initParameters.entrySet()) {
			if (parameter.getKey() == null || parameter.getValue() == null) {
				throw new IllegalArgumentException("an initialization parameter has a name and a value");
			}
			if (this.initParameters.containsKey(parameter.getKey())) {
				conflicts.add(parameter.getKey());
			}
		}
		if (conflicts.isEmpty()) {
			this.initParameters.putAll(initParameters);
		}
		return conflicts;
	}

	@Override
	public Map<String, String> getInitParameters() {
		return Collections.unmodifiableMap(this.initParameters);
	}

	/** The names of the parameters, as a configuration answers them. */
	public Enumeration<String> getInitParameterNames() {
		return Collections.enumeration(this.initParameters.keySet());
	}

	@Override
	public void setAsyncSupported(boolean isAsyncSupported) {
		this.context.open();
		this.asyncSupported = isAsyncSupported;
	}

}
