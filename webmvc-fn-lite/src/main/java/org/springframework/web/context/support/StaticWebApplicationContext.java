/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.context.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.servlet.ServletContext;

import org.jspecify.annotations.Nullable;
import org.springframework.web.context.WebApplicationContext;

/**
 * A context holding the objects it is handed in code: each is made once, by its supplier,
 * when it is first asked for.
 */
public class StaticWebApplicationContext implements WebApplicationContext {

	private final Map<String, Supplier<?>> suppliers = new LinkedHashMap<>();

	private final Map<String, Object> beans = new LinkedHashMap<>();

	private @Nullable ServletContext servletContext;

	/** Registers an object under a name, made by a supplier. */
	public <T> void registerBean(String beanName, Class<T> beanClass, Supplier<T> supplier) {
		if (this.suppliers.containsKey(beanName)) {
			throw new IllegalStateException("A bean named '" + beanName + "' is already registered");
		}
		this.suppliers.put(beanName, supplier);
	}

	public void setServletContext(@Nullable ServletContext servletContext) {
		this.servletContext = servletContext;
	}

	@Override
	public @Nullable ServletContext getServletContext() {
		return this.servletContext;
	}

	@Override
	public boolean containsBean(String name) {
		return this.suppliers.containsKey(name);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) {
		Object bean = bean(name);
		if (!requiredType.isInstance(bean)) {
			throw new IllegalStateException(
					"Bean named '" + name + "' is expected to be of type '" + requiredType.getName() + "'");
		}
		return requiredType.cast(bean);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(Class<T> type) {
		Map<String, T> found = new LinkedHashMap<>();
		for (String name : this.suppliers.keySet()) {
			Object bean = bean(name);
			if (type.isInstance(bean)) {
				found.put(name, type.cast(bean));
			}
		}
		return found;
	}

	private Object bean(String name) {
		Object bean = this.beans.get(name);
		if (bean == null) {
			Supplier<?> supplier = this.suppliers.get(name);
			if (supplier == null) {
				throw new IllegalStateException("No bean named '" + name + "' available");
			}
			bean = supplier.get();
			if (bean == null) {
				throw new IllegalStateException("The supplier of the bean named '" + name + "' answered null");
			}
			this.beans.put(name, bean);
		}
		return bean;
	}

}
