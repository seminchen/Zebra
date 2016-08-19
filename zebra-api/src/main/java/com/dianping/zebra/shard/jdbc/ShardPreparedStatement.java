/**
 * Project: ${zebra-client.aid}
 *
 * File Created at 2011-6-10 $Id$
 *
 * Copyright 2010 dianping.com. All rights reserved.
 *
 * This software is the confidential and proprietary information of Dianping
 * Company. ("Confidential Information"). You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with dianping.com.
 */
package com.dianping.zebra.shard.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import com.dianping.zebra.group.jdbc.param.ArrayParamContext;
import com.dianping.zebra.group.jdbc.param.AsciiParamContext;
import com.dianping.zebra.group.jdbc.param.BigDecimalParamContext;
import com.dianping.zebra.group.jdbc.param.BinaryStreamParamContext;
import com.dianping.zebra.group.jdbc.param.BlobParamContext;
import com.dianping.zebra.group.jdbc.param.BooleanParamContext;
import com.dianping.zebra.group.jdbc.param.ByteArrayParamContext;
import com.dianping.zebra.group.jdbc.param.ByteParamContext;
import com.dianping.zebra.group.jdbc.param.CharacterStreamParamContext;
import com.dianping.zebra.group.jdbc.param.ClobParamContext;
import com.dianping.zebra.group.jdbc.param.DateParamContext;
import com.dianping.zebra.group.jdbc.param.DoubleParamContext;
import com.dianping.zebra.group.jdbc.param.FloatParamContext;
import com.dianping.zebra.group.jdbc.param.IntParamContext;
import com.dianping.zebra.group.jdbc.param.LongParamContext;
import com.dianping.zebra.group.jdbc.param.NCharacterStreamParamContext;
import com.dianping.zebra.group.jdbc.param.NClobParamContext;
import com.dianping.zebra.group.jdbc.param.NStringParamContext;
import com.dianping.zebra.group.jdbc.param.NullParamContext;
import com.dianping.zebra.group.jdbc.param.ObjectParamContext;
import com.dianping.zebra.group.jdbc.param.ParamContext;
import com.dianping.zebra.group.jdbc.param.RefParamContext;
import com.dianping.zebra.group.jdbc.param.RowIdParamContext;
import com.dianping.zebra.group.jdbc.param.SQLXMLParamContext;
import com.dianping.zebra.group.jdbc.param.ShortParamContext;
import com.dianping.zebra.group.jdbc.param.StringParamContext;
import com.dianping.zebra.group.jdbc.param.TimeParamContext;
import com.dianping.zebra.group.jdbc.param.TimestampParamContext;
import com.dianping.zebra.group.jdbc.param.URLParamContext;
import com.dianping.zebra.group.jdbc.param.UnicodeStreamParamContext;
import com.dianping.zebra.group.util.DaoContextHolder;
import com.dianping.zebra.shard.jdbc.parallel.PreparedStatementExecuteQueryCallable;
import com.dianping.zebra.shard.jdbc.parallel.PreparedStatementExecuteUpdateCallable;
import com.dianping.zebra.shard.jdbc.parallel.SQLThreadPoolExecutor;
import com.dianping.zebra.shard.jdbc.parallel.UpdateResult;
import com.dianping.zebra.shard.jdbc.unsupport.UnsupportedShardPreparedStatement;
import com.dianping.zebra.shard.router.RouterResult;
import com.dianping.zebra.shard.router.RouterResult.RouterTarget;
import com.dianping.zebra.util.SqlType;

/**
 * @author Leo Liang
 * @author hao.zhu
 */
public class ShardPreparedStatement extends UnsupportedShardPreparedStatement implements PreparedStatement {

	protected String sql;

	private int autoGeneratedKeys = -1;

	private int[] columnIndexes;

	private String[] columnNames;

	private List<ParamContext> params = new ArrayList<ParamContext>();

