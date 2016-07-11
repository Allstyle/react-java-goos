package com.vdian.goos;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.LineProcessor;
import com.google.common.io.Resources;

/**
 * <p>文件名称：GooGoo.java</p>
 * <p>文件描述：</p>
 * <p>版权所有：版权所有(C)2011-2099</p>
 * <p>公   司：口袋购物 </p>
 * <p>内容摘要：</p>
 * <p>其他说明：</p>
 * <p>完成日期：2016年7月10日</p>
 *
 * @version 1.0
 * @author jiangxiyang@weidian.com
 */
public class GooGoo {

    private static Pattern querySchemaPattern = Pattern.compile("^(.*)\\.querySchema\\.js$");
    private static Pattern dataSchemaPattern = Pattern.compile("^(.*)\\.dataSchema\\.js$");
    private static Pattern modulePattern = Pattern.compile("^module\\.exports\\s+=\\s+(.*)$");

    public static void main(String[] args) {
        if (args.length > 2) {
            usage();
            return;
        }

        String inputDir = "../src/schema";
        String outputDir = ".";

        if (args.length == 2) {
            inputDir = args[0];
            outputDir = args[1];
        }

        if (args.length == 1) {
            inputDir = args[0];
        }

        System.out.println("INFO: input directory = " + inputDir);
        System.out.println("INFO: output directory = " + outputDir);

        File f1 = new File(inputDir), f2 = new File(outputDir);

        if (!f1.exists()) {
            System.err.println("ERROR: " + inputDir + " not exist");
            return;
        }

        if (!f2.exists()) {
            System.err.println("ERROR: " + outputDir + " not exist");
            return;
        }

        // 扫描inputDir下的所有文件
        Set<String> allTable = Sets.newHashSet();
        Matcher m1, m2;
        for (File f : f1.listFiles()) {
            String fileName = f.getName();
            String tableName = null;
            m1 = querySchemaPattern.matcher(fileName);
            if (m1.matches()) {
                tableName = m1.group(1);
                generateQueryVO(f, outputDir, tableName);
            }

            m2 = dataSchemaPattern.matcher(fileName);
            if (m2.matches()) {
                tableName = m2.group(1);
                generateVO(f, outputDir, tableName);
            }

            if (tableName != null) {
                if (!allTable.contains(tableName)) {
                    generateController(outputDir, tableName);
                    allTable.add(tableName);
                }
            }
        }

        // 每个表特定的类生成完毕，开始生成一些通用的类
        try {
            copyFile("LoginController.sample", outputDir, "LoginController.java");
            copyFile("CommonResult.sample", outputDir, "CommonResult.java");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return;
    }

    /**
     * <p>功能描述：根据QuerySchema生成QueryVO</p>
     * <p>创建人：jiangxiyang</p>
     * <p>创建日期：2016年7月11日 上午11:07:30</p>
     *
     * @param schemaFile
     * @param outputDir
     * @param tableName
     */
    private static void generateQueryVO(File schemaFile, String outputDir, String tableName) {
        System.out.println("INFO: generating QueryVO for " + tableName);

        try {
            Map<String, String> params = getParamMap(tableName);
            JSONArray schema = parseJson(schemaFile);

            // 开始处理schema，根据dataType和showType生成各个字段
            StringBuilder fields = new StringBuilder();
            for (Object o : schema) {
                // 一个字段处理失败不应该影响全局
                try {
                    JSONObject field = (JSONObject) o;
                    String dataType = field.getString("dataType"), showType = field.containsKey("showType") ? field.getString("showType") : "normal";

                    if ("between".equals(showType)) {
                        fields.append(getBetweenField(dataType, field.getString("key")));
                    } else if ("select".equals(showType) || "radio".equals(showType)) {
                        fields.append(getListField(dataType, field.getString("key")));
                    } else {
                        fields.append(getSingleField(dataType, field.getString("key")));
                    }
                } catch (Exception e) {
                    System.err.println("ERROR: parsing field " + o + ": " + e.getMessage());
                }
            }

            // 读取模版文件并做变量替换
            params.put("fields", fields.toString());
            List<String> lines = parseTemplate("QueryVO.sample", params);

            // 将parse后的内容写入文件
            generateFile(lines, outputDir, tableName, "QueryVO.java");

        } catch (Exception e) {
            System.err.println("ERROR: generating QueryVO ERROR: " + schemaFile.getAbsolutePath());
            e.printStackTrace();
        }
    }

    /**根据dataSchema生成VO*/
    private static void generateVO(File schemaFile, String outputDir, String tableName) {
        System.out.println("INFO: generating VO for " + tableName);

        try {
            Map<String, String> params = getParamMap(tableName);
            JSONArray schema = parseJson(schemaFile);

            // 开始处理schema
            StringBuilder fields = new StringBuilder();
            for (Object o : schema) {
                try {
                    JSONObject field = (JSONObject) o;
                    String dataType = field.getString("dataType");
                    // dataSchema现在没有showType属性，所以都是singleField
                    fields.append(getSingleField(dataType, field.getString("key")));
                } catch (Exception e) {
                    System.err.println("ERROR: parsing field " + o + ": " + e.getMessage());
                }
            }

            // 读取模版文件并做变量替换
            params.put("fields", fields.toString());
            List<String> lines = parseTemplate("VO.sample", params);

            // 将parse后的内容写入文件
            generateFile(lines, outputDir, tableName, "VO.java");

        } catch (Exception e) {
            System.err.println("ERROR: generating VO ERROR: " + schemaFile.getAbsolutePath());
            e.printStackTrace();
        }
    }

    /**生成controller*/
    private static void generateController(String outputDir, String tableName) {
        System.out.println("INFO: generating Controller for " + tableName);

        try {
            Map<String, String> params = getParamMap(tableName);
            List<String> lines = parseTemplate("Controller.sample", params);
            generateFile(lines, outputDir, tableName, "Controller.java");
        } catch (Exception e) {
            System.err.println("ERROR: generating Controller ERROR: " + tableName);
            e.printStackTrace();
        }
    }

    private static void generateFile(List<String> lines, String outputDir, String tableName, String fileName) throws IOException {
        ensureDir(outputDir, tableName);
        String upCamelName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, tableName);
        File target = new File(outputDir + "/" + tableName + "/" + upCamelName + fileName);
        System.out.println("INFO: writing file " + target.getAbsolutePath());
        BufferedWriter bw = Files.newBufferedWriter(target.toPath());
        for (String line : lines) {
            bw.write(line);
            bw.newLine();
        }
        bw.close();
    }

