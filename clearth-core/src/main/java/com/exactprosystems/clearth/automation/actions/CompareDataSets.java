/******************************************************************************
 * Copyright 2009-2020 Exactpro Systems Limited
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

package com.exactprosystems.clearth.automation.actions;

import com.exactprosystems.clearth.ClearThCore;
import com.exactprosystems.clearth.automation.Action;
import com.exactprosystems.clearth.automation.GlobalContext;
import com.exactprosystems.clearth.automation.MatrixContext;
import com.exactprosystems.clearth.automation.StepContext;
import com.exactprosystems.clearth.automation.exceptions.FailoverException;
import com.exactprosystems.clearth.automation.exceptions.ResultException;
import com.exactprosystems.clearth.automation.report.Result;
import com.exactprosystems.clearth.automation.report.results.CloseableContainerResult;
import com.exactprosystems.clearth.automation.report.results.ContainerResult;
import com.exactprosystems.clearth.automation.report.results.CsvContainerResult;
import com.exactprosystems.clearth.automation.report.results.DefaultResult;
import com.exactprosystems.clearth.utils.*;
import com.exactprosystems.clearth.utils.inputparams.InputParamsHandler;
import com.exactprosystems.clearth.utils.inputparams.InputParamsUtils;
import com.exactprosystems.clearth.utils.scripts.ScriptResult;
import com.exactprosystems.clearth.utils.scripts.ScriptUtils;
import com.exactprosystems.clearth.utils.sql.DefaultSQLValueTransformer;
import com.exactprosystems.clearth.utils.sql.ParametrizedQuery;
import com.exactprosystems.clearth.utils.sql.SQLUtils;
import com.exactprosystems.clearth.utils.tabledata.BasicTableDataReader;
import com.exactprosystems.clearth.utils.tabledata.TableRow;
import com.exactprosystems.clearth.utils.tabledata.comparison.*;
import com.exactprosystems.clearth.utils.tabledata.comparison.rowsComparators.DefaultStringTableRowsComparator;
import com.exactprosystems.clearth.utils.tabledata.comparison.rowsComparators.NumericStringTableRowsComparator;
import com.exactprosystems.clearth.utils.tabledata.readers.CsvDataReader;
import com.exactprosystems.clearth.utils.tabledata.readers.DbDataReader;
import com.exactprosystems.clearth.utils.tabledata.rowMatchers.DefaultStringTableRowMatcher;
import com.exactprosystems.clearth.utils.tabledata.rowMatchers.NumericStringTableRowMatcher;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.StringUtils;

import java.io.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CompareDataSets extends Action
{
	public static final String EXPECTED_FORMAT = "ExpectedFormat", ACTUAL_FORMAT = "ActualFormat",
			EXPECTED_SOURCE = "ExpectedSource", ACTUAL_SOURCE = "ActualSource",
			KEY_COLUMNS = "KeyColumns", NUMERIC_COLUMNS = "NumericColumns",
			CHECK_DUPLICATES = "CheckDuplicates";
	
	// Formats of sources available for comparison
	public static final String FORMAT_DB_QUERY = "Query", FORMAT_DB_QUERY_FILE = "QueryFile", FORMAT_CSV_FILE = "CsvFile",
			FORMAT_SCRIPT = "Script", FORMAT_SCRIPT_FILE = "ScriptFile",
			
			CONTAINER_PASSED = "Passed rows",
			CONTAINER_FAILED = "Failed rows",
			CONTAINER_NOT_FOUND = "Not found rows",
			CONTAINER_EXTRA = "Extra rows";
	
	// Matrix params names for additional ones to initialize data readers (0 - common, 1 - expected, 2 - actual)
	public static final String[] CSV_DELIMITER = new String[] { "CsvDelimiterCommon", "CsvDelimiterExpected", "CsvDelimiterActual" },
			SCRIPT_SHELL_NAME = new String[] { "ScriptShellNameCommon", "ScriptShellNameExpected", "ScriptShellNameActual" },
			SCRIPT_SHELL_OPTION = new String[] { "ScriptShellOptionCommon", "ScriptShellOptionExpected", "ScriptShellOptionActual" },
			SCRIPT_FILE_PARAMS = new String[] { "ScriptFileParamsCommon", "ScriptFileParamsExpected", "ScriptFileParamsActual" };
	// ... and keys in mapping for them
	protected final String ADDITIONAL_CSV_DELIMITER = "CsvDelimiter", ADDITIONAL_SCRIPT_SHELL_NAME = "ScriptShellName",
			ADDITIONAL_SCRIPT_SHELL_OPTION = "ScriptShellOption", ADDITIONAL_SCRIPT_FILE_PARAMS = "ScriptFileParams";
	
	protected Map<String, BigDecimal> numericColumns = null;
	protected IValueTransformer bdValueTransformer = null;
	
	private KeyColumnsRowsCollector keyColumnsRowsCollector = null;
	
	@Override
	protected Result run(StepContext stepContext, MatrixContext matrixContext, GlobalContext globalContext)
			throws ResultException, FailoverException
	{
		getLogger().debug("Initializing special action parameters");
		InputParamsHandler handler = new InputParamsHandler(inputParams);
		initParameters(globalContext, handler);
		Set<String> keyColumns = handler.getSet(KEY_COLUMNS, ",");
		String expectedFormat = handler.getRequiredString(EXPECTED_FORMAT), expectedSource = handler.getRequiredString(EXPECTED_SOURCE),
				actualFormat = handler.getRequiredString(ACTUAL_FORMAT), actualSource = handler.getRequiredString(ACTUAL_SOURCE);
		boolean checkDuplicates = handler.getBoolean(CHECK_DUPLICATES, false);
		handler.check();
		
		BasicTableDataReader<String, String, ?> expectedReader = null, actualReader = null;
		try
		{
			getLogger().debug("Preparing data readers");
			expectedReader = getTableDataReader(expectedFormat, expectedSource,
					getAdditionalParamsToInitReader(expectedFormat, true), true);
			actualReader = getTableDataReader(actualFormat, actualSource,
					getAdditionalParamsToInitReader(actualFormat, false), false);
			getLogger().debug("Data readers are ready. Starting comparison");
			return compareTables(expectedReader, actualReader, keyColumns, checkDuplicates);
		}
		catch (Exception e)
		{
			if (e instanceof ResultException)
				throw (ResultException)e;
			throw ResultException.failed("Error while comparing data.", e);
		}
		finally
		{
			getLogger().debug("Closing used resources");
			// Readers may be not closed by comparator (e.g. due to some exception)
			Utils.closeResource(expectedReader);
			Utils.closeResource(actualReader);
		}
	}
	
	protected void initParameters(GlobalContext globalContext, InputParamsHandler handler)
	{
		numericColumns = getNumericColumns(handler.getSet(NUMERIC_COLUMNS, ","));
	}
	
	protected Map<String, Object> getAdditionalParamsToInitReader(String formatName, boolean forExpectedData)
	{
		Map<String, Object> additionalParams = new HashMap<>();
		if (FORMAT_CSV_FILE.equals(formatName) || FORMAT_SCRIPT.equals(formatName) || FORMAT_SCRIPT_FILE.equals(formatName))
		{
			additionalParams.put(ADDITIONAL_CSV_DELIMITER, getCsvDelimiter(forExpectedData));
			if (FORMAT_SCRIPT.equals(formatName))
			{
				additionalParams.put(ADDITIONAL_SCRIPT_SHELL_NAME, getScriptShellName(forExpectedData));
				additionalParams.put(ADDITIONAL_SCRIPT_SHELL_OPTION, getScriptShellOption(forExpectedData));
			}
			else if (FORMAT_SCRIPT_FILE.equals(formatName))
				additionalParams.put(ADDITIONAL_SCRIPT_FILE_PARAMS, getScriptFileParameters(forExpectedData));
		}
		return additionalParams;
	}
	
	protected char getCsvDelimiter(boolean forExpectedData)
	{
		String paramToUse = inputParams.containsKey(CSV_DELIMITER[0]) ? CSV_DELIMITER[0] : CSV_DELIMITER[forExpectedData ? 1 : 2],
				delimiter = InputParamsUtils.getStringOrDefault(inputParams, paramToUse, ",").replace("\\t", "\t");
		if (delimiter.length() == 1)
			return delimiter.charAt(0);
		else
			throw ResultException.failed("CSV delimiter specified in parameter '" + paramToUse + "' has invalid format: it should be 1 character in length.");
	}
	
	protected String getScriptShellName(boolean forExpectedData)
	{
		return InputParamsUtils.getStringOrDefault(inputParams, inputParams.containsKey(SCRIPT_SHELL_NAME[0]) ?
				SCRIPT_SHELL_NAME[0] : SCRIPT_SHELL_NAME[forExpectedData ? 1 : 2], "bash");
	}
	
	protected String getScriptShellOption(boolean forExpectedData)
	{
		return InputParamsUtils.getStringOrDefault(inputParams, inputParams.containsKey(SCRIPT_SHELL_OPTION[0]) ?
				SCRIPT_SHELL_OPTION[0] : SCRIPT_SHELL_OPTION[forExpectedData ? 1 : 2], "-c");
	}
	
	protected String getScriptFileParameters(boolean forExpectedData)
	{
		return InputParamsUtils.getStringOrDefault(inputParams, inputParams.containsKey(SCRIPT_FILE_PARAMS[0]) ?
				SCRIPT_FILE_PARAMS[0] : SCRIPT_FILE_PARAMS[forExpectedData ? 1 : 2], "");
	}
	
	
	protected Result compareTables(BasicTableDataReader<String, String, ?> expectedReader,
			BasicTableDataReader<String, String, ?> actualReader, Set<String> keyColumns, boolean checkDuplicates) throws Exception
	{
		ContainerResult result = null;
		DefaultStringTableRowMatcher rowMatcher = null;
		if (!keyColumns.isEmpty())
		{
			rowMatcher = createTableRowMatcher(keyColumns);
			if (checkDuplicates)
				keyColumnsRowsCollector = createKeyColumnsRowsCollector(keyColumns);
		}
		
		try (TableDataComparator<String, String> comparator = createTableDataComparator(expectedReader, actualReader,
				rowMatcher, createTableRowsComparator()))
		{
			if (!comparator.hasMoreRows())
				return DefaultResult.passed("Both datasets are empty. Nothing to compare.");
			
			long comparisonStartTime = System.currentTimeMillis();
			result = createComparisonContainerResult();
			int rowsCount = 0, passedRowsCount = 0;
			do
			{
				rowsCount++;
				RowComparisonData<String, String> compData = comparator.compareRows();
				if (compData.isSuccess())
					passedRowsCount++;
				
				// Pass comparison errors to the logs if exist
				List<String> compErrors = compData.getErrors();
				if (getLogger().isWarnEnabled() && !compErrors.isEmpty())
				{
					LineBuilder errMsgBuilder = new LineBuilder();
					errMsgBuilder.add("Comparison error(s) at line #").add(rowsCount).append(":");
					compErrors.forEach(currentError -> errMsgBuilder.add("* ").append(currentError));
					getLogger().warn(errMsgBuilder.toString());
				}
				
				processCurrentRowResult(compData, getRowContainerName(compData, rowsCount), result, keyColumnsRowsCollector,
						comparator.getCurrentRow(), rowMatcher);
				afterRow(rowsCount, passedRowsCount);
			}
			while (comparator.hasMoreRows());
			
			getLogger().debug("Comparison finished in {} sec. Processed {} rows: {} passed / {} failed",
					TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - comparisonStartTime), rowsCount,
					passedRowsCount, rowsCount - passedRowsCount);
			
			return result;
		}
		finally
		{
			if (result instanceof AutoCloseable)
				Utils.closeResource((AutoCloseable)result);
			Utils.closeResource(keyColumnsRowsCollector);
		}
	}
	
	protected ContainerResult createComparisonContainerResult()
	{
		ContainerResult result = CloseableContainerResult.createPlainResult(null);
		// Creating containers firstly to have them in expected order
		result.addContainer(CONTAINER_PASSED, createComparisonNestedResult(CONTAINER_PASSED), true);
		result.addContainer(CONTAINER_FAILED, createComparisonNestedResult(CONTAINER_FAILED), true);
		result.addContainer(CONTAINER_NOT_FOUND, createComparisonNestedResult(CONTAINER_NOT_FOUND), true);
		result.addContainer(CONTAINER_EXTRA, createComparisonNestedResult(CONTAINER_EXTRA), true);
		return result;
	}
	
	protected ContainerResult createComparisonNestedResult(String header)
	{
		return CsvContainerResult.createPlainResult(header.toLowerCase().replace(" ", "_"));
	}
	
	protected TableDataComparator<String, String> createTableDataComparator(BasicTableDataReader<String, String, ?> expectedReader,
			BasicTableDataReader<String, String, ?> actualReader, DefaultStringTableRowMatcher rowMatcher,
			DefaultStringTableRowsComparator rowsComparator) throws IOException
	{
		return rowMatcher == null ? new StringTableDataComparator(expectedReader, actualReader, rowsComparator)
				: new IndexedStringTableDataComparator(expectedReader, actualReader, rowMatcher, rowsComparator);
	}
	
	protected void processCurrentRowResult(RowComparisonData<String, String> compData, String rowName, ContainerResult result,
			KeyColumnsRowsCollector keyColumnsRowsCollector, TableRow<String, String> currentRow,
			DefaultStringTableRowMatcher rowMatcher) throws IOException
	{
		if (keyColumnsRowsCollector != null && rowMatcher != null)
		{
			String primaryKey = rowMatcher.createPrimaryKey(currentRow),
					originalRowName = keyColumnsRowsCollector.checkForDuplicatedRow(primaryKey, currentRow, rowMatcher::matchBySecondaryKey);
			if (StringUtils.isNotBlank(originalRowName))
			{
				addDuplicatedRowResult(compData, rowName, originalRowName, result);
				return;
			}
			// Add current row to collector only if it's not a duplicated one
			keyColumnsRowsCollector.addRow(rowName, primaryKey, currentRow);
		}
		addRowComparisonResult(createBlockResult(compData, rowName), result, compData.getResultType());
	}
	
	protected void addDuplicatedRowResult(RowComparisonData<String, String> compData, String rowName, String originalRowName,
			ContainerResult result)
	{
		Result rowResult = createBlockResult(compData, rowName + " (duplicate of row named '" + originalRowName + "')");
		rowResult.setSuccess(false);
		
		RowComparisonResultType compResultType = compData.getResultType();
		if (compResultType == RowComparisonResultType.PASSED)
			compResultType = RowComparisonResultType.FAILED;
		addRowComparisonResult(rowResult, result, compResultType);
	}
	
	protected void addRowComparisonResult(Result rowResult, ContainerResult result, RowComparisonResultType compResultType)
	{
		String nestedName = getResultTypeName(compResultType);
		ContainerResult nestedResult = result.getContainer(nestedName);
		if (nestedResult == null)
		{
			nestedResult = createComparisonNestedResult(nestedName);
			result.addContainer(nestedName, nestedResult, true);
		}
		nestedResult.addDetail(rowResult);
	}
	
	protected Result createBlockResult(RowComparisonData<String, String> compData, String headerMessage)
	{
		ContainerResult blockResult = ContainerResult.createBlockResult(headerMessage);
		blockResult.addDetail(compData.toDetailedResult());
		return blockResult;
	}
	
	protected String getRowContainerName(RowComparisonData<String, String> compData, int rowsCount)
	{
		return "Row #" + rowsCount;
	}
	
	protected void afterRow(int rowNumber, int passedRowsCount)
	{
		if (rowNumber > 0 && (rowNumber <= 10000 && rowNumber % 1000 == 0 || rowNumber <= 100000 && rowNumber % 10000 == 0
				|| rowNumber <= 1000000 && rowNumber % 100000 == 0 || rowNumber % 1000000 == 0))
			getLogger().debug("Compared {} rows, {} passed", rowNumber, passedRowsCount);
		
		// Check if action has been interrupted (i.e. scheduler stopped) and should be finished
		if (rowNumber % 1000 == 0 && Thread.currentThread().isInterrupted())
			throw ResultException.failed("Action execution has been interrupted.");
	}
	
	
	protected BasicTableDataReader<String, String, ?> getTableDataReader(String formatName, String source,
			Map<String, Object> additionalParams, boolean forExpectedData) throws Exception
	{
		switch (formatName)
		{
			case FORMAT_DB_QUERY:
			case FORMAT_DB_QUERY_FILE:
				return createDbDataReader(source, formatName.equals(FORMAT_DB_QUERY_FILE), forExpectedData);
			case FORMAT_CSV_FILE:
				CsvDataReader csvFileReader = new CsvDataReader(new BufferedReader(new FileReader(ClearThCore.rootRelative(source))));
				csvFileReader.setDelimiter((char)additionalParams.get(ADDITIONAL_CSV_DELIMITER));
				return csvFileReader;
			case FORMAT_SCRIPT:
			case FORMAT_SCRIPT_FILE:
				String scriptResult = formatName.equals(FORMAT_SCRIPT) ?
						executeScriptCommands(source, (String)additionalParams.get(ADDITIONAL_SCRIPT_SHELL_NAME),
								(String)additionalParams.get(ADDITIONAL_SCRIPT_SHELL_OPTION), forExpectedData)
						: executeScriptFile(ClearThCore.rootRelative(source),
								(String)additionalParams.get(ADDITIONAL_SCRIPT_FILE_PARAMS), forExpectedData);
				CsvDataReader scriptResultReader = new CsvDataReader(new StringReader(scriptResult));
				scriptResultReader.setDelimiter((char)additionalParams.get(ADDITIONAL_CSV_DELIMITER));
				return scriptResultReader;
			default:
				return getCustomTableDataReader(formatName, source, additionalParams, forExpectedData);
		}
	}
	
	protected BasicTableDataReader<String, String, ?> getCustomTableDataReader(String formatName, String source,
			Map<String, Object> additionalParams, boolean forExpectedData) throws Exception
	{
		throw new IllegalArgumentException("Unsupported comparison format '" + formatName + "'. Acceptable ones are: "
				+ Stream.of(FORMAT_DB_QUERY, FORMAT_DB_QUERY_FILE, FORMAT_CSV_FILE, FORMAT_SCRIPT, FORMAT_SCRIPT_FILE)
						.collect(Collectors.joining(", ", "'", "'")) + ".");
	}
	
	protected DbDataReader createDbDataReader(String source, boolean isFile, boolean forExpectedData)
			throws IOException, SQLException, SettingsException
	{
		ParametrizedQuery query = !isFile ? SQLUtils.parseSQLTemplate(source)
				: SQLUtils.parseSQLTemplate(new File(ClearThCore.rootRelative(source)));
		PreparedStatement statement = query.createPreparedStatement(getDbConnection(forExpectedData), inputParams);
		
		DbDataReader reader = new DbDataReader(statement);
		reader.setValueTransformer(createSQLValueTransformer());
		reader.setQueryDescription("for " + (forExpectedData ? "expected" : "actual") + " data");
		return reader;
	}
	
	protected String executeScriptCommands(String commands, String shellName, String shellOption, boolean forExpectedData) throws IOException
	{
		return processScriptResult(ScriptUtils.executeScript(commands, shellName, shellOption, null, null), forExpectedData);
	}
	
	protected String executeScriptFile(String scriptPath, String args, boolean forExpectedData) throws IOException
	{
		return processScriptResult(ScriptUtils.executeScript(scriptPath + " " + args, null), forExpectedData);
	}
	
	protected String processScriptResult(ScriptResult scriptResult, boolean forExpectedData)
	{
		if (scriptResult.result == 0)
			return scriptResult.outStr;
		
		LineBuilder builder = new LineBuilder();
		builder.add("Error occurred while executing ").add(forExpectedData ? "expected" : "actual").append(" script.");
		builder.add("Exit code: ").append(scriptResult.result);
		builder.add("Output: ").append(scriptResult.outStr);
		builder.add("Error text: ").append(scriptResult.errStr);
		throw ResultException.failed(builder.toString());
	}
	
	
	protected Map<String, BigDecimal> getNumericColumns(Set<String> columnsWithScales)
	{
		if (CollectionUtils.isEmpty(columnsWithScales))
			return null;
		
		bdValueTransformer = createBigDecimalValueTransformer();
		Map<String, BigDecimal> numericColumns = new HashMap<>();
		for (String columnWithScale : columnsWithScales)
		{
			String[] columnAndScale = columnWithScale.split(":", 2);
			String column = columnAndScale[0];
			BigDecimal precision = BigDecimal.ZERO;
			if (columnAndScale.length == 2)
			{
				try
				{
					precision = new BigDecimal(bdValueTransformer != null ?
							bdValueTransformer.transform(columnAndScale[1]) : columnAndScale[1]);
				}
				catch (Exception e)
				{
					throw ResultException.failed("Numeric column '" + column + "' with specified precision '"
							+ columnAndScale[1] + "' couldn't be obtained due to error.", e);
				}
			}
			numericColumns.put(column, precision);
		}
		return numericColumns;
	}
	
	protected DefaultStringTableRowMatcher createTableRowMatcher(Set<String> keyColumns)
	{
		return MapUtils.isEmpty(numericColumns) ? new DefaultStringTableRowMatcher(keyColumns)
				:  new NumericStringTableRowMatcher(keyColumns, numericColumns, bdValueTransformer);
	}
	
	protected DefaultStringTableRowsComparator createTableRowsComparator()
	{
		return MapUtils.isEmpty(numericColumns) ? new DefaultStringTableRowsComparator()
				: new NumericStringTableRowsComparator(numericColumns, bdValueTransformer);
	}
	
	protected IValueTransformer createBigDecimalValueTransformer()
	{
		return new BigDecimalValueTransformer();
	}
	
	protected IValueTransformer createSQLValueTransformer()
	{
		return new DefaultSQLValueTransformer();
	}
	
	protected Connection getDbConnection(boolean forExpectedData) throws SQLException, SettingsException
	{
		// FIXME: here we need to obtain DB connection by using Core capabilities, once this is implemented
		throw new SQLException("Could not initialize database connection. Please contact developers.");
	}
	
	protected KeyColumnsRowsCollector createKeyColumnsRowsCollector(Set<String> keyColumns) throws IOException
	{
		return new KeyColumnsRowsCollector(keyColumns);
	}
	
	
	protected String getResultTypeName(RowComparisonResultType result)
	{
		switch (result)
		{
			case PASSED:
				return CONTAINER_PASSED;
			case FAILED:
				return CONTAINER_FAILED;
			case NOT_FOUND:
				return CONTAINER_NOT_FOUND;
			case EXTRA:
				return CONTAINER_EXTRA;
			default:
				return null;
		}
	}
	
	@Override
	public void dispose()
	{
		super.dispose();
		
		numericColumns = null;
		bdValueTransformer = null;
		keyColumnsRowsCollector = null;
	}
}
