package com.enterprise.platform.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PostgreSQL JSONB 与 JsonNode 的显式映射，保留 SQL NULL 并拒绝损坏 JSON。
 *
 * <p>这里仅解析 JSON 树，不进行 DTO 命名转换；数据库写入使用 OTHER，避免 varchar/JSONB 类型冲突。
 *
 * @author chomy
 **/
public class JsonNodeTypeHandler extends BaseTypeHandler<JsonNode> {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * 将 JSON 树绑定为 PostgreSQL JSONB 可接受的参数类型。
   **/
  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, JsonNode value, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, value.toString(), Types.OTHER);
  }

  /**
   * 读取具名 JSONB 列；数据库空值仍返回 Java null。
   **/
  @Override
  public JsonNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return parse(rs.getString(columnName));
  }

  /**
   * 读取按位置定位的 JSONB 列。
   **/
  @Override
  public JsonNode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return parse(rs.getString(columnIndex));
  }

  /**
   * 支持 MyBatis 的可调用语句结果读取。
   **/
  @Override
  public JsonNode getNullableResult(CallableStatement statement, int columnIndex)
      throws SQLException {
    return parse(statement.getString(columnIndex));
  }

  /**
   * 损坏的持久化 JSON 必须显式失败，不能用空对象掩盖。
   **/
  private JsonNode parse(String value) throws SQLException {
    if (value == null) return null;
    try {
      return JSON.readTree(value);
    } catch (JsonProcessingException e) {
      throw new SQLException("INVALID_STORED_JSON", e);
    }
  }
}