	protected PreparedStatement createPrepareStatement(Connection connection, String targetSql) throws SQLException {
		PreparedStatement stmt = null;
		if (getResultSetType() != -1 && getResultSetConcurrency() != -1 && getResultSetHoldability() != -1) {
			stmt = connection.prepareStatement(targetSql, getResultSetType(), getResultSetConcurrency(),
					getResultSetHoldability());
		} else if (getResultSetType() != -1 && getResultSetConcurrency() != -1) {
			stmt = connection.prepareStatement(targetSql, getResultSetType(), getResultSetConcurrency());
		} else if (autoGeneratedKeys != -1) {
			stmt = connection.prepareStatement(targetSql, autoGeneratedKeys);
		} else if (columnIndexes != null) {
			stmt = connection.prepareStatement(targetSql, columnIndexes);
		} else if (columnNames != null) {
			stmt = connection.prepareStatement(targetSql, columnNames);
		} else {
			stmt = connection.prepareStatement(targetSql, Statement.RETURN_GENERATED_KEYS);
		}

		return stmt;
	}

	@Override
	public void clearParameters() throws SQLException {
		params.clear();
	}

	@Override
	public boolean execute() throws SQLException {
		SqlType sqlType = judgeSqlType(sql);

		if (sqlType == SqlType.SELECT) {
			executeQuery();

			return true;
		} else if (sqlType == SqlType.INSERT || sqlType == SqlType.UPDATE || sqlType == SqlType.DELETE) {
			executeUpdate();

			return false;
		} else {
			throw new SQLException("only select, insert, update, delete sql is supported");
		}
	}

	@Override
	public ResultSet executeQuery() throws SQLException {
		checkClosed();

		ResultSet specRS = beforeQuery(sql);
		if (specRS != null) {
			this.results = specRS;
			this.updateCount = -1;
			attachedResultSets.add(specRS);

			return this.results;
		}

		RouterResult routerTarget = routingAndCheck(sql, getParams());

		rewriteAndMergeParms(routerTarget.getParams());

		ShardResultSet rs = new ShardResultSet();
		rs.setStatement(this);
		rs.setRouterTarget(routerTarget);

		attachedResultSets.add(rs);

		if (isSingleTarget(routerTarget)) {
			// if has only one sql,then serial execute it
			for (RouterTarget targetedSql : routerTarget.getSqls()) {
				for (String executableSql : targetedSql.getSqls()) {
					Connection conn = connection.getRealConnection(targetedSql.getDatabaseName(), autoCommit);
					PreparedStatement stmt = createPrepareStatement(conn, executableSql);
					actualStatements.add(stmt);
					setParams(stmt);

					rs.addResultSet(stmt.executeQuery());
				}
			}
		} else {
			// if has multiple sqls,then parallel execute them
			List<Callable<ResultSet>> tasks = new ArrayList<Callable<ResultSet>>();
			for (RouterTarget targetedSql : routerTarget.getSqls()) {
				for (String executableSql : targetedSql.getSqls()) {
					Connection conn = connection.getRealConnection(targetedSql.getDatabaseName(), autoCommit);
					PreparedStatement stmt = createPrepareStatement(conn, executableSql);
					actualStatements.add(stmt);
					setParams(stmt);

					tasks.add(new PreparedStatementExecuteQueryCallable(stmt, DaoContextHolder.getSqlName()));
				}
			}

			List<Future<ResultSet>> futures = SQLThreadPoolExecutor.getInstance().invokeSQLs(tasks);
			for (Future<ResultSet> f : futures) {
				try {
					rs.addResultSet(f.get());
				} catch (Exception e) {
					// normally can't be here!
					throw new SQLException(e);
				}
			}
		}

		this.results = rs;
		this.updateCount = -1;

		rs.init();

		return this.results;
	}

