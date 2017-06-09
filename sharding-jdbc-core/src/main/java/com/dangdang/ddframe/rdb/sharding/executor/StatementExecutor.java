/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.executor;

import com.codahale.metrics.Timer.Context;
import com.dangdang.ddframe.rdb.sharding.constant.SQLType;
import com.dangdang.ddframe.rdb.sharding.executor.event.EventExecutionType;
import com.dangdang.ddframe.rdb.sharding.executor.event.ExecutionEventBus;
import com.dangdang.ddframe.rdb.sharding.executor.event.DMLAbstractExecutionEvent;
import com.dangdang.ddframe.rdb.sharding.executor.event.DQLAbstractExecutionEvent;
import com.dangdang.ddframe.rdb.sharding.executor.event.AbstractExecutionEvent;
import com.dangdang.ddframe.rdb.sharding.metrics.MetricsContext;
import com.dangdang.ddframe.rdb.sharding.routing.SQLExecutionUnit;
import com.google.common.base.Optional;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * 多线程执行静态语句对象请求的执行器.
 * 
 * @author gaohongtao
 * @author caohao
 * @author zhangliang
 */
@RequiredArgsConstructor
public final class StatementExecutor {
    
    private final ExecutorEngine executorEngine;
    
    private final SQLType sqlType;
    
    private final Map<SQLExecutionUnit, Statement> statements;
    
    /**
     * 执行SQL查询.
     * 
     * @return 结果集列表
     */
    public List<ResultSet> executeQuery() {
        Context context = MetricsContext.start("ShardingStatement-executeQuery");
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        List<ResultSet> result;
        try {
            if (1 == statements.size()) {
                Entry<SQLExecutionUnit, Statement> entry = statements.entrySet().iterator().next();
                return Collections.singletonList(executeQuery(entry.getKey(), entry.getValue(), isExceptionThrown, dataMap));
            }
            result = executorEngine.execute(statements.entrySet(), new ExecuteUnit<Entry<SQLExecutionUnit, Statement>, ResultSet>() {
                
                @Override
                public ResultSet execute(final Entry<SQLExecutionUnit, Statement> input) throws Exception {
                    synchronized (input.getValue().getConnection()) {
                        return executeQuery(input.getKey(), input.getValue(), isExceptionThrown, dataMap);
                    }
                }
            });
        } finally {
            MetricsContext.stop(context);
        }
        return result;
    }
    
    private ResultSet executeQuery(final SQLExecutionUnit sqlExecutionUnit, final Statement statement, final boolean isExceptionThrown, final Map<String, Object> dataMap) {
        ResultSet result;
        ExecutorExceptionHandler.setExceptionThrown(isExceptionThrown);
        ExecutorDataMap.setDataMap(dataMap);
        AbstractExecutionEvent event = getExecutionEvent(sqlExecutionUnit);
        ExecutionEventBus.getInstance().post(event);
        try {
            result = statement.executeQuery(sqlExecutionUnit.getSql());
        } catch (final SQLException ex) {
            event.setEventExecutionType(EventExecutionType.EXECUTE_FAILURE);
            event.setException(Optional.of(ex));
            ExecutionEventBus.getInstance().post(event);
            ExecutorExceptionHandler.handleException(ex);
            return null;
        }
        event.setEventExecutionType(EventExecutionType.EXECUTE_SUCCESS);
        ExecutionEventBus.getInstance().post(event);
        return result;
    }
    
