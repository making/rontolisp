// Shiori: what a repository runs its statements through.
package com.example.shiori.jdbc;

// The database (each statement on a connection of the pool's), or a transaction (each on
// the connection it holds).
public interface JdbcClient {

    StatementSpec sql(String sql);

}
