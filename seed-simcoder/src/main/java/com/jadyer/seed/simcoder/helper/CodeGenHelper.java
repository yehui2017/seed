package com.jadyer.seed.simcoder.helper;

import com.jadyer.seed.comm.util.JadyerUtil;
import com.jadyer.seed.simcoder.model.Column;
import com.jadyer.seed.simcoder.model.Table;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.beetl.core.Configuration;
import org.beetl.core.GroupTemplate;
import org.beetl.core.Template;
import org.beetl.core.resource.ClasspathResourceLoader;

import javax.swing.filechooser.FileSystemView;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * Created by 玄玉<http://jadyer.cn/> on 2017/9/8 23:22.
 */
public class CodeGenHelper {
    private static final String PACKAGE_MODEL = "com.jadyer.seed.mpp.web.model";
    private static final String PACKAGE_REPOSITORY = "com.jadyer.seed.mpp.web.repository";
    private static final String importColumnAnnotation = "\nimport javax.persistence.Column;";
    private static final String importDate = "import java.util.Date;\n";
    private static final String importBigDecimal = "import java.math.BigDecimal;\n";
    private static final String importBigDecimalAndDate = "import java.math.BigDecimal;\nimport java.util.Date;\n";
    private static GroupTemplate groupTemplate = null;
    static{
        try {
            groupTemplate = new GroupTemplate(new ClasspathResourceLoader("templates/"), Configuration.defaultConfiguration());
        } catch (IOException e) {
            System.err.println("加载Beetl模板失败，堆栈轨迹如下：");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 生成整个数据库的
     */
    public static void generateFromDatabase(String databaseName){
        List<Table> tableList = DBHelper.getTableList(databaseName);
        for(Table table : tableList){
            generateFromTable(table.getName(), table.getComment());
        }
    }

    /**
     * 生成某张表的
     */
    public static void generateFromTable(String tablename, String tablecomment){
        boolean hasDate = false;
        boolean hasBigDecimal = false;
        boolean hasColumnAnnotation = false;
        StringBuilder fields = new StringBuilder();
        StringBuilder methods = new StringBuilder();
        List<Column> columnList = DBHelper.getColumnList(tablename);
        for(int i=0; i<columnList.size(); i++){
            if(StringUtils.equalsAnyIgnoreCase(columnList.get(i).getName(), "id", "create_time", "update_time")){
                continue;
            }
            /*
             * /** 字段注释 *\/
             */
            if(StringUtils.isNotBlank(columnList.get(i).getComment())){
                fields.append("    /** ").append(columnList.get(i).getComment()).append(" */\n");
            }
            /*
             * @Column(name="bind_status")
             */
            String fieldname = DBHelper.buildFieldnameFromColumnname(columnList.get(i).getName());
            if(!fieldname.equals(columnList.get(i).getName())){
                hasColumnAnnotation = true;
                fields.append("    @Column(name=\"").append(columnList.get(i).getName()).append("\")").append("\n");
            }
            /*
             * private int bindStatus;
             */
            String javaType = DBHelper.buildJavatypeFromDbtype(columnList.get(i).getType());
            if(javaType.equals("Date")){
                hasDate = true;
            }
            if(javaType.equals("BigDecimal")){
                hasBigDecimal = true;
            }
            fields.append("    private ").append(javaType).append(" ").append(fieldname).append(";").append("\n");
            /*
             * public int getBindStatus() {
             *     return bindStatus;
             * }
             *
             * public void setBindStatus(int bindStatus) {
             *     this.bindStatus = bindStatus;
             * }
             */
            methods.append("    public ").append(javaType).append(" get").append(StringUtils.capitalize(fieldname)).append("() {").append("\n");
            methods.append("        return ").append(fieldname).append(";").append("\n");
            methods.append("    }").append("\n");
            methods.append("\n");
            methods.append("    public void set").append(StringUtils.capitalize(fieldname)).append("(").append(javaType).append(" ").append(fieldname).append(") {").append("\n");
            methods.append("        this.").append(fieldname).append(" = ").append(fieldname).append(";").append("\n");
            methods.append("    }");
            //约定的
            if(i+1 != columnList.size()-2){
                methods.append("\n\n");
            }
        }
        /*
         * 用户信息
         * Generated from seed-simcoder by 玄玉<http://jadyer.cn/> on 2017/9/5 14:40.
         */
        StringBuilder comments = new StringBuilder();
        if(StringUtils.isNotBlank(tablecomment)){
            if(tablecomment.endsWith("表")){
                tablecomment = tablecomment.substring(0, tablecomment.length()-1);
            }
            comments.append(tablecomment).append("\n");
            comments.append(" * ");
        }
        comments.append("Generated from seed-simcoder by 玄玉<http://jadyer.cn/> on ").append(DateFormatUtils.format(new Date(), "yyyy/MM/dd HH:mm."));
        /*
         * 构造Beetl模板变量
         */
        String classname = DBHelper.buildClassnameFromTablename(tablename);
        Template templateRepo = groupTemplate.getTemplate("repository.btl");
        templateRepo.binding("PACKAGE_REPOSITORY", PACKAGE_REPOSITORY);
        templateRepo.binding("PACKAGE_MODEL", PACKAGE_MODEL);
        templateRepo.binding("CLASS_NAME", classname);
        templateRepo.binding("comments", comments.toString());
        Template templateModel = groupTemplate.getTemplate("model.btl");
        templateModel.binding("PACKAGE_MODEL", PACKAGE_MODEL);
        templateModel.binding("CLASS_NAME", classname);
        templateModel.binding("TABLE_NAME", tablename);
        templateModel.binding("fields", fields.toString());
        templateModel.binding("methods", methods.toString());
        templateModel.binding("comments", comments.toString());
        templateModel.binding("serialVersionUID", JadyerUtil.buildSerialVersionUID());
        if(hasColumnAnnotation){
            templateModel.binding("importColumnAnnotation", importColumnAnnotation);
        }
        if(hasDate && hasBigDecimal){
            templateModel.binding("importBigDecimalDate", importBigDecimalAndDate);
        }else if(hasBigDecimal){
            templateModel.binding("importBigDecimalDate", importBigDecimal);
        }else if(hasDate){
            templateModel.binding("importBigDecimalDate", importDate);
        }
        try {
            String outBaseDir = FileSystemView.getFileSystemView().getHomeDirectory().getPath() + System.getProperty("file.separator");
            templateModel.renderTo(FileUtils.openOutputStream(new File(outBaseDir + "model" + System.getProperty("file.separator") + classname + ".java")));
            templateRepo.renderTo(FileUtils.openOutputStream(new File(outBaseDir + "repository" + System.getProperty("file.separator") + classname + "Repository.java")));
        } catch (IOException e) {
            System.err.println("生成代码时发生异常，堆栈轨迹如下：");
            e.printStackTrace();
        }
    }
}