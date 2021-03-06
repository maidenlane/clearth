/******************************************************************************
 * Copyright 2009-2019 Exactpro Systems Limited
 * https://www.exactpro.com
 * Build Software to Test Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.exactprosystems.clearth.utils.tabledata.typing.reader;

import com.exactprosystems.clearth.utils.Utils;
import com.exactprosystems.clearth.utils.sql.SQLUtils;
import com.exactprosystems.clearth.utils.tabledata.BasicTableDataReader;
import com.exactprosystems.clearth.utils.tabledata.RowsListFactory;
import com.exactprosystems.clearth.utils.tabledata.TableRow;
import com.exactprosystems.clearth.utils.tabledata.readers.DbRowFilter;
import com.exactprosystems.clearth.utils.tabledata.typing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.sql.Types.*;


public  class TypedDbDataReader extends BasicTableDataReader<TypedTableHeaderItem,Object, TypedTableData>
{
	private static final Logger logger = LoggerFactory.getLogger(TypedDbDataReader.class);

	protected final PreparedStatement statement;
	protected ResultSet resultSet;
	protected DbRowFilter dbRowFilter;
	protected String queryDescription;
	protected TypedTableHeader header;

	public TypedDbDataReader(PreparedStatement statement)
	{
		this.statement = statement;
	}

	@Override
	public boolean hasMoreData() throws IOException
	{
		if (resultSet == null)
			executeStatement();

		try
		{
			return resultSet.next();
		}
		catch (SQLException e)
		{
			throw new IOException("Error while getting next query result row", e);
		}
	}

	@Override
	public boolean filter() throws IOException
	{
		return dbRowFilter == null || dbRowFilter.filter(resultSet);
	}

	@Override
	protected TypedTableData createTableData(Set<TypedTableHeaderItem> header,
	                                         RowsListFactory<TypedTableHeaderItem, Object> rowsListFactory)
	{
		return new TypedTableData(header);
	}

	@Override
	public void close() throws IOException
	{
		Utils.closeResource(resultSet);
		Utils.closeResource(statement);
	}

	@Override
	protected Set<TypedTableHeaderItem> readHeader() throws IOException
	{
		Set<TypedTableHeaderItem> headerSet = new LinkedHashSet<>();

		if (resultSet == null)
			executeStatement();
		try
		{
			ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
			List<String> columnNames = new LinkedList<>(SQLUtils.getColumnNames(resultSetMetaData));

			for (int i = 1; i <= columnNames.size(); i++)
			{
				int typeIndex = resultSetMetaData.getColumnType(i);
				headerSet.add(new TypedTableHeaderItem(columnNames.get(i - 1), getType(typeIndex)));
			}
			return headerSet;
		}
		catch (SQLException e)
		{
			throw new IOException("Error while reading header from query result", e);
		}
	}

	@Override
	protected void fillRow(TableRow<TypedTableHeaderItem, Object> row) throws IOException
	{
		TypedTableRow typedTableRow = (TypedTableRow) row;
		header = (TypedTableHeader) row.getHeader();
		for (TypedTableHeaderItem head : header)
		{
			try
			{
				TableDataType type = head.getType();
				String headerName = head.getName();
				Object rsValue = getValueFromResultSet(head.getName(), resultSet);
				switch (type)
				{
					case INTEGER:
						typedTableRow.setInteger(headerName, (Integer) rsValue);
						break;
					case BOOLEAN:
						typedTableRow.setBoolean(headerName, (Boolean) rsValue);
						break;
					case FLOAT:
						typedTableRow.setFloat(headerName, (Float) rsValue);
						break;
					case DOUBLE:
						typedTableRow.setDouble(headerName, (Double) rsValue);
						break;
					case BYTE:
						Byte byteValue = null;
						if (rsValue instanceof Integer)
							byteValue = ((Integer) rsValue).byteValue();
						else if (rsValue instanceof Byte)
							byteValue = (Byte) rsValue;
						typedTableRow.setByte(headerName, byteValue);
						break;
					case SHORT:
						Short shortValue = null;
						if (rsValue instanceof Integer)
							shortValue = ((Integer) rsValue).shortValue();
						else if (rsValue instanceof Short)
							shortValue = (Short) rsValue;
						typedTableRow.setShort(headerName, shortValue);
						break;
					case LONG:
						typedTableRow.setLong(headerName, (Long) rsValue);
						break;
					case LOCALDATE:
						typedTableRow.setLocalDate(headerName, (Date) rsValue);
						break;
					case LOCALTIME:
						typedTableRow.setLocalTime(headerName, (LocalTime) rsValue);
						break;
					case BIGDECIMAL:
						typedTableRow.setBigDecimal(headerName, (BigDecimal) rsValue);
						break;
					case STRING:
						typedTableRow.setString(headerName, (String) rsValue);
						break;
					case LOCALDATETIME:
					default:
						if (rsValue != null)
							typedTableRow.setString(headerName, rsValue.toString());
				}
			}
			catch (SQLException e)
			{
				throw new IOException("Error while getting value for column '" + head.getName() + "'", e);
			}
		}
	}


	protected Object getValueFromResultSet(String tableHeader, ResultSet resultSet) throws SQLException
	{
		return resultSet.getObject(tableHeader);
	}

	private TableDataType getType(int index)
	{
		switch (index)
		{
			case INTEGER:
				return TableDataType.INTEGER;
			case CHAR:
			case VARCHAR:
			case LONGVARCHAR:
			case NCHAR:
			case NVARCHAR:
				return TableDataType.STRING;
			case BOOLEAN:
				return TableDataType.BOOLEAN;
			case REAL:
				return TableDataType.FLOAT;
			case FLOAT:
			case DOUBLE:
				return TableDataType.DOUBLE;
			case TINYINT:
				return TableDataType.BYTE;
			case SMALLINT:
				return TableDataType.SHORT;
			case BIGINT:
				return TableDataType.LONG;
			case DATE:
				return TableDataType.LOCALDATE;
			case TIME:
				return TableDataType.LOCALTIME;
			case TIMESTAMP:
				return TableDataType.LOCALDATETIME;
			case NUMERIC:
				return TableDataType.BIGDECIMAL;
		}
		return TableDataType.OBJECT;
	}

	protected void executeStatement() throws IOException
	{
		try
		{
			long startTime = System.currentTimeMillis();
			if (!statement.execute())
				throw new IOException("No data in DB result set. Probably an update query has been used or there is no result at all");
			logger.debug("Query {}has been executed in {} sec.", queryDescription != null ? queryDescription + " "
							: "",
					TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime));
			resultSet = statement.getResultSet();
		}
		catch (SQLException e)
		{
			throw new IOException("Error occurred while executing SQL query", e);
		}
	}
}