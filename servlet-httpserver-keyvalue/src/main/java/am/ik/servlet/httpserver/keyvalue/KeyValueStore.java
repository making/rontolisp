package am.ik.servlet.httpserver.keyvalue;

import java.util.List;
import java.util.Optional;

import am.ik.rontolisp.wit.WitImport;

/**
 * The WIT interface {@code wasi:keyvalue/store@0.2.0-draft2}, the functions the session
 * store calls of it. In a WebAssembly component rontolisp compiles, the host is its
 * implementation; under a JDK, and on rontolisp's interpreter and JVM, the first service
 * provider listed in {@code META-INF/services}: {@link MemoryKeyValueStore} unless the
 * application lists one of its own before it.
 */
@WitImport(value = "wasi:keyvalue/store@0.2.0-draft2", wit = "wit/keyvalue.wit")
public interface KeyValueStore {

	/**
	 * Opens a store by the identifier the host knows it by: {@code ""} is the default.
	 * @param identifier the store's identifier
	 * @return a handle on the store
	 */
	Bucket open(String identifier);

	/** A handle on a store, to be closed once used. */
	interface Bucket extends AutoCloseable {

		/**
		 * The value of a key.
		 * @param key the key
		 * @return the value, or empty where the key has none
		 */
		Optional<byte[]> get(String key);

		/**
		 * Sets the value of a key.
		 * @param key the key
		 * @param value the value
		 */
		void set(String key, byte[] value);

		/**
		 * Removes a key and its value, if it has one.
		 * @param key the key
		 */
		void delete(String key);

		/**
		 * A page of the store's keys, in no order.
		 * @param cursor where the page starts: empty for the first, else the cursor of
		 * the page before
		 * @return the keys, and the cursor of the next page if there is one
		 */
		KeyResponse listKeys(Optional<String> cursor);

		@Override
		void close();

	}

	/**
	 * A page of keys.
	 *
	 * @param keys the keys
	 * @param cursor the next page's cursor, empty after the last page
	 */
	record KeyResponse(List<String> keys, Optional<String> cursor) {
	}

	/** Why a call failed, carried by the {@code WitException} it throws. */
	sealed interface Error {

		/** The host has no store of the identifier. */
		record NoSuchStore() implements Error {
		}

		/** The store is not the program's to use. */
		record AccessDenied() implements Error {
		}

		/**
		 * Another failure.
		 *
		 * @param value the host's words
		 */
		record Other(String value) implements Error {
		}

	}

}