	@Override
	public int executeUpdate() throws SQLException {
		checkClosed();

		RouterResult routerTarget = routingAndCheck(sql, getParams());

		rewriteAndMergeParms(routerTarget.getParams());

		int affectedRows = 0;

		if (isSingleTarget(routerTarget)) {
			// if has only one sql,then serial execute it
			for (RouterTarget targetedSql : routerTarget.getSqls()) {
				for (String executableSql : targetedSql.getSqls()) {
					Connection conn = connection.getRealConnection(targetedSql.getDatabaseName(), autoCommit);
					PreparedStatement stmt = createPrepareStatement(conn, executableSql);
					actualStatements.add(stmt);
					setParams(stmt);

					affectedRows += stmt.executeUpdate();
					if(this.autoGeneratedKeys != -1){
						this.generatedKey = stmt.getGeneratedKeys();
					}
				}
			}
		} else {
			// if has multiple sqls,then parallel execute them
			List<Callable<UpdateResult>> tasks = new ArrayList<Callable<UpdateResult>>();
			for (RouterTarget targetedSql : routerTarget.getSqls()) {
				for (String executableSql : targetedSql.getSqls()) {
					Connection conn = connection.getRealConnection(targetedSql.getDatabaseName(), autoCommit);
					PreparedStatement stmt = createPrepareStatement(conn, executableSql);
					actualStatements.add(stmt);
					setParams(stmt);

					tasks.add(new PreparedStatementExecuteUpdateCallable(stmt, DaoContextHolder.getSqlName(),
							executableSql, this.autoGeneratedKeys));
				}
			}

			List<Future<UpdateResult>> futures = SQLThreadPoolExecutor.getInstance().invokeSQLs(tasks);
			for (Future<UpdateResult> f : futures) {
				try {
					UpdateResult updateResult = f.get();

					affectedRows += updateResult.getAffectedRows();
					if (updateResult.getGeneratedKey() != null) {
						this.generatedKey = updateResult.getGeneratedKey();
					}
				} catch (Exception e) {
					// normally can't be here
					throw new SQLException(e);
				}
			}
		}

		this.results = null;
		this.updateCount = affectedRows;

		return affectedRows;
	}

	public int getAutoGeneratedKeys() {
		return autoGeneratedKeys;
	}

	public int[] getColumnIndexes() {
		return columnIndexes;
	}

