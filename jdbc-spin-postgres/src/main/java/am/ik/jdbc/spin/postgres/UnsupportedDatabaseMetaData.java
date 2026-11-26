package am.ik.jdbc.spin.postgres;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

import org.jspecify.annotations.Nullable;

/**
 * Every abstract member of {@link DatabaseMetaData} as a refusal by name, so that the
 * class under it holds what the driver does and nothing else. Written by a script from
 * the interface's signatures; a member is changed in the subclass, not here.
 */
abstract class UnsupportedDatabaseMetaData implements DatabaseMetaData {

	@Override
	public boolean allProceduresAreCallable() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.allProceduresAreCallable");
	}

	@Override
	public boolean allTablesAreSelectable() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.allTablesAreSelectable");
	}

	@Override
	public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.autoCommitFailureClosesAllResultSets");
	}

	@Override
	public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.dataDefinitionCausesTransactionCommit");
	}

	@Override
	public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.dataDefinitionIgnoredInTransactions");
	}

	@Override
	public boolean deletesAreDetected(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.deletesAreDetected");
	}

	@Override
	public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.doesMaxRowSizeIncludeBlobs");
	}

	@Override
	public boolean generatedKeyAlwaysReturned() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.generatedKeyAlwaysReturned");
	}

	@Override
	public ResultSet getAttributes(String a, String b, String c, String d) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getAttributes");
	}

	@Override
	public ResultSet getBestRowIdentifier(String a, String b, String c, int d, boolean e) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getBestRowIdentifier");
	}

	@Override
	public String getCatalogSeparator() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getCatalogSeparator");
	}

	@Override
	public String getCatalogTerm() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getCatalogTerm");
	}

	@Override
	public ResultSet getCatalogs() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getCatalogs");
	}

	@Override
	public ResultSet getClientInfoProperties() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getClientInfoProperties");
	}

	@Override
	public ResultSet getColumnPrivileges(String a, String b, String c, String d) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getColumnPrivileges");
	}

	@Override
	public ResultSet getColumns(String a, String b, String c, String d) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getColumns");
	}

	@Override
	public Connection getConnection() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getConnection");
	}

	@Override
	public ResultSet getCrossReference(String a, String b, String c, String d, String e, String f) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getCrossReference");
	}

	@Override
	public int getDatabaseMajorVersion() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getDatabaseMajorVersion");
	}

	@Override
	public int getDatabaseMinorVersion() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getDatabaseMinorVersion");
	}

	@Override
	public String getDatabaseProductName() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getDatabaseProductName");
	}

	@Override
	public @Nullable String getDatabaseProductVersion() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getDatabaseProductVersion");
	}

	@Override
	public int getDefaultTransactionIsolation() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getDefaultTransactionIsolation");
	}

	@Override
	public String getDriverName() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getDriverName");
	}

	@Override
	public String getDriverVersion() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getDriverVersion");
	}

	@Override
	public ResultSet getExportedKeys(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getExportedKeys");
	}

	@Override
	public String getExtraNameCharacters() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getExtraNameCharacters");
	}

	@Override
	public ResultSet getFunctionColumns(String a, String b, String c, String d) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getFunctionColumns");
	}

	@Override
	public ResultSet getFunctions(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getFunctions");
	}

	@Override
	public String getIdentifierQuoteString() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getIdentifierQuoteString");
	}

	@Override
	public ResultSet getImportedKeys(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getImportedKeys");
	}

	@Override
	public ResultSet getIndexInfo(String a, String b, String c, boolean d, boolean e) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getIndexInfo");
	}

	@Override
	public int getJDBCMajorVersion() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getJDBCMajorVersion");
	}

	@Override
	public int getJDBCMinorVersion() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getJDBCMinorVersion");
	}

	@Override
	public int getMaxBinaryLiteralLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxBinaryLiteralLength");
	}

	@Override
	public int getMaxCatalogNameLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxCatalogNameLength");
	}

	@Override
	public int getMaxCharLiteralLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxCharLiteralLength");
	}

	@Override
	public int getMaxColumnNameLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxColumnNameLength");
	}

	@Override
	public int getMaxColumnsInGroupBy() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxColumnsInGroupBy");
	}

	@Override
	public int getMaxColumnsInIndex() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxColumnsInIndex");
	}

	@Override
	public int getMaxColumnsInOrderBy() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxColumnsInOrderBy");
	}

	@Override
	public int getMaxColumnsInSelect() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxColumnsInSelect");
	}

	@Override
	public int getMaxColumnsInTable() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxColumnsInTable");
	}

	@Override
	public int getMaxConnections() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxConnections");
	}

	@Override
	public int getMaxCursorNameLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxCursorNameLength");
	}

	@Override
	public int getMaxIndexLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxIndexLength");
	}

	@Override
	public int getMaxProcedureNameLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxProcedureNameLength");
	}

	@Override
	public int getMaxRowSize() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxRowSize");
	}

	@Override
	public int getMaxSchemaNameLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxSchemaNameLength");
	}

	@Override
	public int getMaxStatementLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxStatementLength");
	}

	@Override
	public int getMaxStatements() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxStatements");
	}

	@Override
	public int getMaxTableNameLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxTableNameLength");
	}

	@Override
	public int getMaxTablesInSelect() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxTablesInSelect");
	}

	@Override
	public int getMaxUserNameLength() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getMaxUserNameLength");
	}

	@Override
	public String getNumericFunctions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getNumericFunctions");
	}

	@Override
	public ResultSet getPrimaryKeys(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getPrimaryKeys");
	}

	@Override
	public ResultSet getProcedureColumns(String a, String b, String c, String d) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getProcedureColumns");
	}

	@Override
	public String getProcedureTerm() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getProcedureTerm");
	}

	@Override
	public ResultSet getProcedures(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getProcedures");
	}

	@Override
	public ResultSet getPseudoColumns(String a, String b, String c, String d) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getPseudoColumns");
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getResultSetHoldability");
	}

	@Override
	public RowIdLifetime getRowIdLifetime() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getRowIdLifetime");
	}

	@Override
	public String getSQLKeywords() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSQLKeywords");
	}

	@Override
	public int getSQLStateType() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSQLStateType");
	}

	@Override
	public String getSchemaTerm() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSchemaTerm");
	}

	@Override
	public ResultSet getSchemas() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSchemas");
	}

	@Override
	public ResultSet getSchemas(String a, String b) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSchemas");
	}

	@Override
	public String getSearchStringEscape() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSearchStringEscape");
	}

	@Override
	public String getStringFunctions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getStringFunctions");
	}

	@Override
	public ResultSet getSuperTables(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSuperTables");
	}

	@Override
	public ResultSet getSuperTypes(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSuperTypes");
	}

	@Override
	public String getSystemFunctions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getSystemFunctions");
	}

	@Override
	public ResultSet getTablePrivileges(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getTablePrivileges");
	}

	@Override
	public ResultSet getTableTypes() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getTableTypes");
	}

	@Override
	public ResultSet getTables(String a, String b, String c, String[] d) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getTables");
	}

	@Override
	public String getTimeDateFunctions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getTimeDateFunctions");
	}

	@Override
	public ResultSet getTypeInfo() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getTypeInfo");
	}

	@Override
	public ResultSet getUDTs(String a, String b, String c, int[] d) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getUDTs");
	}

	@Override
	public String getURL() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getURL");
	}

	@Override
	public String getUserName() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getUserName");
	}

	@Override
	public ResultSet getVersionColumns(String a, String b, String c) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.getVersionColumns");
	}

	@Override
	public boolean insertsAreDetected(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.insertsAreDetected");
	}

	@Override
	public boolean isCatalogAtStart() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.isCatalogAtStart");
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.isReadOnly");
	}

	@Override
	public boolean isWrapperFor(Class<?> a) throws SQLException {
		return a.isInstance(this);
	}

	@Override
	public boolean locatorsUpdateCopy() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.locatorsUpdateCopy");
	}

	@Override
	public boolean nullPlusNonNullIsNull() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.nullPlusNonNullIsNull");
	}

	@Override
	public boolean nullsAreSortedAtEnd() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.nullsAreSortedAtEnd");
	}

	@Override
	public boolean nullsAreSortedAtStart() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.nullsAreSortedAtStart");
	}

	@Override
	public boolean nullsAreSortedHigh() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.nullsAreSortedHigh");
	}

	@Override
	public boolean nullsAreSortedLow() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.nullsAreSortedLow");
	}

	@Override
	public boolean othersDeletesAreVisible(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.othersDeletesAreVisible");
	}

	@Override
	public boolean othersInsertsAreVisible(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.othersInsertsAreVisible");
	}

	@Override
	public boolean othersUpdatesAreVisible(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.othersUpdatesAreVisible");
	}

	@Override
	public boolean ownDeletesAreVisible(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.ownDeletesAreVisible");
	}

	@Override
	public boolean ownInsertsAreVisible(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.ownInsertsAreVisible");
	}

	@Override
	public boolean ownUpdatesAreVisible(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.ownUpdatesAreVisible");
	}

	@Override
	public boolean storesLowerCaseIdentifiers() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.storesLowerCaseIdentifiers");
	}

	@Override
	public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.storesLowerCaseQuotedIdentifiers");
	}

	@Override
	public boolean storesMixedCaseIdentifiers() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.storesMixedCaseIdentifiers");
	}

	@Override
	public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.storesMixedCaseQuotedIdentifiers");
	}

	@Override
	public boolean storesUpperCaseIdentifiers() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.storesUpperCaseIdentifiers");
	}

	@Override
	public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.storesUpperCaseQuotedIdentifiers");
	}

	@Override
	public boolean supportsANSI92EntryLevelSQL() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsANSI92EntryLevelSQL");
	}

	@Override
	public boolean supportsANSI92FullSQL() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsANSI92FullSQL");
	}

	@Override
	public boolean supportsANSI92IntermediateSQL() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsANSI92IntermediateSQL");
	}

	@Override
	public boolean supportsAlterTableWithAddColumn() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsAlterTableWithAddColumn");
	}

	@Override
	public boolean supportsAlterTableWithDropColumn() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsAlterTableWithDropColumn");
	}

	@Override
	public boolean supportsBatchUpdates() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsBatchUpdates");
	}

	@Override
	public boolean supportsCatalogsInDataManipulation() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsCatalogsInDataManipulation");
	}

	@Override
	public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsCatalogsInIndexDefinitions");
	}

	@Override
	public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsCatalogsInPrivilegeDefinitions");
	}

	@Override
	public boolean supportsCatalogsInProcedureCalls() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsCatalogsInProcedureCalls");
	}

	@Override
	public boolean supportsCatalogsInTableDefinitions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsCatalogsInTableDefinitions");
	}

	@Override
	public boolean supportsColumnAliasing() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsColumnAliasing");
	}

	@Override
	public boolean supportsConvert() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsConvert");
	}

	@Override
	public boolean supportsConvert(int a, int b) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsConvert");
	}

	@Override
	public boolean supportsCoreSQLGrammar() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsCoreSQLGrammar");
	}

	@Override
	public boolean supportsCorrelatedSubqueries() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsCorrelatedSubqueries");
	}

	@Override
	public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsDataDefinitionAndDataManipulationTransactions");
	}

	@Override
	public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsDataManipulationTransactionsOnly");
	}

	@Override
	public boolean supportsDifferentTableCorrelationNames() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsDifferentTableCorrelationNames");
	}

	@Override
	public boolean supportsExpressionsInOrderBy() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsExpressionsInOrderBy");
	}

	@Override
	public boolean supportsExtendedSQLGrammar() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsExtendedSQLGrammar");
	}

	@Override
	public boolean supportsFullOuterJoins() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsFullOuterJoins");
	}

	@Override
	public boolean supportsGetGeneratedKeys() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsGetGeneratedKeys");
	}

	@Override
	public boolean supportsGroupBy() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsGroupBy");
	}

	@Override
	public boolean supportsGroupByBeyondSelect() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsGroupByBeyondSelect");
	}

	@Override
	public boolean supportsGroupByUnrelated() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsGroupByUnrelated");
	}

	@Override
	public boolean supportsIntegrityEnhancementFacility() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsIntegrityEnhancementFacility");
	}

	@Override
	public boolean supportsLikeEscapeClause() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsLikeEscapeClause");
	}

	@Override
	public boolean supportsLimitedOuterJoins() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsLimitedOuterJoins");
	}

	@Override
	public boolean supportsMinimumSQLGrammar() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsMinimumSQLGrammar");
	}

	@Override
	public boolean supportsMixedCaseIdentifiers() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsMixedCaseIdentifiers");
	}

	@Override
	public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsMixedCaseQuotedIdentifiers");
	}

	@Override
	public boolean supportsMultipleOpenResults() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsMultipleOpenResults");
	}

	@Override
	public boolean supportsMultipleResultSets() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsMultipleResultSets");
	}

	@Override
	public boolean supportsMultipleTransactions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsMultipleTransactions");
	}

	@Override
	public boolean supportsNamedParameters() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsNamedParameters");
	}

	@Override
	public boolean supportsNonNullableColumns() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsNonNullableColumns");
	}

	@Override
	public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsOpenCursorsAcrossCommit");
	}

	@Override
	public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsOpenCursorsAcrossRollback");
	}

	@Override
	public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsOpenStatementsAcrossCommit");
	}

	@Override
	public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsOpenStatementsAcrossRollback");
	}

	@Override
	public boolean supportsOrderByUnrelated() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsOrderByUnrelated");
	}

	@Override
	public boolean supportsOuterJoins() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsOuterJoins");
	}

	@Override
	public boolean supportsPositionedDelete() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsPositionedDelete");
	}

	@Override
	public boolean supportsPositionedUpdate() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsPositionedUpdate");
	}

	@Override
	public boolean supportsResultSetConcurrency(int a, int b) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsResultSetConcurrency");
	}

	@Override
	public boolean supportsResultSetHoldability(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsResultSetHoldability");
	}

	@Override
	public boolean supportsResultSetType(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsResultSetType");
	}

	@Override
	public boolean supportsSavepoints() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSavepoints");
	}

	@Override
	public boolean supportsSchemasInDataManipulation() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSchemasInDataManipulation");
	}

	@Override
	public boolean supportsSchemasInIndexDefinitions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSchemasInIndexDefinitions");
	}

	@Override
	public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSchemasInPrivilegeDefinitions");
	}

	@Override
	public boolean supportsSchemasInProcedureCalls() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSchemasInProcedureCalls");
	}

	@Override
	public boolean supportsSchemasInTableDefinitions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSchemasInTableDefinitions");
	}

	@Override
	public boolean supportsSelectForUpdate() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSelectForUpdate");
	}

	@Override
	public boolean supportsStatementPooling() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsStatementPooling");
	}

	@Override
	public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsStoredFunctionsUsingCallSyntax");
	}

	@Override
	public boolean supportsStoredProcedures() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsStoredProcedures");
	}

	@Override
	public boolean supportsSubqueriesInComparisons() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSubqueriesInComparisons");
	}

	@Override
	public boolean supportsSubqueriesInExists() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSubqueriesInExists");
	}

	@Override
	public boolean supportsSubqueriesInIns() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSubqueriesInIns");
	}

	@Override
	public boolean supportsSubqueriesInQuantifieds() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsSubqueriesInQuantifieds");
	}

	@Override
	public boolean supportsTableCorrelationNames() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsTableCorrelationNames");
	}

	@Override
	public boolean supportsTransactionIsolationLevel(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsTransactionIsolationLevel");
	}

	@Override
	public boolean supportsTransactions() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsTransactions");
	}

	@Override
	public boolean supportsUnion() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsUnion");
	}

	@Override
	public boolean supportsUnionAll() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.supportsUnionAll");
	}

	@Override
	public <T> T unwrap(Class<T> a) throws SQLException {
		if (a.isInstance(this)) {
			return a.cast(this);
		}
		throw Failures.unsupported("An unwrap to " + a.getName());
	}

	@Override
	public boolean updatesAreDetected(int a) throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.updatesAreDetected");
	}

	@Override
	public boolean usesLocalFilePerTable() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.usesLocalFilePerTable");
	}

	@Override
	public boolean usesLocalFiles() throws SQLException {
		throw Failures.unsupported("DatabaseMetaData.usesLocalFiles");
	}

}
