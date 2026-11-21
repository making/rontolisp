package am.ik.servlet.httpserver;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.Registration;

/**
 * What a servlet's and a filter's registration share: the name, the class name, the
 * initialization parameters, all fixed once the context is initialized.
 */
abstract class Registered implements Registration.Dynamic {

	final WebContext context;

	private final String name;

	private final String className;

	private final Map<String, String> initParameters = new LinkedHashMap<>();

	boolean asyncSupported;

	Registered(WebContext context, String name, String className) {
		this.context = context;
		this.name = name;
		this.className = className;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public String getClassName() {
		return this.className;
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
	public String getInitParameter(String name) {
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
