// Shiori: where a statement finds its connection.
package com.example.shiori.jdbc;

import java.sql.SQLException;

// The pool's connection for the statement alone, or the one a transaction holds.
interface Connector {

    <T> T run(Work<T> work) throws SQLException;

}
