// The WIT interface wasi:keyvalue/store as a Java interface: each abstract method a
// function of wit/keyvalue.wit, which rontolisp checks it against when it compiles the
// program. The version is the WIT's: point `wit` at another draft's package and the
// interface is checked against that one.
//
// The interface declares what the program calls. `open` is a function of the WIT
// interface; Bucket is its resource `bucket`, whose methods are the resource's and
// close() its release; KeyResponse is the record `key-response`, Error the variant
// `error`, a record each case. A result's error arm is thrown as a WitException holding
// the Error.

import java.util.List;
import java.util.Optional;

import am.ik.rontolisp.wit.WitImport;

@WitImport(value = "wasi:keyvalue/store", wit = "wit/keyvalue.wit")
public interface Store {

    Bucket open(String identifier);

    interface Bucket extends AutoCloseable {

        Optional<byte[]> get(String key);

        void set(String key, byte[] value);

        void delete(String key);

        boolean exists(String key);

        KeyResponse listKeys(Optional<Long> cursor);

        @Override
        void close();
    }

    record KeyResponse(List<String> keys, Optional<Long> cursor) {
    }

    sealed interface Error {

        record NoSuchStore() implements Error {
        }

        record AccessDenied() implements Error {
        }

        record Other(String value) implements Error {
        }
    }
}
