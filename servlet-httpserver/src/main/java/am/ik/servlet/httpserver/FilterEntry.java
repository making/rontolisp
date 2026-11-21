package am.ik.servlet.httpserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;

/**
 * A registered filter: its registration while the context is open, its configuration once
 * the context initialized it.
 */
final class FilterEntry extends Registered implements FilterRegistration.Dynamic, FilterConfig {

	final Filter filter;

	FilterEntry(WebContext context, String name, Filter filter) {
		super(context, name, filter.getClass().getName());
		this.filter = filter;
	}

	@Override
	public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
			String... servletNames) {
		if (servletNames == null || servletNames.length == 0) {
			throw new IllegalArgumentException("a mapping needs a servlet name");
		}
		this.context.open();
		this.context.mapFilter(new FilterMapping(this, dispatcherTypes, null, servletNames), isMatchAfter);
	}

	@Override
	public Collection<String> getServletNameMappings() {
		return mapped(false);
	}

	@Override
	public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
			String... urlPatterns) {
		if (urlPatterns == null || urlPatterns.length == 0) {
			throw new IllegalArgumentException("a mapping needs a URL pattern");
		}
		this.context.open();
		this.context.mapFilter(new FilterMapping(this, dispatcherTypes, urlPatterns, null), isMatchAfter);
	}

	@Override
	public Collection<String> getUrlPatternMappings() {
		return mapped(true);
	}

	private Collection<String> mapped(boolean patterns) {
		List<String> all = new ArrayList<>();
		for (FilterMapping mapping : this.context.filterMappings()) {
			String[] values = patterns ? mapping.urlPatterns : mapping.servletNames;
			if (mapping.filter == this && values != null) {
				all.addAll(Arrays.asList(values));
			}
		}
		return all;
	}

	@Override
	public String getFilterName() {
		return getName();
	}

	@Override
	public ServletContext getServletContext() {
		return this.context;
	}

}