    /**
     * 执行SQL更新.
     * 
     * @return 更新数量
     */
    public int executeUpdate() {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql);
            }
        });
    }
    
    public int executeUpdate(final int autoGeneratedKeys) {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, autoGeneratedKeys);
            }
        });
    }
    
    public int executeUpdate(final int[] columnIndexes) {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, columnIndexes);
            }
        });
    }
    
    public int executeUpdate(final String[] columnNames) {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, columnNames);
            }
        });
    }
    
    private int executeUpdate(final Updater updater) {
        Context context = MetricsContext.start("ShardingStatement-executeUpdate");
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        try {
            if (1 == statements.size()) {
                Entry<SQLExecutionUnit, Statement> entry = statements.entrySet().iterator().next();
                return executeUpdate(updater, entry.getKey(), entry.getValue(), isExceptionThrown, dataMap);
            }
            return executorEngine.execute(statements.entrySet(), new ExecuteUnit<Entry<SQLExecutionUnit, Statement>, Integer>() {
                
                @Override
                public Integer execute(final Entry<SQLExecutionUnit, Statement> input) throws Exception {
                    synchronized (input.getValue().getConnection()) {
                        return executeUpdate(updater, input.getKey(), input.getValue(), isExceptionThrown, dataMap);
                    }
                }
            }, new MergeUnit<Integer, Integer>() {
                
                @Override
                public Integer merge(final List<Integer> results) {
                    if (null == results) {
                        return 0;
                    }
                    int result = 0;
                    for (int each : results) {
                        result += each;
                    }
                    return result;
                }
            });
        } finally {
            MetricsContext.stop(context);
        }
    }
    
    private int executeUpdate(final Updater updater, final SQLExecutionUnit sqlExecutionUnit, final Statement statement, final boolean isExceptionThrown, final Map<String, Object> dataMap) {
        int result;
        ExecutorExceptionHandler.setExceptionThrown(isExceptionThrown);
        ExecutorDataMap.setDataMap(dataMap);
        AbstractExecutionEvent event = getExecutionEvent(sqlExecutionUnit);
        ExecutionEventBus.getInstance().post(event);
        try {
            result = updater.executeUpdate(statement, sqlExecutionUnit.getSql());
        } catch (final SQLException ex) {
            event.setEventExecutionType(EventExecutionType.EXECUTE_FAILURE);
            event.setException(Optional.of(ex));
            ExecutionEventBus.getInstance().post(event);
            ExecutorExceptionHandler.handleException(ex);
            return 0;
        }
        event.setEventExecutionType(EventExecutionType.EXECUTE_SUCCESS);
        ExecutionEventBus.getInstance().post(event);
        return result;
    }
    
    /**
     * 执行SQL请求.
     * 
     * @return true表示执行DQL语句, false表示执行的DML语句
     */
    public boolean execute() {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql);
            }
        });
    }
    
    public boolean execute(final int autoGeneratedKeys) {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, autoGeneratedKeys);
            }
        });
    }
    
    public boolean execute(final int[] columnIndexes) {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, columnIndexes);
            }
        });
    }
    
    public boolean execute(final String[] columnNames) {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, columnNames);
            }
        });
    }
    
    private boolean execute(final Executor executor) {
        Context context = MetricsContext.start("ShardingStatement-execute");
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        try {
            if (1 == statements.size()) {
                Entry<SQLExecutionUnit, Statement> entry = statements.entrySet().iterator().next();
                return execute(executor, entry.getKey(), entry.getValue(), isExceptionThrown, dataMap);
            }
            List<Boolean> result = executorEngine.execute(statements.entrySet(), new ExecuteUnit<Entry<SQLExecutionUnit, Statement>, Boolean>() {
        
                @Override
                public Boolean execute(final Entry<SQLExecutionUnit, Statement> input) throws Exception {
                    synchronized (input.getValue().getConnection()) {
                        return StatementExecutor.this.execute(executor, input.getKey(), input.getValue(), isExceptionThrown, dataMap);
                    }
                }
            });
            return (null == result || result.isEmpty()) ? false : result.get(0);
        } finally {
            MetricsContext.stop(context);
        }
    }
    
    private boolean execute(final Executor executor, final SQLExecutionUnit sqlExecutionUnit, final Statement statement, final boolean isExceptionThrown, final Map<String, Object> dataMap) {
        boolean result;
        ExecutorExceptionHandler.setExceptionThrown(isExceptionThrown);
        ExecutorDataMap.setDataMap(dataMap);
        AbstractExecutionEvent event = getExecutionEvent(sqlExecutionUnit);
        ExecutionEventBus.getInstance().post(event);
        try {
            result = executor.execute(statement, sqlExecutionUnit.getSql());
        } catch (final SQLException ex) {
            event.setEventExecutionType(EventExecutionType.EXECUTE_FAILURE);
            event.setException(Optional.of(ex));
            ExecutionEventBus.getInstance().post(event);
            ExecutorExceptionHandler.handleException(ex);
            return false;
        }
        event.setEventExecutionType(EventExecutionType.EXECUTE_SUCCESS);
        ExecutionEventBus.getInstance().post(event);
        return result;
    }
    
    private AbstractExecutionEvent getExecutionEvent(final SQLExecutionUnit sqlExecutionUnit) {
        if (SQLType.SELECT == sqlType) {
            return new DQLAbstractExecutionEvent(sqlExecutionUnit.getDataSource(), sqlExecutionUnit.getSql());
        }
        return new DMLAbstractExecutionEvent(sqlExecutionUnit.getDataSource(), sqlExecutionUnit.getSql());
    }
    
    private interface Updater {
        
        int executeUpdate(Statement statement, String sql) throws SQLException;
    }
    
    private interface Executor {
        
        boolean execute(Statement statement, String sql) throws SQLException;
    }
}