    /**从classpath中读取某个文件，原样写入outputDir*/
    private static void copyFile(String inputFile, String outputDir, String outputName) throws IOException {
        File target = new File(outputDir, outputName);
        System.out.println("INFO: writing file " + target.getAbsolutePath());
        BufferedWriter bw = Files.newBufferedWriter(target.toPath());
        for (String line : Resources.readLines(Resources.getResource(inputFile), Charsets.UTF_8)) {
            bw.write(line);
            bw.newLine();
        }
        bw.close();
    }

    private static Map<String, String> getParamMap(String tableName) {
        String lowCamelName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, tableName);
        String upCamelName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, tableName);
        Map<String, String> params = Maps.newHashMap();
        params.put("lowCamelName", lowCamelName);
        params.put("upCamelName", upCamelName);
        return params;
    }

    /**读取模版文件并做变量替换*/
    private static List<String> parseTemplate(String templateFileName, final Map<String, String> params) throws MalformedURLException, IOException {
        final Pattern p = Pattern.compile("\\{(.*)\\}");
        // 定义每行的处理逻辑
        LineProcessor<List<String>> processor = new LineProcessor<List<String>>() {

            private List<String> result = Lists.newArrayList();

            @Override
            public boolean processLine(String line) throws IOException {
                String tmp = line;
                Matcher m = p.matcher(tmp);
                while (m.find()) {
                    String key = m.group(1);
                    if (params.containsKey(key)) {
                        tmp = tmp.replaceAll("\\{" + key + "\\}", params.get(key));
                    }
                }

                result.add(tmp);
                return true;
            }

            @Override
            public List<String> getResult() {
                return result;
            }
        };

        return Resources.readLines(Resources.getResource(templateFileName), Charsets.UTF_8, processor);
    }

    /**生成普通的字段*/
    private static String getSingleField(String dataType, String key) {
        if ("int".equals(dataType)) {
            return "private Long " + key + ";\n";
        } else if ("float".equals(dataType)) {
            return "private Double " + key + ";\n";
        } else if ("varchar".equals(dataType)) {
            return "private String " + key + ";\n";
        } else if ("datetime".equals(dataType)) {
            return "private Date " + key + ";\n";
        } else {
            throw new RuntimeException("unknown dataType " + dataType);
        }
    }

    /**生成list字段，目前只有dataType=int/varchar时可能List*/
    private static String getListField(String dataType, String key) {
        if ("int".equals(dataType)) {
            return "private List<Long> " + key + ";\n";
        } else if ("varchar".equals(dataType)) {
            return "private List<String> " + key + ";\n";
        } else {
            throw new RuntimeException("unknown dataType " + dataType);
        }
    }

    /**生成两个字段，用于范围查询，只有int/float/datetime可能出现范围查询*/
    private static String getBetweenField(String dataType, String key) {
        StringBuilder sb = new StringBuilder();
        if ("int".equals(dataType)) {
            sb.append("private Long " + key + "Begin;\n");
            sb.append("private Long " + key + "End;\n");
        } else if ("float".equals(dataType)) {
            sb.append("private Double " + key + "Begin;\n");
            sb.append("private Double " + key + "End;\n");
        } else if ("datetime".equals(dataType)) {
            sb.append("private Date " + key + "Begin;\n");
            sb.append("private Date " + key + "End;\n");
        } else {
            throw new RuntimeException("unknown dataType " + dataType);
        }

        return sb.toString();
    }

    /**
     * <p>功能描述：读取schema文件并转换为json对象</p>
     * <p>创建人：jiangxiyang</p>
     * <p>创建日期：2016年7月11日 上午9:58:50</p>
     *
     * @param schemaFile
     * @param tableName
     * @return
     * @throws IOException 
     */
    private static JSONArray parseJson(File schemaFile) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(schemaFile));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0)
                continue;
            if (line.startsWith("//")) // 忽略注释
                continue;
            if (line.contains("//")) // inline注释
                line = line.substring(0, line.indexOf("//"));
            if (line.endsWith(";")) // 去除最后一行的;
                line = line.substring(0, line.length() - 1);
            if (line.startsWith("render")) // 忽略自定义的渲染函数
                continue;

            if (line.startsWith("module.exports")) { // module语句
                Matcher m = modulePattern.matcher(line);
                if (m.matches()) {
                    sb.append(m.group(1));
                } else {
                    System.err.println("ERROR: error format for line: " + line);
                }
                continue;
            }

            sb.append(line);
        }
        br.close();

        return JSONArray.parseArray(sb.toString());
    }

    /**
     * <p>功能描述：确保某个目录存在，不存在则新建</p>
     * <p>创建人：jiangxiyang</p>
     * <p>创建日期：2016年7月11日 上午10:04:50</p>
     *
     * @param parent
     * @param child
     */
    private static void ensureDir(String parent, String child) {
        File f = new File(parent, child);
        if (!f.exists())
            f.mkdirs();
    }

    /**
     * <p>功能描述：打印帮助信息</p>
     * <p>创建人：jiangxiyang</p>
     * <p>创建日期：2016年7月11日 上午10:08:34</p>
     *
     */
    private static void usage() {
        System.err.println("Param incorrect.");
        System.err.println("Usage: java -jar xyz.jar [inputDir] [outputDir]");
    }

}
