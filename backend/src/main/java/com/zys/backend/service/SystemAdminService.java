package com.zys.backend.service;

import cn.hutool.core.util.StrUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.manager.vector.VectorClient;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员系统工具：数据库表数据、Redis 键与运行状态。
 */
@Service
public class SystemAdminService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REDIS_KEYS = 500;
    private static final int MAX_REDIS_COLLECTION_ITEMS = 100;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private DataSource dataSource;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private SystemSettingsService settingsService;

    @Resource
    private CosStorageManager cosStorageManager;

    @Resource
    private VectorClient vectorClient;

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", databaseStatus());
        result.put("redis", redisStatus());
        Map<String, Object> cos = new LinkedHashMap<>();
        Map<String, Object> settings = settingsService.getPublicSettings();
        boolean configured = Boolean.TRUE.equals(settings.get("cosSecretIdConfigured"))
                && Boolean.TRUE.equals(settings.get("cosSecretKeyConfigured"))
                && StrUtil.isNotBlank((String) settings.get("cosBucket"));
        cos.put("configured", configured);
        cos.put("connected", configured && cosStorageManager.testConnection());
        cos.put("host", settings.get("cosHost"));
        cos.put("region", settings.get("cosRegion"));
        cos.put("bucket", settings.get("cosBucket"));
        result.put("cos", cos);
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("connected", vectorClient.isAvailable());
        vector.put("storage", "本机 Chroma");
        result.put("vector", vector);
        result.put("settings", settings);
        return result;
    }

    public List<Map<String, Object>> listTables() {
        List<Map<String, Object>> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            try (ResultSet resultSet = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (resultSet.next()) {
                    String name = resultSet.getString("TABLE_NAME");
                    TableMeta tableMeta = readTableMeta(connection, name);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("primaryKeys", tableMeta.primaryKeys);
                    item.put("columnCount", tableMeta.columns.size());
                    item.put("rowCount", countRows(tableMeta));
                    tables.add(item);
                }
            }
        } catch (SQLException e) {
            throw databaseError("读取数据库表失败", e);
        }
        tables.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
        return tables;
    }

    public Map<String, Object> getRows(String tableName, int page, int pageSize) {
        TableMeta table = requireTable(tableName);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        int offset = (safePage - 1) * safeSize;
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(quote(table.name));
        if (!table.primaryKeys.isEmpty()) {
            sql.append(" ORDER BY ")
                    .append(table.primaryKeys.stream().map(this::quote)
                            .collect(Collectors.joining(", ")));
        }
        sql.append(" LIMIT ? OFFSET ?");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), safeSize, offset)
                .stream()
                .map(this::jsonSafeRow)
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", table.name);
        result.put("columns", table.columns);
        result.put("primaryKeys", table.primaryKeys);
        result.put("rows", rows);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        result.put("total", countRows(table));
        return result;
    }

    public int insertRow(String tableName, Map<String, Object> values) {
        TableMeta table = requireTable(tableName);
        Map<String, Object> safeValues = filterValues(table, values, true);
        if (safeValues.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "没有可新增的字段");
        }
        String columns = safeValues.keySet().stream().map(this::quote)
                .collect(Collectors.joining(", "));
        String placeholders = String.join(", ",
                Collections.nCopies(safeValues.size(), "?"));
        String sql = "INSERT INTO " + quote(table.name) + " (" + columns + ") VALUES (" +
                placeholders + ")";
        return jdbcTemplate.update(sql, safeValues.values().toArray());
    }

    public int updateRow(String tableName,
                         Map<String, Object> primaryKey,
                         Map<String, Object> values) {
        TableMeta table = requireTable(tableName);
        requirePrimaryKey(table, primaryKey);
        Map<String, Object> safeValues = filterValues(table, values, false);
        for (String key : table.primaryKeys) {
            safeValues.remove(key);
        }
        if (safeValues.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "没有可修改的字段");
        }
        List<Object> parameters = new ArrayList<>(safeValues.values());
        parameters.addAll(table.primaryKeys.stream().map(primaryKey::get)
                .collect(Collectors.toList()));
        String sql = "UPDATE " + quote(table.name) + " SET " +
                safeValues.keySet().stream().map(key -> quote(key) + " = ?")
                        .collect(Collectors.joining(", ")) +
                " WHERE " + table.primaryKeys.stream().map(key -> quote(key) + " = ?")
                        .collect(Collectors.joining(" AND "));
        return jdbcTemplate.update(sql, parameters.toArray());
    }

    public int deleteRow(String tableName, Map<String, Object> primaryKey) {
        TableMeta table = requireTable(tableName);
        requirePrimaryKey(table, primaryKey);
        String sql = "DELETE FROM " + quote(table.name) + " WHERE " +
                table.primaryKeys.stream().map(key -> quote(key) + " = ?")
                        .collect(Collectors.joining(" AND "));
        Object[] parameters = table.primaryKeys.stream().map(primaryKey::get).toArray();
        return jdbcTemplate.update(sql, parameters);
    }

    public Map<String, Object> listRedisKeys(String pattern, int limit) {
        String safePattern = StrUtil.isBlank(pattern) ? "*" : pattern.trim();
        int safeLimit = Math.max(1, Math.min(MAX_REDIS_KEYS, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
        try (Cursor<byte[]> cursor = connection.scan(
                ScanOptions.scanOptions().match(safePattern).count(Math.min(safeLimit, 200)).build())) {
            while (cursor.hasNext() && items.size() < safeLimit) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                items.add(readRedisKey(key));
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取 Redis 键失败");
        } finally {
            connection.close();
        }
        items.sort(Comparator.comparing(item -> String.valueOf(item.get("key"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pattern", safePattern);
        result.put("items", items);
        result.put("limited", items.size() >= safeLimit);
        return result;
    }

    public Map<String, Object> saveRedisString(String key, String value, Long ttlSeconds) {
        if (StrUtil.isBlank(key)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Redis 键不能为空");
        }
        if (key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Redis 键不能包含换行");
        }
        redisTemplate.opsForValue().set(key, value == null ? "" : value);
        if (ttlSeconds != null && ttlSeconds > 0) {
            redisTemplate.expire(key, java.time.Duration.ofSeconds(ttlSeconds));
        } else if (ttlSeconds != null && ttlSeconds == -1) {
            redisTemplate.persist(key);
        }
        return readRedisKey(key);
    }

    public boolean deleteRedisKey(String key) {
        if (StrUtil.isBlank(key)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Redis 键不能为空");
        }
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    private Map<String, Object> databaseStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            result.put("connected", connection.isValid(2));
            result.put("url", metadata.getURL());
            result.put("user", metadata.getUserName());
            result.put("product", metadata.getDatabaseProductName() + " " +
                    metadata.getDatabaseProductVersion());
        } catch (SQLException e) {
            result.put("connected", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> redisStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        RedisConnection connection = null;
        try {
            connection = redisTemplate.getConnectionFactory().getConnection();
            result.put("connected", "PONG".equalsIgnoreCase(connection.ping()));
            result.put("keyCount", connection.dbSize());
        } catch (Exception e) {
            result.put("connected", false);
            result.put("message", e.getMessage());
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
        return result;
    }

    private Map<String, Object> readRedisKey(String key) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        DataType type = redisTemplate.type(key);
        item.put("type", type == null ? "NONE" : type.code());
        item.put("ttl", redisTemplate.getExpire(key));
        try {
            if (DataType.STRING.equals(type)) {
                item.put("value", redisTemplate.opsForValue().get(key));
            } else if (DataType.HASH.equals(type)) {
                item.put("value", limitMap(redisTemplate.opsForHash().entries(key)));
            } else if (DataType.LIST.equals(type)) {
                item.put("value", redisTemplate.opsForList()
                        .range(key, 0, MAX_REDIS_COLLECTION_ITEMS - 1));
            } else if (DataType.SET.equals(type)) {
                item.put("value", limitCollection(redisTemplate.opsForSet().members(key)));
            } else if (DataType.ZSET.equals(type)) {
                item.put("value", limitCollection(redisTemplate.opsForZSet()
                        .rangeWithScores(key, 0, MAX_REDIS_COLLECTION_ITEMS - 1)));
            } else {
                item.put("value", "该数据类型仅支持查看类型、TTL 和删除");
            }
        } catch (DataAccessException e) {
            item.put("value", "读取失败：" + e.getMostSpecificCause().getMessage());
        }
        return item;
    }

    private Map<Object, Object> limitMap(Map<Object, Object> source) {
        Map<Object, Object> result = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            if (count++ >= MAX_REDIS_COLLECTION_ITEMS) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private Collection<?> limitCollection(Collection<?> source) {
        if (source == null || source.size() <= MAX_REDIS_COLLECTION_ITEMS) {
            return source;
        }
        return source.stream().limit(MAX_REDIS_COLLECTION_ITEMS).collect(Collectors.toList());
    }

    private long countRows(TableMeta table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quote(table.name), Long.class);
        return count == null ? 0 : count;
    }

    private TableMeta requireTable(String tableName) {
        if (StrUtil.isBlank(tableName)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据库表不能为空");
        }
        try (Connection connection = dataSource.getConnection()) {
            return readTableMeta(connection, tableName);
        } catch (SQLException e) {
            throw databaseError("读取表结构失败", e);
        }
    }

    private TableMeta readTableMeta(Connection connection, String requestedName)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String actualName = null;
        try (ResultSet tables = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String candidate = tables.getString("TABLE_NAME");
                if (candidate.equals(requestedName)) {
                    actualName = candidate;
                    break;
                }
            }
        }
        if (actualName == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "数据库表不存在");
        }
        Set<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet keys = metadata.getPrimaryKeys(catalog, null, actualName)) {
            while (keys.next()) {
                primaryKeys.add(keys.getString("COLUMN_NAME"));
            }
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        Set<String> columnNames = new LinkedHashSet<>();
        try (ResultSet resultSet = metadata.getColumns(catalog, null, actualName, "%")) {
            while (resultSet.next()) {
                String name = resultSet.getString("COLUMN_NAME");
                columnNames.add(name);
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("name", name);
                column.put("type", resultSet.getString("TYPE_NAME"));
                column.put("nullable", resultSet.getInt("NULLABLE") !=
                        DatabaseMetaData.columnNoNulls);
                column.put("autoIncrement",
                        "YES".equalsIgnoreCase(resultSet.getString("IS_AUTOINCREMENT")));
                column.put("primaryKey", primaryKeys.contains(name));
                column.put("size", resultSet.getInt("COLUMN_SIZE"));
                int decimalDigits = resultSet.getInt("DECIMAL_DIGITS");
                column.put("decimalDigits", resultSet.wasNull() ? 0 : decimalDigits);
                column.put("defaultValue", resultSet.getString("COLUMN_DEF"));
                column.put("remarks", resultSet.getString("REMARKS"));
                columns.add(column);
            }
        }
        return new TableMeta(actualName, columns, columnNames,
                new ArrayList<>(primaryKeys));
    }

    private Map<String, Object> filterValues(TableMeta table,
                                             Map<String, Object> values,
                                             boolean skipAutoIncrement) {
        if (values == null) {
            return Collections.emptyMap();
        }
        Set<String> autoIncrement = table.columns.stream()
                .filter(column -> Boolean.TRUE.equals(column.get("autoIncrement")))
                .map(column -> String.valueOf(column.get("name")))
                .collect(Collectors.toSet());
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!table.columnNames.contains(entry.getKey())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "字段不存在：" + entry.getKey());
            }
            if (skipAutoIncrement && autoIncrement.contains(entry.getKey())
                    && (entry.getValue() == null || "".equals(entry.getValue()))) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private void requirePrimaryKey(TableMeta table, Map<String, Object> primaryKey) {
        if (table.primaryKeys.isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "该表没有主键，页面禁止修改和删除");
        }
        if (primaryKey == null || !primaryKey.keySet().containsAll(table.primaryKeys)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "主键不完整");
        }
    }

    private Map<String, Object> jsonSafeRow(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof byte[]) {
                byte[] bytes = (byte[]) value;
                result.put(entry.getKey(), "[二进制 " + bytes.length + " 字节] " +
                        Base64.getEncoder().encodeToString(bytes));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private BusinessException databaseError(String message, Exception cause) {
        return new BusinessException(ErrorCode.SYSTEM_ERROR,
                message + "：" + cause.getMessage());
    }

    private static class TableMeta {
        private final String name;
        private final List<Map<String, Object>> columns;
        private final Set<String> columnNames;
        private final List<String> primaryKeys;

        private TableMeta(String name,
                          List<Map<String, Object>> columns,
                          Set<String> columnNames,
                          List<String> primaryKeys) {
            this.name = name;
            this.columns = columns;
            this.columnNames = new HashSet<>(columnNames);
            this.primaryKeys = primaryKeys;
        }
    }
}
