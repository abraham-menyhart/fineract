/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.core.service.database;

import org.apache.fineract.infrastructure.dataqueries.exception.DatatableNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.Objects.nonNull;
import static org.apache.fineract.infrastructure.core.service.database.PostgresSQLExceptionCode.RELATION_NOT_EXISTS;

@Component
public class PostgreSQLQueryService implements DatabaseQueryService {
    private final DatabaseTypeResolver databaseTypeResolver;

    @Autowired
    public PostgreSQLQueryService(DatabaseTypeResolver databaseTypeResolver) {
        this.databaseTypeResolver = databaseTypeResolver;
    }

    @Override
    public boolean isSupported() {
        return databaseTypeResolver.isPostgreSQL();
    }

    @Override
    public boolean isTablePresent(DataSource dataSource, String tableName) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer result = jdbcTemplate.queryForObject(format("SELECT COUNT(table_name) " + "FROM information_schema.tables "
                + "WHERE table_schema = 'public' " + "AND table_name = '%s';", tableName), Integer.class);
        return Objects.equals(result, 1);
    }

    @Override
    public SqlRowSet getTableColumns(DataSource dataSource, String tableName) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String sql = "SELECT attname AS COLUMN_NAME, not attnotnull AS IS_NULLABLE, atttypid::regtype  AS DATATYPE, attlen AS CHARACTER_MAXIMUM_LENGTH, attnum = 1 AS COLUMN_KEY FROM pg_attribute WHERE attrelid = '\""
                + tableName + "\"'::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum";
        final SqlRowSet columnDefinitions = query(jdbcTemplate, sql, tableName); // NOSONAR
        if (columnDefinitions.next()) {
            return columnDefinitions;
        } else {
            throw new IllegalArgumentException("Table " + tableName + " is not found");
        }
    }

    @NotNull
    private static SqlRowSet query(JdbcTemplate jdbcTemplate, String sql, String tableName) {
        try {
            return jdbcTemplate.queryForRowSet(sql);
        } catch (DataAccessException e) {
            if (isRelationNotExistsException(e)) {
                throw new DatatableNotFoundException(tableName);
            } else {
                throw e;
            }
        }
    }

    private static boolean isRelationNotExistsException(DataAccessException e) {
        if (e.getCause() instanceof SQLException sqlException) {
            return nonNull(sqlException.getSQLState()) && sqlException.getSQLState().equals(RELATION_NOT_EXISTS);
        }
        return false;
    }

    @Override
    public List<IndexDetail> getTableIndexes(DataSource dataSource, String tableName) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String sql = "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = '" + tableName + "'";
        final SqlRowSet indexDefinitions = jdbcTemplate.queryForRowSet(sql); // NOSONAR
        if (indexDefinitions.next()) {
            return DatabaseIndexMapper.getIndexDetails(indexDefinitions);
        } else {
            throw new IllegalArgumentException("Table " + tableName + " is not found");
        }
    }
}
