/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.web.context;

import java.util.Map;

import jakarta.servlet.ServletContext;
import org.jspecify.annotations.Nullable;

/** The objects of a web application, by name. */
public interface WebApplicationContext {

	boolean containsBean(String name);

	<T> T getBean(String name, Class<T> requiredType);

	/** The objects that are of a type, by name, in the order they were registered. */
	<T> Map<String, T> getBeansOfType(Class<T> type);

	/**
	 * The servlet context, {@code null} until the application's servlet is initialized.
	 */
	@Nullable ServletContext getServletContext();

}
