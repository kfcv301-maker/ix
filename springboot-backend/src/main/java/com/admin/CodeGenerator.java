package com.admin;

import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;

import java.util.Scanner;

/** Developer-only helper, migrated to the MyBatis-Plus 3.5 builder API. */
public class CodeGenerator {

    private static String scanner(String tip) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("请输入" + tip + "：");
        if (scanner.hasNext()) {
            String value = scanner.next();
            if (StringUtils.isNotBlank(value)) return value;
        }
        throw new MybatisPlusException("请输入正确的" + tip + "！");
    }

    public static void main(String[] args) {
        String host = requiredEnvironment("DB_HOST");
        String database = requiredEnvironment("DB_NAME");
        String username = requiredEnvironment("DB_USER");
        String password = requiredEnvironment("DB_PASSWORD");
        String outputDirectory = System.getProperty("user.dir") + "/src/main/java";

        FastAutoGenerator.create("jdbc:mysql://" + host + "/" + database
                        + "?useUnicode=true&useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                username, password)
                .globalConfig(builder -> builder.author("QAQ").outputDir(outputDirectory).disableOpenDir())
                .packageConfig(builder -> builder.parent("com.admin"))
                .strategyConfig(builder -> builder.addInclude(scanner("表名，多个英文逗号分割").split(","))
                        .entityBuilder().superClass("com.admin.entity.BaseEntity").enableLombok()
                        .addSuperEntityColumns("id", "created_time", "updated_time", "status")
                        .controllerBuilder().superClass("com.admin.controller.BaseController").enableRestStyle()
                        .serviceBuilder().formatServiceFileName("%sService"))
                .execute();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new MybatisPlusException("请设置数据库环境变量: DB_HOST, DB_NAME, DB_USER, DB_PASSWORD");
        }
        return value;
    }
}