	public String[] getColumnNames() {
		return columnNames;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected List<Object> getParams() {
		Collections.sort(params, new Comparator() {

			@Override
			public int compare(Object o1, Object o2) {
				ParamContext context1 = (ParamContext) o1;
				ParamContext context2 = (ParamContext) o2;

				return context1.getIndex() < context2.getIndex() ? -1
						: (context1.getIndex() > context2.getIndex() ? 1 : 0);
			}

		});

		List<Object> parameters = new ArrayList<Object>();
		for (ParamContext context : params) {
			parameters.add(context.getValues()[0]);
		}

		return parameters;
	}

	public String getSql() {
		return sql;
	}

	protected void rewriteAndMergeParms(List<Object> newParams) {
		if (newParams == null) {
			return;
		}
		int index = 0;
		for (Object newParam : newParams) {
			ParamContext context = params.get(index++);
			context.getValues()[0] = newParam;
		}
	}

	@Override
	public void setArray(int parameterIndex, Array x) throws SQLException {
		params.add(new ArrayParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
		params.add(new AsciiParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
		params.add(new AsciiParamContext(parameterIndex, new Object[] { x, length }));
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
		params.add(new AsciiParamContext(parameterIndex, new Object[] { x, length }));
	}

	public void setAutoGeneratedKeys(int autoGeneratedKeys) {
		this.autoGeneratedKeys = autoGeneratedKeys;
	}

	@Override
	public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
		params.add(new BigDecimalParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
		params.add(new BinaryStreamParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
		params.add(new BinaryStreamParamContext(parameterIndex, new Object[] { x, length }));
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
		params.add(new BinaryStreamParamContext(parameterIndex, new Object[] { x, length }));
	}

	@Override
	public void setBlob(int parameterIndex, Blob x) throws SQLException {
		params.add(new BlobParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
		params.add(new BlobParamContext(parameterIndex, new Object[] { inputStream }));
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
		params.add(new BlobParamContext(parameterIndex, new Object[] { inputStream, length }));
	}

	@Override
	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		params.add(new BooleanParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setByte(int parameterIndex, byte x) throws SQLException {
		params.add(new ByteParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setBytes(int parameterIndex, byte[] x) throws SQLException {
		params.add(new ByteArrayParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
		params.add(new CharacterStreamParamContext(parameterIndex, new Object[] { reader }));
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
		params.add(new CharacterStreamParamContext(parameterIndex, new Object[] { reader, length }));
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
		params.add(new CharacterStreamParamContext(parameterIndex, new Object[] { reader, length }));
	}

	@Override
	public void setClob(int parameterIndex, Clob x) throws SQLException {
		params.add(new ClobParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setClob(int parameterIndex, Reader reader) throws SQLException {
		params.add(new ClobParamContext(parameterIndex, new Object[] { reader }));
	}

	@Override
	public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
		params.add(new ClobParamContext(parameterIndex, new Object[] { reader, length }));
	}

	public void setColumnIndexes(int[] columnIndexes) {
		if (columnIndexes != null && columnIndexes.length != 0) {
			this.columnIndexes = new int[columnIndexes.length];
			for (int i = 0; i < columnIndexes.length; i++) {
				this.columnIndexes[i] = columnIndexes[i];
			}
		}
	}

	public void setColumnNames(String[] columnNames) {
		if (columnNames != null && columnNames.length != 0) {
			this.columnNames = new String[columnNames.length];
			for (int i = 0; i < columnNames.length; i++) {
				this.columnNames[i] = columnNames[i];
			}
		}
	}

	@Override
	public void setDate(int parameterIndex, Date x) throws SQLException {
		params.add(new DateParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
		params.add(new DateParamContext(parameterIndex, new Object[] { x, cal }));
	}

	@Override
	public void setDouble(int parameterIndex, double x) throws SQLException {
		params.add(new DoubleParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setFloat(int parameterIndex, float x) throws SQLException {
		params.add(new FloatParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setInt(int parameterIndex, int x) throws SQLException {
		params.add(new IntParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setLong(int parameterIndex, long x) throws SQLException {
		params.add(new LongParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
		params.add(new NCharacterStreamParamContext(parameterIndex, new Object[] { value }));
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
		params.add(new NCharacterStreamParamContext(parameterIndex, new Object[] { value, length }));
	}

	@Override
	public void setNClob(int parameterIndex, NClob value) throws SQLException {
		params.add(new NClobParamContext(parameterIndex, new Object[] { value }));
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader) throws SQLException {
		params.add(new NClobParamContext(parameterIndex, new Object[] { reader }));
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
		params.add(new NClobParamContext(parameterIndex, new Object[] { reader, length }));
	}

	@Override
	public void setNString(int parameterIndex, String value) throws SQLException {
		params.add(new NStringParamContext(parameterIndex, new Object[] { value }));
	}

	@Override
	public void setNull(int parameterIndex, int sqlType) throws SQLException {
		params.add(new NullParamContext(parameterIndex, new Object[] { sqlType }));
	}

	@Override
	public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
		params.add(new NullParamContext(parameterIndex, new Object[] { sqlType, typeName }));
	}

	@Override
	public void setObject(int parameterIndex, Object x) throws SQLException {
		params.add(new ObjectParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
		params.add(new ObjectParamContext(parameterIndex, new Object[] { x, targetSqlType }));
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		params.add(new ObjectParamContext(parameterIndex, new Object[] { x, targetSqlType, scaleOrLength }));
	}

	protected void setParams(PreparedStatement stmt) throws SQLException {
		for (ParamContext paramContext : params) {
			paramContext.setParam(stmt);
		}
	}

	@Override
	public void setRef(int parameterIndex, Ref x) throws SQLException {
		params.add(new RefParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setRowId(int parameterIndex, RowId x) throws SQLException {
		params.add(new RowIdParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setShort(int parameterIndex, short x) throws SQLException {
		params.add(new ShortParamContext(parameterIndex, new Object[] { x }));
	}

	public void setSql(String sql) {
		this.sql = sql;
	}

	@Override
	public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
		params.add(new SQLXMLParamContext(parameterIndex, new Object[] { xmlObject }));
	}

	@Override
	public void setString(int parameterIndex, String x) throws SQLException {
		params.add(new StringParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setTime(int parameterIndex, Time x) throws SQLException {
		params.add(new TimeParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
		params.add(new TimeParamContext(parameterIndex, new Object[] { x, cal }));
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
		params.add(new TimestampParamContext(parameterIndex, new Object[] { x }));
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
		params.add(new TimestampParamContext(parameterIndex, new Object[] { x, cal }));
	}

	@Override
	public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
		params.add(new UnicodeStreamParamContext(parameterIndex, new Object[] { x, length }));
	}

	@Override
	public void setURL(int parameterIndex, URL x) throws SQLException {
		params.add(new URLParamContext(parameterIndex, new Object[] { x }));
	}

}
