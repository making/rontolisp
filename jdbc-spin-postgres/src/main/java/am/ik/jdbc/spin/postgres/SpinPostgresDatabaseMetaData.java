package am.ik.jdbc.spin.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * What the driver tells of the server and of itself: who they are, how names are kept,
 * what the server's SQL and a statement and a result set of this driver can do, and the
 * tables, columns, keys and indexes, asked of the catalog ({@code pg_catalog}, PostgreSQL
 * documentation, "System Catalogs"). A catalog of JDBC is a database, and a session sees
 * the objects of its own.
 *
 * <p>
 * The answers are {@code jdbc-postgresql}'s, written here a second time (the two share no
 * code: {@code .kb/jdbc-spin-postgres.md}) and asked of both side by side by the tests.
 * The host converts a column of the catalog's {@code name} and {@code "char"} (their
 * binary form is their text) but not an {@code oid}: no query answers one, and
 * {@link #getColumns} reads a type by its name, not by its object identifier.
 */
final class SpinPostgresDatabaseMetaData extends UnsupportedDatabaseMetaData {

	private final SpinPostgresConnection connection;

	SpinPostgresDatabaseMetaData(SpinPostgresConnection connection) {
		this.connection = connection;
	}

	// Who.

	@Override
	public Connection getConnection() throws SQLException {
		return this.connection;
	}

	@Override
	public String getURL() throws SQLException {
		return this.connection.address().url;
	}

	@Override
	public String getUserName() throws SQLException {
		return this.connection.address().user;
	}

	@Override
	public String getDatabaseProductName() throws SQLException {
		return "PostgreSQL";
	}

	@Override
	public @Nullable String getDatabaseProductVersion() throws SQLException {
		return this.connection.setting("server_version");
	}

	@Override
	public int getDatabaseMajorVersion() throws SQLException {
		return version(0);
	}

	@Override
	public int getDatabaseMinorVersion() throws SQLException {
		return version(1);
	}

	// A number of the server's version, as 17 and 2 of "17.2 (Debian 17.2-1)".
	private int version(int part) throws SQLException {
		String version = getDatabaseProductVersion();
		int at = 0;
		int number = 0;
		for (int seen = 0; version != null && seen <= part; seen++) {
			number = 0;
			boolean digits = false;
			while (at < version.length() && Character.isDigit(version.charAt(at))) {
				number = number * 10 + (version.charAt(at++) - '0');
				digits = true;
			}
			if (!digits || (seen < part && (at >= version.length() || version.charAt(at++) != '.'))) {
				return 0;
			}
		}
		return number;
	}

	@Override
	public String getDriverName() throws SQLException {
		return SpinPostgresDriver.NAME;
	}

	@Override
	public String getDriverVersion() throws SQLException {
		return SpinPostgresDriver.MAJOR_VERSION + "." + SpinPostgresDriver.MINOR_VERSION;
	}

	@Override
	public int getDriverMajorVersion() {
		return SpinPostgresDriver.MAJOR_VERSION;
	}

	@Override
	public int getDriverMinorVersion() {
		return SpinPostgresDriver.MINOR_VERSION;
	}

	@Override
	public int getJDBCMajorVersion() throws SQLException {
		return 4;
	}

	@Override
	public int getJDBCMinorVersion() throws SQLException {
		return 3;
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		return this.connection.isReadOnly();
	}

	// Names (PostgreSQL documentation, "Identifiers and Key Words"): one not quoted is
	// folded to lower case, one in double quotes is kept and told apart by its case.

	@Override
	public String getIdentifierQuoteString() throws SQLException {
		return "\"";
	}

	@Override
	public boolean storesLowerCaseIdentifiers() throws SQLException {
		return true;
	}

	@Override
	public boolean storesUpperCaseIdentifiers() throws SQLException {
		return false;
	}

	@Override
	public boolean storesMixedCaseIdentifiers() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsMixedCaseIdentifiers() throws SQLException {
		return false;
	}

	@Override
	public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
		return false;
	}

	@Override
	public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
		return false;
	}

	@Override
	public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
		return true;
	}

	@Override
	public String getCatalogTerm() throws SQLException {
		return "database";
	}

	@Override
	public String getSchemaTerm() throws SQLException {
		return "schema";
	}

	@Override
	public String getCatalogSeparator() throws SQLException {
		return ".";
	}

	// Transactions.

	@Override
	public boolean supportsTransactions() throws SQLException {
		return true;
	}

	@Override
	public int getDefaultTransactionIsolation() throws SQLException {
		return Connection.TRANSACTION_READ_COMMITTED;
	}

	@Override
	public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
		return level == Connection.TRANSACTION_READ_UNCOMMITTED || level == Connection.TRANSACTION_READ_COMMITTED
				|| level == Connection.TRANSACTION_REPEATABLE_READ || level == Connection.TRANSACTION_SERIALIZABLE;
	}

	@Override
	public boolean supportsSavepoints() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
		return false;
	}

	@Override
	public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
		return false;
	}

	@Override
	public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
		return false;
	}

	// What a statement and a result set of this driver do.

	@Override
	public boolean supportsBatchUpdates() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsGetGeneratedKeys() throws SQLException {
		return true;
	}

	/** A statement is one command and answers one result. */
	@Override
	public boolean supportsMultipleResultSets() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsMultipleOpenResults() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsNamedParameters() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsStoredProcedures() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsStatementPooling() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsResultSetType(int type) throws SQLException {
		return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
	}

	@Override
	public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
		return supportsResultSetType(type) && concurrency == ResultSet.CONCUR_READ_ONLY;
	}

	@Override
	public boolean supportsResultSetHoldability(int holdability) throws SQLException {
		return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		return ResultSet.HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public int getSQLStateType() throws SQLException {
		return sqlStateSQL;
	}

	@Override
	public boolean locatorsUpdateCopy() throws SQLException {
		return false;
	}

	@Override
	public boolean usesLocalFiles() throws SQLException {
		return false;
	}

	@Override
	public boolean usesLocalFilePerTable() throws SQLException {
		return false;
	}

	// The server's SQL (PostgreSQL documentation, "SQL Commands", "Queries", "SQL Key
	// Words").

	/**
	 * The server's key words that are not SQL:2003's, asked of the server
	 * ({@code pg_get_keywords}).
	 */
	@Override
	public String getSQLKeywords() throws SQLException {
		// The key words of SQL:2003, reserved and not (the column of SQL:2003 in "SQL Key
		// Words" of the documentation of PostgreSQL 8.4); a local, not a field, so a
		// program holds them only when it asks for them.
		String sql2003 = "a,abs,absolute,action,ada,add,admin,after,all,allocate,alter,always,and,any,are,array,as,"
				+ "asc,asensitive,assertion,assignment,asymmetric,at,atomic,attribute,attributes,authorization,"
				+ "avg,base64,before,begin,bernoulli,between,bigint,binary,blob,boolean,both,breadth,by,c,call,"
				+ "called,cardinality,cascade,cascaded,case,cast,catalog,catalog_name,ceil,ceiling,chain,char,"
				+ "character,characteristics,characters,character_length,character_set_catalog,"
				+ "character_set_name,character_set_schema,char_length,check,class_origin,clob,close,coalesce,"
				+ "cobol,collate,collation,collation_catalog,collation_name,collation_schema,collect,column,"
				+ "column_name,command_function,command_function_code,commit,committed,condition,"
				+ "condition_number,connect,connection,connection_name,constraint,constraints,"
				+ "constraint_catalog,constraint_name,constraint_schema,constructor,contains,content,continue,"
				+ "convert,corr,corresponding,count,covar_pop,covar_samp,create,cross,cube,cume_dist,current,"
				+ "current_date,current_default_transform_group,current_path,current_role,current_time,"
				+ "current_timestamp,current_transform_group_for_type,current_user,cursor,cursor_name,cycle,"
				+ "data,date,datetime_interval_code,datetime_interval_precision,day,deallocate,dec,decimal,"
				+ "declare,default,defaults,deferrable,deferred,defined,definer,degree,delete,dense_rank,depth,"
				+ "deref,derived,desc,describe,descriptor,deterministic,diagnostics,disconnect,dispatch,"
				+ "distinct,document,domain,double,drop,dynamic,dynamic_function,dynamic_function_code,each,"
				+ "element,else,end,end-exec,equals,escape,every,except,exception,exclude,excluding,exec,"
				+ "execute,exists,exp,external,extract,false,fetch,filter,final,first,float,floor,following,"
				+ "for,foreign,fortran,found,free,from,full,function,fusion,g,general,generated,get,global,go,"
				+ "goto,grant,granted,group,grouping,having,hex,hierarchy,hold,hour,identity,immediate,"
				+ "implementation,in,including,increment,indicator,initially,inner,inout,input,insensitive,"
				+ "insert,instance,instantiable,int,integer,intersect,intersection,interval,into,invoker,is,"
				+ "isolation,join,k,key,key_member,key_type,language,large,last,lateral,leading,left,length,"
				+ "level,like,ln,local,localtime,localtimestamp,locator,lower,m,map,mapping,match,matched,max,"
				+ "maxvalue,member,merge,message_length,message_octet_length,message_text,method,min,minute,"
				+ "minvalue,mod,modifies,module,month,more,multiset,mumps,name,names,national,natural,nchar,"
				+ "nclob,nesting,new,next,no,none,normalize,normalized,not,null,nullable,nullif,nulls,number,"
				+ "numeric,object,octets,octet_length,of,old,on,only,open,option,options,or,order,ordering,"
				+ "ordinality,others,out,outer,output,over,overlaps,overlay,overriding,pad,parameter,"
				+ "parameter_mode,parameter_name,parameter_ordinal_position,parameter_specific_catalog,"
				+ "parameter_specific_name,parameter_specific_schema,partial,partition,pascal,path,"
				+ "percentile_cont,percentile_disc,percent_rank,placing,pli,position,power,preceding,precision,"
				+ "prepare,preserve,primary,prior,privileges,procedure,public,range,rank,read,reads,real,"
				+ "recursive,ref,references,referencing,regr_avgx,regr_avgy,regr_count,regr_intercept,regr_r2,"
				+ "regr_slope,regr_sxx,regr_sxy,regr_syy,relative,release,repeatable,restart,restrict,result,"
				+ "return,returned_cardinality,returned_length,returned_octet_length,returned_sqlstate,returns,"
				+ "revoke,right,role,rollback,rollup,routine,routine_catalog,routine_name,routine_schema,row,"
				+ "rows,row_count,row_number,savepoint,scale,schema,schema_name,scope,scope_catalog,scope_name,"
				+ "scope_schema,scroll,search,second,section,security,select,self,sensitive,sequence,"
				+ "serializable,server,server_name,session,session_user,set,sets,similar,simple,size,smallint,"
				+ "some,source,space,specific,specifictype,specific_name,sql,sqlexception,sqlstate,sqlwarning,"
				+ "sqrt,standalone,start,state,statement,static,stddev_pop,stddev_samp,strip,structure,style,"
				+ "subclass_origin,submultiset,substring,sum,symmetric,system,system_user,table,tablesample,"
				+ "table_name,temporary,then,ties,time,timestamp,timezone_hour,timezone_minute,to,"
				+ "top_level_count,trailing,transaction,transactions_committed,transactions_rolled_back,"
				+ "transaction_active,transform,transforms,translate,translation,treat,trigger,trigger_catalog,"
				+ "trigger_name,trigger_schema,trim,true,type,uescape,unbounded,uncommitted,under,union,unique,"
				+ "unknown,unnamed,unnest,update,upper,usage,user,user_defined_type_catalog,"
				+ "user_defined_type_code,user_defined_type_name,user_defined_type_schema,using,value,values,"
				+ "varchar,varying,var_pop,var_samp,version,view,when,whenever,where,whitespace,width_bucket,"
				+ "window,with,within,without,work,wrapper,write,xml,xmlagg,xmlattributes,xmlbinary,xmlcomment,"
				+ "xmlconcat,xmlelement,xmlforest,xmlnamespaces,xmlparse,xmlpi,xmlroot,xmlserialize,year,zone";
		try (ResultSet rows = query(
				"SELECT pg_catalog.string_agg(word, ',' ORDER BY word)"
						+ " FROM pg_catalog.pg_get_keywords() WHERE word <> ALL (pg_catalog.string_to_array(?, ','))",
				sql2003)) {
			rows.next();
			String words = rows.getString(1);
			return words != null ? words : "";
		}
	}

	// The functions of the escape {fn ..}: none, since no escape is read.

	@Override
	public String getNumericFunctions() throws SQLException {
		return "";
	}

	@Override
	public String getStringFunctions() throws SQLException {
		return "";
	}

	@Override
	public String getSystemFunctions() throws SQLException {
		return "";
	}

	@Override
	public String getTimeDateFunctions() throws SQLException {
		return "";
	}

	@Override
	public boolean supportsConvert() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsConvert(int fromType, int toType) throws SQLException {
		return false;
	}

	@Override
	public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
		return false;
	}

	/**
	 * The character a pattern of the catalog's queries puts before a % or a _ meant as
	 * itself.
	 */
	@Override
	public String getSearchStringEscape() throws SQLException {
		return "\\";
	}

	/**
	 * A name not quoted may hold a dollar sign after its first character, and any letter
	 * outside ASCII ("Identifiers and Key Words").
	 */
	@Override
	public String getExtraNameCharacters() throws SQLException {
		return "$";
	}

	@Override
	public String getProcedureTerm() throws SQLException {
		return "procedure";
	}

	@Override
	public boolean isCatalogAtStart() throws SQLException {
		return true;
	}

	// NULL sorts as if larger than any other value ("Sorting Rows").

	@Override
	public boolean nullsAreSortedHigh() throws SQLException {
		return true;
	}

	@Override
	public boolean nullsAreSortedLow() throws SQLException {
		return false;
	}

	@Override
	public boolean nullsAreSortedAtStart() throws SQLException {
		return false;
	}

	@Override
	public boolean nullsAreSortedAtEnd() throws SQLException {
		return false;
	}

	@Override
	public boolean nullPlusNonNullIsNull() throws SQLException {
		return true;
	}

	// A table or a function may be out of the user's reach: getTables and getProcedures
	// list every one.

	@Override
	public boolean allProceduresAreCallable() throws SQLException {
		return false;
	}

	@Override
	public boolean allTablesAreSelectable() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsAlterTableWithAddColumn() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsAlterTableWithDropColumn() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsColumnAliasing() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsTableCorrelationNames() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsDifferentTableCorrelationNames() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsExpressionsInOrderBy() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsOrderByUnrelated() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsGroupBy() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsGroupByUnrelated() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsGroupByBeyondSelect() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsLikeEscapeClause() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsMultipleTransactions() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsNonNullableColumns() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsMinimumSQLGrammar() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsCoreSQLGrammar() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsExtendedSQLGrammar() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsANSI92EntryLevelSQL() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsANSI92IntermediateSQL() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsANSI92FullSQL() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsIntegrityEnhancementFacility() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsOuterJoins() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsFullOuterJoins() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsLimitedOuterJoins() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSchemasInDataManipulation() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSchemasInProcedureCalls() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSchemasInTableDefinitions() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSchemasInIndexDefinitions() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
		return true;
	}

	// A name may be qualified by its database only where that is the session's own.

	@Override
	public boolean supportsCatalogsInDataManipulation() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsCatalogsInProcedureCalls() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsCatalogsInTableDefinitions() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
		return false;
	}

	// No cursor of a result set is named (setCursorName is refused).

	@Override
	public boolean supportsPositionedDelete() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsPositionedUpdate() throws SQLException {
		return false;
	}

	@Override
	public boolean supportsSelectForUpdate() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSubqueriesInComparisons() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSubqueriesInExists() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSubqueriesInIns() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsSubqueriesInQuantifieds() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsCorrelatedSubqueries() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsUnion() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsUnionAll() throws SQLException {
		return true;
	}

	// A result set holds its rows, so it stays open across a commit and a rollback.

	@Override
	public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
		return true;
	}

	@Override
	public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
		return true;
	}

	@Override
	public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
		return false;
	}

	@Override
	public boolean generatedKeyAlwaysReturned() throws SQLException {
		return true;
	}

	// A result set updates nothing, and sees no change made after its rows were read.

	@Override
	public boolean ownUpdatesAreVisible(int type) throws SQLException {
		return false;
	}

	@Override
	public boolean ownDeletesAreVisible(int type) throws SQLException {
		return false;
	}

	@Override
	public boolean ownInsertsAreVisible(int type) throws SQLException {
		return false;
	}

	@Override
	public boolean othersUpdatesAreVisible(int type) throws SQLException {
		return false;
	}

	@Override
	public boolean othersDeletesAreVisible(int type) throws SQLException {
		return false;
	}

	@Override
	public boolean othersInsertsAreVisible(int type) throws SQLException {
		return false;
	}

	@Override
	public boolean updatesAreDetected(int type) throws SQLException {
		return false;
	}

	@Override
	public boolean deletesAreDetected(int type) throws SQLException {
		return false;
	}

	@Override
	public boolean insertsAreDetected(int type) throws SQLException {
		return false;
	}

	// Limits ("PostgreSQL Limits"): a name is 63 bytes, a table 1600 columns, an index
	// 32,
	// a result 1664; 0 is no limit, or one the documentation does not state.

	@Override
	public int getMaxColumnNameLength() throws SQLException {
		return NAME_LENGTH;
	}

	@Override
	public int getMaxCursorNameLength() throws SQLException {
		return NAME_LENGTH;
	}

	@Override
	public int getMaxSchemaNameLength() throws SQLException {
		return NAME_LENGTH;
	}

	@Override
	public int getMaxProcedureNameLength() throws SQLException {
		return NAME_LENGTH;
	}

	@Override
	public int getMaxCatalogNameLength() throws SQLException {
		return NAME_LENGTH;
	}

	@Override
	public int getMaxTableNameLength() throws SQLException {
		return NAME_LENGTH;
	}

	@Override
	public int getMaxUserNameLength() throws SQLException {
		return NAME_LENGTH;
	}

	private static final int NAME_LENGTH = 63;

	@Override
	public int getMaxColumnsInTable() throws SQLException {
		return 1600;
	}

	@Override
	public int getMaxColumnsInIndex() throws SQLException {
		return 32;
	}

	@Override
	public int getMaxColumnsInSelect() throws SQLException {
		return 1664;
	}

	@Override
	public int getMaxColumnsInGroupBy() throws SQLException {
		return 0;
	}

	@Override
	public int getMaxColumnsInOrderBy() throws SQLException {
		return 0;
	}

	@Override
	public int getMaxTablesInSelect() throws SQLException {
		return 0;
	}

	@Override
	public int getMaxBinaryLiteralLength() throws SQLException {
		return 0;
	}

	@Override
	public int getMaxCharLiteralLength() throws SQLException {
		return 0;
	}

	@Override
	public int getMaxIndexLength() throws SQLException {
		return 0;
	}

	@Override
	public int getMaxRowSize() throws SQLException {
		return 0;
	}

	@Override
	public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
		return false;
	}

	@Override
	public int getMaxStatementLength() throws SQLException {
		return 0;
	}

	@Override
	public int getMaxStatements() throws SQLException {
		return 0;
	}

	/** The server's {@code max_connections}. */
	@Override
	public int getMaxConnections() throws SQLException {
		try (ResultSet rows = query("SELECT pg_catalog.current_setting('max_connections')::int4")) {
			rows.next();
			return rows.getInt(1);
		}
	}

	@Override
	public ResultSet getClientInfoProperties() throws SQLException {
		return new Rows(new String[] { "NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION" },
				new Postgres.DbDataType[] { TEXT, INT4, TEXT, TEXT })
			.add("ApplicationName", NAME_LENGTH, "", "The name the server shows for the session")
			.result();
	}

	// The catalog. A pattern is LIKE's: % any run of characters, _ any one, a backslash
	// before either for itself; null asks for every name, as a null catalog does. A name
	// that is no pattern is compared whole.

	// The parameters of a query of the catalog, named once: the catalog asked for and
	// the patterns or names of a schema and of a table.
	private static final String GIVEN = "WITH p AS (SELECT ?::text AS cat, ?::text AS schem, ?::text AS tab) ";

	// The object's catalog is the one asked for.
	private static final String CATALOG = "(p.cat IS NULL OR p.cat = pg_catalog.current_database())";

	@Override
	public ResultSet getCatalogs() throws SQLException {
		return query("SELECT datname AS \"TABLE_CAT\" FROM pg_catalog.pg_database WHERE datallowconn ORDER BY 1");
	}

	@Override
	public ResultSet getSchemas() throws SQLException {
		return getSchemas(null, null);
	}

	/** The schemas, but the temporary ones of other sessions. */
	@Override
	public ResultSet getSchemas(@Nullable String catalog, @Nullable String schemaPattern) throws SQLException {
		return query(GIVEN + "SELECT n.nspname AS \"TABLE_SCHEM\", pg_catalog.current_database() AS \"TABLE_CATALOG\""
				+ " FROM pg_catalog.pg_namespace n, p WHERE " + CATALOG
				+ " AND (p.schem IS NULL OR n.nspname LIKE p.schem)"
				+ " AND (n.nspname NOT LIKE 'pg\\_temp\\_%' AND n.nspname NOT LIKE 'pg\\_toast\\_temp\\_%'"
				+ " OR n.oid = pg_catalog.pg_my_temp_schema()) ORDER BY 2, 1", catalog, schemaPattern, null);
	}

	@Override
	public ResultSet getTableTypes() throws SQLException {
		Rows rows = new Rows(new String[] { "TABLE_TYPE" }, new Postgres.DbDataType[] { TEXT });
		for (String type : new String[] { "FOREIGN TABLE", "INDEX", "MATERIALIZED VIEW", "PARTITIONED INDEX",
				"PARTITIONED TABLE", "SEQUENCE", "SYSTEM INDEX", "SYSTEM TABLE", "SYSTEM TOAST INDEX",
				"SYSTEM TOAST TABLE", "SYSTEM VIEW", "TABLE", "TEMPORARY INDEX", "TEMPORARY SEQUENCE",
				"TEMPORARY TABLE", "TEMPORARY VIEW", "TYPE", "VIEW" }) {
			rows.add(type);
		}
		return rows.result();
	}

	// What a relation of pg_class is to getTables, by its kind and its schema: the
	// system's (pg_catalog, information_schema, pg_toast), a session's temporary one, or
	// a
	// user's. A method and not a field, as the key words are a local, so a program that
	// asks the catalog nothing holds no text of it.
	private static String tableType() {
		return "CASE WHEN n.nspname = 'pg_toast' THEN CASE c.relkind"
				+ " WHEN 'r' THEN 'SYSTEM TOAST TABLE' WHEN 't' THEN 'SYSTEM TOAST TABLE' WHEN 'i' THEN 'SYSTEM TOAST INDEX' END"
				+ " WHEN n.nspname IN ('pg_catalog', 'information_schema') THEN CASE c.relkind"
				+ " WHEN 'r' THEN 'SYSTEM TABLE' WHEN 'v' THEN 'SYSTEM VIEW' WHEN 'i' THEN 'SYSTEM INDEX' END"
				+ " WHEN n.nspname LIKE 'pg\\_temp\\_%' THEN CASE c.relkind"
				+ " WHEN 'r' THEN 'TEMPORARY TABLE' WHEN 'v' THEN 'TEMPORARY VIEW' WHEN 'i' THEN 'TEMPORARY INDEX'"
				+ " WHEN 'S' THEN 'TEMPORARY SEQUENCE' END"
				+ " ELSE CASE c.relkind WHEN 'r' THEN 'TABLE' WHEN 'p' THEN 'PARTITIONED TABLE' WHEN 'v' THEN 'VIEW'"
				+ " WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'S' THEN 'SEQUENCE' WHEN 'f' THEN 'FOREIGN TABLE'"
				+ " WHEN 'i' THEN 'INDEX' WHEN 'I' THEN 'PARTITIONED INDEX' WHEN 'c' THEN 'TYPE' END END";
	}

	@Override
	public ResultSet getTables(@Nullable String catalog, @Nullable String schemaPattern,
			@Nullable String tableNamePattern, String @Nullable [] types) throws SQLException {
		StringBuilder sql = new StringBuilder(GIVEN).append("SELECT * FROM (SELECT pg_catalog.current_database()")
			.append(" AS \"TABLE_CAT\", n.nspname AS \"TABLE_SCHEM\", c.relname AS \"TABLE_NAME\", ")
			.append(tableType())
			.append(" AS \"TABLE_TYPE\", pg_catalog.obj_description(c.oid, 'pg_class') AS \"REMARKS\",")
			.append(" NULL::text AS \"TYPE_CAT\", NULL::text AS \"TYPE_SCHEM\", NULL::text AS \"TYPE_NAME\",")
			.append(" NULL::text AS \"SELF_REFERENCING_COL_NAME\", NULL::text AS \"REF_GENERATION\"")
			.append(" FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace, p WHERE ")
			.append(CATALOG)
			.append(" AND (p.schem IS NULL OR n.nspname LIKE p.schem) AND (p.tab IS NULL OR c.relname LIKE p.tab)) t")
			.append(" WHERE \"TABLE_TYPE\" IS NOT NULL");
		@Nullable String[] parameters = new String[3 + (types != null ? types.length : 0)];
		parameters[0] = catalog;
		parameters[1] = schemaPattern;
		parameters[2] = tableNamePattern;
		if (types != null) {
			sql.append(" AND \"TABLE_TYPE\" IN (NULL");
			for (int i = 0; i < types.length; i++) {
				sql.append(", ?");
				parameters[3 + i] = types[i];
			}
			sql.append(')');
		}
		return query(sql.append(" ORDER BY 4, 1, 2, 3").toString(), parameters);
	}

	/**
	 * The columns of the tables, views and foreign tables. A domain's column is a
	 * {@link Types#DISTINCT} whose source is the domain's type; the type, the size and
	 * the digits are the type's by its name and its modifier, as the socket driver tells
	 * them of a column of a result.
	 */
	@Override
	public ResultSet getColumns(@Nullable String catalog, @Nullable String schemaPattern,
			@Nullable String tableNamePattern, @Nullable String columnNamePattern) throws SQLException {
		Rows columns = new Rows(
				new String[] { "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
						"COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
						"COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
						"IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
						"IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN" },
				new Postgres.DbDataType[] { TEXT, TEXT, TEXT, TEXT, INT4, TEXT, INT4, INT4, INT4, INT4, INT4, TEXT,
						TEXT, INT4, INT4, INT4, INT4, TEXT, TEXT, TEXT, TEXT, INT2, TEXT, TEXT });
		// The rank counts every column of the table; the pattern of the column's name is
		// applied after it. A domain's type is its base's name and modifier.
		String sql = GIVEN + ", q AS (SELECT ?::text AS col) SELECT r.* FROM (SELECT pg_catalog.current_database(),"
				+ " n.nspname, c.relname, a.attname, a.atttypmod, t.typname, t.typtype = 'd', b.typname,"
				+ " t.typtypmod, tn.nspname, a.attnotnull OR (t.typtype = 'd' AND t.typnotnull),"
				+ " pg_catalog.col_description(c.oid, a.attnum), pg_catalog.pg_get_expr(d.adbin, d.adrelid),"
				+ " row_number() OVER (PARTITION BY c.oid ORDER BY a.attnum), a.attidentity <> '', a.attgenerated <> ''"
				+ " FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid = a.attrelid"
				+ " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
				+ " JOIN pg_catalog.pg_type t ON t.oid = a.atttypid JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace"
				+ " LEFT JOIN pg_catalog.pg_type b ON b.oid = t.typbasetype"
				+ " LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum, p"
				+ " WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND " + CATALOG
				+ " AND (p.schem IS NULL OR n.nspname LIKE p.schem) AND (p.tab IS NULL OR c.relname LIKE p.tab)) r, q"
				+ " WHERE q.col IS NULL OR r.attname LIKE q.col ORDER BY 2, 3, 14";
		try (ResultSet rows = query(sql, catalog, schemaPattern, tableNamePattern, columnNamePattern)) {
			while (rows.next()) {
				boolean domain = rows.getBoolean(7);
				String type = Objects.requireNonNull(domain ? rows.getString(8) : rows.getString(6));
				int modifier = domain ? rows.getInt(9) : rows.getInt(5);
				int jdbc = Values.jdbc(type);
				String typeName = Objects.requireNonNull(rows.getString(6));
				Integer size = SpinPostgresResultSetMetaData.precision(type, modifier);
				Integer digits = switch (jdbc) {
					case Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.REAL, Types.DOUBLE,
							Types.TIME, Types.TIMESTAMP ->
						SpinPostgresResultSetMetaData.scale(type, modifier);
					default -> null;
				};
				boolean characters = jdbc == Types.CHAR || jdbc == Types.VARCHAR || jdbc == Types.BINARY;
				boolean notNull = rows.getBoolean(11);
				String definition = rows.getString(13);
				boolean identity = rows.getBoolean(15);
				boolean generated = rows.getBoolean(16);
				boolean numbered = identity || (definition != null && definition.startsWith("nextval("));
				columns.add(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
						domain ? Types.DISTINCT : jdbc,
						domain ? Sql.identifier(Objects.requireNonNull(rows.getString(10))) + "."
								+ Sql.identifier(typeName) : typeName,
						size, null, digits, 10, notNull ? columnNoNulls : columnNullable, rows.getString(12),
						definition, null, null, characters ? size : null, rows.getInt(14), notNull ? "NO" : "YES", null,
						null, null, domain ? jdbc : null, numbered ? "YES" : "NO", generated ? "YES" : "NO");
			}
		}
		return columns.result();
	}

	/** The columns of a table's primary key, by their names. */
	@Override
	public ResultSet getPrimaryKeys(@Nullable String catalog, @Nullable String schema, String table)
			throws SQLException {
		return query(GIVEN + "SELECT pg_catalog.current_database() AS \"TABLE_CAT\", n.nspname AS \"TABLE_SCHEM\","
				+ " c.relname AS \"TABLE_NAME\", a.attname AS \"COLUMN_NAME\", k.seq::int2 AS \"KEY_SEQ\","
				+ " o.conname AS \"PK_NAME\" FROM pg_catalog.pg_constraint o"
				+ " CROSS JOIN LATERAL unnest(o.conkey) WITH ORDINALITY AS k(attnum, seq)"
				+ " JOIN pg_catalog.pg_class c ON c.oid = o.conrelid JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
				+ " JOIN pg_catalog.pg_attribute a ON a.attrelid = o.conrelid AND a.attnum = k.attnum, p"
				+ " WHERE o.contype = 'p' AND " + CATALOG + " AND (p.schem IS NULL OR n.nspname = p.schem)"
				+ " AND (p.tab IS NULL OR c.relname = p.tab) ORDER BY 4", catalog, schema, table);
	}

	/** The columns the table's foreign keys refer to, by the table they are of. */
	@Override
	public ResultSet getImportedKeys(@Nullable String catalog, @Nullable String schema, String table)
			throws SQLException {
		return keys(null, null, null, catalog, schema, table, 1);
	}

	/** The foreign keys that refer to the table's columns, by the table they are of. */
	@Override
	public ResultSet getExportedKeys(@Nullable String catalog, @Nullable String schema, String table)
			throws SQLException {
		return keys(catalog, schema, table, null, null, null, 5);
	}

	@Override
	public ResultSet getCrossReference(@Nullable String parentCatalog, @Nullable String parentSchema,
			String parentTable, @Nullable String foreignCatalog, @Nullable String foreignSchema, String foreignTable)
			throws SQLException {
		return keys(parentCatalog, parentSchema, parentTable, foreignCatalog, foreignSchema, foreignTable, 5);
	}

	// The column pairs of the foreign keys from the foreign table to the parent one, a
	// table not named being any, ordered by the parent's columns (from 1) or the foreign
	// one's (from 5). A rule is one of the importedKey constants
	// (pg_constraint.confupdtype and confdeltype).
	private ResultSet keys(@Nullable String parentCatalog, @Nullable String parentSchema, @Nullable String parentTable,
			@Nullable String foreignCatalog, @Nullable String foreignSchema, @Nullable String foreignTable, int order)
			throws SQLException {
		String rule = " WHEN 'c' THEN " + importedKeyCascade + " WHEN 'n' THEN " + importedKeySetNull
				+ " WHEN 'd' THEN " + importedKeySetDefault + " WHEN 'r' THEN " + importedKeyRestrict + " ELSE "
				+ importedKeyNoAction + " END)::int2";
		return query("WITH p AS (SELECT ?::text AS cat, ?::text AS schem, ?::text AS tab),"
				+ " f AS (SELECT ?::text AS cat, ?::text AS schem, ?::text AS tab)"
				+ " SELECT pg_catalog.current_database() AS \"PKTABLE_CAT\", pn.nspname AS \"PKTABLE_SCHEM\","
				+ " pc.relname AS \"PKTABLE_NAME\", pa.attname AS \"PKCOLUMN_NAME\","
				+ " pg_catalog.current_database() AS \"FKTABLE_CAT\", fn.nspname AS \"FKTABLE_SCHEM\","
				+ " fc.relname AS \"FKTABLE_NAME\", fa.attname AS \"FKCOLUMN_NAME\", k.seq::int2 AS \"KEY_SEQ\","
				+ " (CASE o.confupdtype" + rule + " AS \"UPDATE_RULE\", (CASE o.confdeltype" + rule
				+ " AS \"DELETE_RULE\", o.conname AS \"FK_NAME\", x.relname AS \"PK_NAME\","
				+ " (CASE WHEN o.condeferred THEN " + importedKeyInitiallyDeferred + " WHEN o.condeferrable THEN "
				+ importedKeyInitiallyImmediate + " ELSE " + importedKeyNotDeferrable
				+ " END)::int2 AS \"DEFERRABILITY\"" + " FROM pg_catalog.pg_constraint o"
				+ " CROSS JOIN LATERAL unnest(o.confkey, o.conkey) WITH ORDINALITY AS k(pk, fk, seq)"
				+ " JOIN pg_catalog.pg_class pc ON pc.oid = o.confrelid"
				+ " JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace"
				+ " JOIN pg_catalog.pg_attribute pa ON pa.attrelid = o.confrelid AND pa.attnum = k.pk"
				+ " JOIN pg_catalog.pg_class fc ON fc.oid = o.conrelid"
				+ " JOIN pg_catalog.pg_namespace fn ON fn.oid = fc.relnamespace"
				+ " JOIN pg_catalog.pg_attribute fa ON fa.attrelid = o.conrelid AND fa.attnum = k.fk"
				+ " LEFT JOIN pg_catalog.pg_class x ON x.oid = o.conindid, p, f" + " WHERE o.contype = 'f' AND "
				+ CATALOG + " AND (f.cat IS NULL OR f.cat = pg_catalog.current_database())"
				+ " AND (p.schem IS NULL OR pn.nspname = p.schem) AND (p.tab IS NULL OR pc.relname = p.tab)"
				+ " AND (f.schem IS NULL OR fn.nspname = f.schem) AND (f.tab IS NULL OR fc.relname = f.tab)"
				+ " ORDER BY " + order + ", " + (order + 1) + ", " + (order + 2) + ", 9", parentCatalog, parentSchema,
				parentTable, foreignCatalog, foreignSchema, foreignTable);
	}

	/**
	 * A table's indexes, a row a key column (or expression). An index of the hash method
	 * is {@link #tableIndexHashed}, any other {@link #tableIndexOther}; no row of
	 * {@link #tableIndexStatistic} is answered. The cardinality is the server's estimate
	 * of the index's rows ({@code pg_class.reltuples}), whatever {@code approximate}
	 * asks.
	 */
	@Override
	public ResultSet getIndexInfo(@Nullable String catalog, @Nullable String schema, String table, boolean unique,
			boolean approximate) throws SQLException {
		return query(GIVEN + ", u AS (SELECT ?::boolean AS uniq) SELECT pg_catalog.current_database() AS \"TABLE_CAT\","
				+ " n.nspname AS \"TABLE_SCHEM\", c.relname AS \"TABLE_NAME\", NOT i.indisunique AS \"NON_UNIQUE\","
				+ " NULL::text AS \"INDEX_QUALIFIER\", x.relname AS \"INDEX_NAME\", (CASE m.amname WHEN 'hash' THEN "
				+ tableIndexHashed + " ELSE " + tableIndexOther + " END)::int2 AS \"TYPE\","
				+ " k.k::int2 AS \"ORDINAL_POSITION\", pg_catalog.pg_get_indexdef(x.oid, k.k, true) AS \"COLUMN_NAME\","
				+ " CASE WHEN pg_catalog.pg_index_column_has_property(x.oid, k.k, 'desc') THEN 'D'"
				+ " WHEN pg_catalog.pg_index_column_has_property(x.oid, k.k, 'asc') THEN 'A' END AS \"ASC_OR_DESC\","
				+ " greatest(x.reltuples, 0)::int8 AS \"CARDINALITY\", x.relpages::int8 AS \"PAGES\","
				+ " pg_catalog.pg_get_expr(i.indpred, i.indrelid) AS \"FILTER_CONDITION\""
				+ " FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid = i.indrelid"
				+ " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace JOIN pg_catalog.pg_class x ON x.oid = i.indexrelid"
				+ " JOIN pg_catalog.pg_am m ON m.oid = x.relam"
				+ " CROSS JOIN LATERAL generate_series(1, i.indnkeyatts) AS k(k), p, u WHERE " + CATALOG
				+ " AND (p.schem IS NULL OR n.nspname = p.schem) AND (p.tab IS NULL OR c.relname = p.tab)"
				+ " AND (i.indisunique OR NOT u.uniq) ORDER BY 4, 7, 6, 8", catalog, schema, table,
				Boolean.toString(unique));
	}

	// A query of the catalog, each parameter text; its result set walks both ways and
	// closes its statement.
	private ResultSet query(String sql, @Nullable String... parameters) throws SQLException {
		PreparedStatement statement = this.connection.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE,
				ResultSet.CONCUR_READ_ONLY);
		for (int i = 0; i < parameters.length; i++) {
			statement.setString(i + 1, parameters[i]);
		}
		statement.closeOnCompletion();
		return statement.executeQuery();
	}

	private static final Postgres.DbDataType TEXT = new Postgres.DbDataType.Str();

	private static final Postgres.DbDataType INT4 = new Postgres.DbDataType.Int32();

	private static final Postgres.DbDataType INT2 = new Postgres.DbDataType.Int16();

	// Rows the driver makes itself, under columns of the host's cases, so a result set
	// reads them as it reads the host's.
	private static final class Rows {

		private final List<Postgres.Column> columns = new ArrayList<>();

		private final List<List<Postgres.DbValue>> rows = new ArrayList<>();

		Rows(String[] names, Postgres.DbDataType[] types) {
			for (int i = 0; i < names.length; i++) {
				this.columns.add(new Postgres.Column(names[i], types[i]));
			}
		}

		// A row: each value a text or a number, null for NULL.
		Rows add(@Nullable Object... values) {
			List<Postgres.DbValue> row = new ArrayList<>();
			for (int i = 0; i < values.length; i++) {
				Object value = values[i];
				row.add(value == null ? new Postgres.DbValue.DbNull() : switch (this.columns.get(i).dataType()) {
					case Postgres.DbDataType.Int32 type -> new Postgres.DbValue.Int32(((Number) value).intValue());
					case Postgres.DbDataType.Int16 type -> new Postgres.DbValue.Int16(((Number) value).shortValue());
					default -> new Postgres.DbValue.Str(value.toString());
				});
			}
			this.rows.add(row);
			return this;
		}

		ResultSet result() {
			return new SpinPostgresResultSet(null, new Postgres.RowSet(this.columns, this.rows),
					ResultSet.TYPE_SCROLL_INSENSITIVE);
		}

	}

}
