/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http.converter;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;

/**
 * What most converters share: the media types, the {@code Content-Type} and
 * {@code Content-Length} of what is written. A converter of an application's own states
 * the class it takes and reads and writes the body.
 */
public abstract class AbstractHttpMessageConverter<T> implements HttpMessageConverter<T> {

	private List<MediaType> supportedMediaTypes = Collections.emptyList();

	private @Nullable Charset defaultCharset;

	protected AbstractHttpMessageConverter() {
	}

	protected AbstractHttpMessageConverter(MediaType supportedMediaType) {
		setSupportedMediaTypes(Collections.singletonList(supportedMediaType));
	}

	protected AbstractHttpMessageConverter(MediaType... supportedMediaTypes) {
		setSupportedMediaTypes(List.of(supportedMediaTypes));
	}

	protected AbstractHttpMessageConverter(Charset defaultCharset, MediaType... supportedMediaTypes) {
		this.defaultCharset = defaultCharset;
		setSupportedMediaTypes(List.of(supportedMediaTypes));
	}

	public void setSupportedMediaTypes(List<MediaType> supportedMediaTypes) {
		if (supportedMediaTypes.isEmpty()) {
			throw new IllegalArgumentException("MediaType List must not be empty");
		}
		this.supportedMediaTypes = new ArrayList<>(supportedMediaTypes);
	}

	@Override
	public List<MediaType> getSupportedMediaTypes() {
		return Collections.unmodifiableList(this.supportedMediaTypes);
	}

	public void setDefaultCharset(@Nullable Charset defaultCharset) {
		this.defaultCharset = defaultCharset;
	}

	/** The charset of a body whose type names none, or {@code null}. */
	public @Nullable Charset getDefaultCharset() {
		return this.defaultCharset;
	}

	@Override
	public boolean canRead(Class<?> clazz, @Nullable MediaType mediaType) {
		return supports(clazz) && canRead(mediaType);
	}

	protected boolean canRead(@Nullable MediaType mediaType) {
		if (mediaType == null) {
			return true;
		}
		for (MediaType supportedMediaType : getSupportedMediaTypes()) {
			if (supportedMediaType.includes(mediaType)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean canWrite(Class<?> clazz, @Nullable MediaType mediaType) {
		return supports(clazz) && canWrite(mediaType);
	}

	protected boolean canWrite(@Nullable MediaType mediaType) {
		if (mediaType == null || MediaType.ALL.equalsTypeAndSubtype(mediaType)) {
			return true;
		}
		for (MediaType supportedMediaType : getSupportedMediaTypes()) {
			if (supportedMediaType.isCompatibleWith(mediaType)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public final T read(Class<? extends T> clazz, HttpInputMessage inputMessage)
			throws IOException, HttpMessageNotReadableException {
		return readInternal(clazz, inputMessage);
	}

	@Override
	public final void write(T t, @Nullable MediaType contentType, HttpOutputMessage outputMessage)
			throws IOException, HttpMessageNotWritableException {
		addDefaultHeaders(outputMessage.getHeaders(), t, contentType);
		writeInternal(t, outputMessage);
	}

	/**
	 * Sets the type and the length of what is written where the message has none: the
	 * type asked for when it is concrete, else the first this converter supports, with
	 * the default charset when the type names none.
	 */
	protected void addDefaultHeaders(HttpHeaders headers, T t, @Nullable MediaType contentType) throws IOException {
		if (headers.getContentType() == null) {
			MediaType contentTypeToUse = contentType;
			if (contentType == null || !contentType.isConcrete()) {
				contentTypeToUse = getDefaultContentType(t);
			}
			else if (MediaType.APPLICATION_OCTET_STREAM.equals(contentType)) {
				MediaType mediaType = getDefaultContentType(t);
				contentTypeToUse = (mediaType != null) ? mediaType : contentTypeToUse;
			}
			if (contentTypeToUse != null) {
				String value = contentTypeToUse.toString();
				if (contentTypeToUse.getCharset() == null) {
					Charset charset = getDefaultCharset();
					if (charset != null) {
						value += ";charset=" + charset.name();
					}
				}
				headers.set(HttpHeaders.CONTENT_TYPE, value);
			}
		}
		if (headers.getContentLength() < 0 && !headers.containsHeader(HttpHeaders.TRANSFER_ENCODING)) {
			Long contentLength = getContentLength(t, headers.getContentType());
			if (contentLength != null) {
				headers.setContentLength(contentLength);
			}
		}
	}

	protected @Nullable MediaType getDefaultContentType(T t) throws IOException {
		List<MediaType> mediaTypes = getSupportedMediaTypes();
		return !mediaTypes.isEmpty() ? mediaTypes.get(0) : null;
	}

	/** The length of what a value is written as, {@code null} when it is not known. */
	protected @Nullable Long getContentLength(T t, @Nullable MediaType contentType) throws IOException {
		return null;
	}

	/** Whether this converter takes the class. */
	protected abstract boolean supports(Class<?> clazz);

	protected abstract T readInternal(Class<? extends T> clazz, HttpInputMessage inputMessage)
			throws IOException, HttpMessageNotReadableException;

	protected abstract void writeInternal(T t, HttpOutputMessage outputMessage)
			throws IOException, HttpMessageNotWritableException;

}
