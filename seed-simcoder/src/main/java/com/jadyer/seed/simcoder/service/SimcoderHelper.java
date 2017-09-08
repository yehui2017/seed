package com.jadyer.seed.simcoder.service;

import com.jadyer.seed.comm.util.DBUtil;
import com.jadyer.seed.simcoder.model.Column;
import com.jadyer.seed.simcoder.model.Table;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by 玄玉<http://jadyer.cn/> on 2017/9/7 17:18.
 */
public class SimcoderHelper {
    private static final String DB_URL = "jdbc:mysql://127.0.0.1:3306/mpp?useUnicode=true&characterEncoding=UTF8&failOverReadOnly=false&zeroDateTimeBehavior=convertToNull";
    private static final String DB_USERNAME = "root";
    private static final String DB_PASSWORD = "xuanyu";
    private static final String SQL_GET_TABLE = "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=?;";
    private static final String SQL_GET_COLUMN = "SELECT COLUMN_NAME as name, COLUMN_COMMENT as comment, DATA_TYPE as type, ifnull(CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION) as length, if(IS_NULLABLE='yes', true, false) as nullable, if(COLUMN_KEY='pri', true, false) as isPrikey, if(EXTRA='auto_increment', true, false) as isAutoIncrement FROM information_schema.COLUMNS WHERE TABLE_NAME=? ORDER BY ORDINAL_POSITION;";

    /**
     * 获取数据库中的所有表信息
     */
    public static List<Table> getTableList(String databaseName){
        List<Table> tableList = new ArrayList<>();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try{
            conn = DBUtil.INSTANCE.getConnection(DB_URL, DB_USERNAME, DB_PASSWORD);
            pstmt = conn.prepareStatement(SQL_GET_TABLE);
            pstmt.setString(1, databaseName);
            rs = pstmt.executeQuery();
            while(rs.next()){
                Table table = new Table();
                table.setName(rs.getString("table_name"));
                table.setComment(rs.getString("table_comment"));
                tableList.add(table);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }finally{
            DBUtil.INSTANCE.closeAll(rs, pstmt, conn);
        }
        return tableList;
    }


    /**
     * 获取某张表的所有列信息
     */
    public static List<Column> getColumnList(String tableName){
        List<Column> columnList = new ArrayList<>();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try{
            conn = DBUtil.INSTANCE.getConnection(DB_URL, DB_USERNAME, DB_PASSWORD);
            pstmt = conn.prepareStatement(SQL_GET_COLUMN);
            pstmt.setString(1, tableName);
            rs = pstmt.executeQuery();
            while(rs.next()){
                Column column = new Column();
                column.setName(rs.getString("name"));
                column.setComment(rs.getString("comment"));
                column.setType(rs.getString("type"));
                //TODO 注意MEDIUMTEXT类型的
                String length = rs.getString("length");
                column.setLength(null==length ? 0 : Integer.parseInt(length));
                /*
                if (("YES".equals(nullable)) || ("yes".equals(nullable)) || ("y".equals(nullable)) || ("Y".equals(nullable)) || ("NOT NULL".equals(nullable))) {
      return "Y";
    }
    if (("NO".equals(nullable)) || ("N".equals(nullable)) || ("no".equals(nullable)) || ("n".equals(nullable)) || ("NULL".equals(nullable))) {
      return "N";
    }
                */
                column.setNullable(rs.getBoolean("nullable"));
                column.setPrikey(rs.getBoolean("isPrikey"));
                column.setAutoIncrement(rs.getBoolean("isAutoIncrement"));
                columnList.add(column);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }finally{
            DBUtil.INSTANCE.closeAll(rs, pstmt, conn);
        }
        return columnList;
    }


    /**
     * 通过表名构建类名
     */
    public static String buildClassnameUseTablename(String tablename){
        if(StringUtils.isBlank(tablename)){
            throw new RuntimeException("表名不能为空");
        }
        if(tablename.startsWith("t_")){
            tablename = tablename.substring(2);
        }
        StringBuilder sb = new StringBuilder();
        for(String obj : tablename.split("_")){
            sb.append(StringUtils.capitalize(obj));
        }
        return sb.toString();
    }




    /**
     * 获取类型，将类型转换
     * @param dataType   文本截取类型
     * @param precision  0
     * @param scale      0
     * @return
     */
    public static String getType(String dataType, String precision, String scale) {
        dataType = dataType.toLowerCase();
        if (dataType.contains("lang varchar"))
        {
            dataType = "String";
        }
        if (dataType.contains("text"))
        {
            dataType = "String";
        }
        else if (dataType.contains("char"))
        {
            dataType = "String";
        }
        else if (dataType.contains("int")){
            dataType = "Integer";
        }
        else if (dataType.contains("float")){
            dataType = "Float";
        }
        else if (dataType.contains("double")){
            dataType = "Double";
        }
        else if (dataType.contains("number")) {
            if ((StringUtils.isNotBlank(scale))&& (Integer.parseInt(scale) > 0))
            {
                dataType = "java.math.BigDecimal";
            }
            else if ((StringUtils.isNotBlank(precision))&& (Integer.parseInt(precision) > 6))
            {
                dataType = "Long";
            }
            else
            {
                dataType = "Integer";
            }
        }
        else if (dataType.contains("decimal"))
        {
            dataType = "BigDecimal";
        }
        else if (dataType.contains("date"))
        {
            dataType = "Date";
        }
        else if (dataType.contains("time"))
        {
            dataType = "java.sql.Timestamp";
        }
        else if (dataType.contains("datetime"))
        {
            dataType = "Date";
        }
        else if (dataType.contains("clob"))
        {
            dataType = "java.sql.Clob";
        }
        else {
            dataType = "Object";
        }
        return dataType;
    }





    public String getFieldType(String type) {
        type = type.toLowerCase();
        if (type.contains("varchar") || type.contains("text") || type.contains("char")) {
            return "String";
        } else if (type.equals("int") || type.equals("tinyint")) {
            return "Integer";
        } else if (type.contains("bigint") || type.contains("long") || type.contains("number")) {
            return "Long";
        } else if (type.contains("double")) {
            return "Double";
        } else if (type.contains("date") || type.contains("time")) {
            return "Date";
        } else if (type.contains("decimal")) {
            return "BigDecimal";
        }
        return "unknown";
    }

    public String getImport(String type) {
        if (type.contains("date") || type.contains("time")) {
            return "java.util.Date";
        } else if (type.contains("decimal")) {
            return "java.math.BigDecimal";
        } else {
            return null;
        }
    }
}