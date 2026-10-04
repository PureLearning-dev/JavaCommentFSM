import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;

/**
 * CommentStripper 测试集（自包含，无第三方依赖）。
 *
 * <p>运行（在项目根目录）：
 * {@code javac -encoding UTF-8 -d out/classes CommentStripper.java test/CommentStripperTest.java && java -cp out/classes CommentStripperTest}
 *
 * <p>每个用例做两项断言：
 * <ol>
 *   <li>输出与期望一致；</li>
 *   <li>幂等性：strip(strip(input)) == strip(input)（保持相同的换行模式）。</li>
 * </ol>
 */
public class CommentStripperTest {

    private int passed = 0;
    private int failed = 0;
    private final StringBuilder failureLog = new StringBuilder();
    private final List<String> allInputs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        CommentStripperTest t = new CommentStripperTest();
        t.runAll();
        System.out.println();
        System.out.println("结果: 通过 " + t.passed + " / " + (t.passed + t.failed));
        if (t.failed > 0) {
            System.out.print(t.failureLog);
            System.exit(1);
        }
    }

    void runAll() throws Exception {
        check("空输入", "", "");
        check("无注释中文源码", "String s = \"你好\";", "String s = \"你好\";");
        section("行注释");
        check("行尾注释", "int a = 1; // 注释", "int a = 1; ");
        check("整行注释", "// 整行注释\nint a;", "\nint a;");
        check("文件尾注释(无换行)", "int a; // tail", "int a; ");
        check("注释内出现各种符号", "x // don't /* */ \" '\n", "x \n");
        check("CRLF 行注释", "int a;// c\r\nint b;", "int a;\r\nint b;");
        check("CR 行注释", "x// c\ry", "x\ry");

        section("块注释");
        check("行内块注释", "int a; /* 注释 */ int b;", "int a;  int b;");
        check("跨行块注释(保留换行)", "/* 多行\n注释 */int a;", "\nint a;");
        check("跨行块注释(不保留换行)", "/* 多行\n注释 */int a;", "int a;", false);
        check("CRLF 块注释(保留换行)", "/*\r\n*/x", "\r\nx");
        check("注释两侧 CR 和 LF 不合并", "a\r/*x*/\nb", "a\r \nb");
        check("块内分离的 CR 和 LF 不合并", "/*\rx\n*/b", "\r \nb");
        check("空块注释分隔标识符", "a/**/b", "a b");
        check("开头星号不能兼作结束符", "a/*/b", "a");
        check("开头后的星号可以结束", "a/**/b", "a b");
        check("注释分隔关键字", "int/**/value;", "int value;");
        check("注释分隔运算符", "x+/**/+y", "x+ +y");
        check("分隔符后文件以斜杠结尾", "x/*c*//", "x /");
        check("连续注释仅需一个分隔符", "a/*1*//*2*/b", "a b");
        check("不保留换行也保留分隔符", "a/*\r\nx*/b", "a b", false);
        check("尾部块注释不增加空格", "a/*x*/", "a");
        check("连续星号正确闭合", "a/****/b", "a b");
        check("已有空白不重复补空格", "a/**/\tb", "a\tb");
        check("块注释起始符不重叠", "/*/still comment*/x", "x");
        check("块注释不嵌套", "a/* b /* c */ d", "a d");
        check("未闭合块注释吞到文件尾", "a /* 未闭合", "a ");

        section("除法");
        check("除法不是注释", "int x = a / b;", "int x = a / b;");
        check("除法后紧跟注释", "int x = a / b;//c", "int x = a / b;");
        check("乘除混合", "x = a * / b;", "x = a * / b;");
        check("文件以单个斜杠结尾", "int x = a /", "int x = a /");

        section("字符字面量");
        check("字符里的斜杠", "char c = '/';", "char c = '/';");
        check("字符转义引号", "char c = '\\'';", "char c = '\\'';");
        check("字符转义反斜杠", "char c = '\\\\';", "char c = '\\\\';");
        check("未闭合字符字面量防御", "char c = 'x", "char c = 'x");
        check("字符转义后换行仍恢复", "'x\\\n// tail\ny", "'x\\\n\ny");

        section("字符串");
        check("URL 中的 //", "String s = \"https://a.com/x//y\";", "String s = \"https://a.com/x//y\";");
        check("字符串中的 */", "String s = \"https://example.com/a/*b*/c\";", "String s = \"https://example.com/a/*b*/c\";");
        check("字符串中的注释符号", "String s = \"a // b /* c */ d\";", "String s = \"a // b /* c */ d\";");
        check("字符串转义引号", "String s = \"say \\\"//hi\\\"\";", "String s = \"say \\\"//hi\\\"\";");
        check("字符串转义反斜杠", "String s = \"a\\\\b\"; // c", "String s = \"a\\\\b\"; ");
        check("空字符串", "String s = \"\";", "String s = \"\";");
        check("空字符串后紧跟行注释", "String s = \"\"// 注释\nint a;", "String s = \"\"\nint a;");
        check("相邻空串(4 引号)", "String s = \"\"\"\";", "String s = \"\"\"\";");
        check("相邻空串(5 引号)", "String s = \"\"\"\"\";", "String s = \"\"\"\"\";");
        check("相邻空串(6 引号)", "String s = \"\"\"\"\"\";", "String s = \"\"\"\"\"\";");
        check("未闭合字符串防御", "String s = \"abc", "String s = \"abc");
        check("字符串转义后 CRLF 仍恢复", "\"x\\\r\n// tail\ny", "\"x\\\r\n\ny");
        check("字符串转义后 LF 仍恢复", "\"x\\\n// tail\ny", "\"x\\\n\ny");

        section("文本块");
        check("文本块保护行注释", "String s = \"\"\"\n// 不是注释\n\"\"\";", "String s = \"\"\"\n// 不是注释\n\"\"\";");
        check("文本块保护块注释", "String s = \"\"\"\n/* 也不是 */\n\"\"\";", "String s = \"\"\"\n/* 也不是 */\n\"\"\";");
        check("文本块内的除法", "String s = \"\"\"\n10 / 2\n\"\"\";", "String s = \"\"\"\n10 / 2\n\"\"\";");
        check("文本块转义三引号", "String s = \"\"\"\n\\\"\"\"\n\"\"\";", "String s = \"\"\"\n\\\"\"\"\n\"\"\";");
        check("文本块内单双引号不误闭合", "String s = \"\"\"\nhe said \"hi\" and 'x'\n\"\"\";", "String s = \"\"\"\nhe said \"hi\" and 'x'\n\"\"\";");
        check("三个引号无换行时回退为空串+普通串", "String s = \"\"\"x//y\";", "String s = \"\"\"x//y\";");
        check("文本块转义打断连续引号", "\"\"\"\n\"\"\\\"\"// keep\n\"\"\";// remove",
                "\"\"\"\n\"\"\\\"\"// keep\n\"\"\";");
        check("文本块头部空白与 CRLF", "\"\"\" \t\f\r\n// keep\r\n\"\"\";// remove\r\n",
                "\"\"\" \t\f\r\n// keep\r\n\"\"\";\r\n");

        section("综合");
        check("注释与字符串混合", "int a = 1; /* 块 */ // 行\nint b = \"/* 不是注释 */\"; // 尾",
                "int a = 1;  \nint b = \"/* 不是注释 */\"; ");
        check("字符与块注释相邻", "a='/'/*c*/b='//';", "a='/' b='//';");
        check("块注释内的换行不保留模式", "a /* x\ny */ b", "a  b", false);
        check("行注释总是保留换行", "// c\nx", "\nx", false);

        section("文件级用例");
        fileChecks();

        section("幂等性");
        idempotenceChecks();

        section("实例复用");
        CommentStripper reused = new CommentStripper();
        for (String input : allInputs) {
            assertStringEquals("复用实例无状态残留", new CommentStripper().strip(input), reused.strip(input));
        }

        section("编译运行与命令行");
        integrationChecks();
    }

    /** 单条用例：期望输出 + 幂等性。 */
    void check(String name, String input, String expected) {
        check(name, input, expected, true);
    }

    void check(String name, String input, String expected, boolean preserve) {
        allInputs.add(input);
        CommentStripper stripper = new CommentStripper(preserve);
        String actual = stripper.strip(input);
        assertStringEquals(name + "（当前模式幂等性）", actual, stripper.strip(actual));
        if (preserve) assertEquals(name + "（逻辑行数）", lineCount(input), lineCount(actual));
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            failureLog.append(String.format("✗ %s%n  输入:   %s%n  期望:   %s%n  实际:   %s%n%n",
                    name, quote(input), quote(expected), quote(actual)));
        }
    }

    void fileChecks() {
        try {
            fileChecksUnsafe();
        } catch (IOException e) {
            failed++;
            failureLog.append("✗ 文件级用例无法读取文件: ").append(e.getMessage())
                    .append("\n  请在项目根目录运行测试（java -cp out/classes CommentStripperTest）。\n\n");
        }
    }

    void fileChecksUnsafe() throws IOException {
        // 1. Sample.java 全文件对比期望输出
        String sample = Files.readString(Path.of("test", "Sample.java"), StandardCharsets.UTF_8);
        String expected = Files.readString(Path.of("test", "Sample.stripped.java"), StandardCharsets.UTF_8);
        allInputs.add(sample);
        String actual = new CommentStripper().strip(sample);
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            failureLog.append("✗ Sample.java 全文件对比失败（详见 diff）\n");
            int n = Math.min(expected.length(), actual.length());
            int i = 0;
            while (i < n && expected.charAt(i) == actual.charAt(i)) i++;
            failureLog.append(String.format("  首个差异位于 %d 行：期望[%s] 实际[%s]%n",
                    lineOf(expected, i), snippet(expected, i), snippet(actual, i)));
        }

        // 2. Application.java 定向断言
        String app = Files.readString(Path.of("Application.java"), StandardCharsets.UTF_8);
        String stripped = new CommentStripper().strip(app);
        allInputs.add(app);
        assertContains("Application.java 保留方法名", stripped, "testForLoop");
        assertContains("Application.java 保留循环代码", stripped, "for(int i = 0; i < 2; i++){");
        assertNotContains("Application.java 去除 Javadoc 内容", stripped, "执行初始化语句");
        assertNotContains("Application.java 无块注释定界符", stripped, "/*");
        assertNotContains("Application.java 无行注释定界符", stripped, "//");
        assertEquals("Application.java 行号不变", lineCount(app), lineCount(stripped));
    }

    void idempotenceChecks() {
        for (int i = 0; i < allInputs.size(); i++) {
            String input = allInputs.get(i);
            String once = new CommentStripper().strip(input);
            String twice = new CommentStripper().strip(once);
            if (once.equals(twice)) {
                passed++;
            } else {
                failed++;
                failureLog.append(String.format("✗ 幂等性失败（第 %d 个输入）%n  一次: %s%n  二次: %s%n%n",
                        i + 1, quote(once), quote(twice)));
            }
        }
    }

    /** 用真实 Java 编译器和独立进程验证输出，临时文件仅存在于本次创建的目录中。 */
    void integrationChecks() throws Exception {
        Path temp = Files.createTempDirectory("comment-stripper-test-");
        try {
            for (Path file : List.of(Path.of("Application.java"), Path.of("test/Sample.java"))) {
                comparePrograms(temp, file.getFileName().toString(), Files.readString(file));
            }
            String boundary = "public class Boundary {\n"
                    + "public static void main(String[] args) {\n"
                    + "int/**/value = 2; int x = value+/**/+value;\n"
                    + "String text = \"\"\"\n\"\"\\\"\"// keep\n\"\"\"; // remove\n"
                    + "int y = 1/*/ still comment */+2;\n"
                    + "System.out.println(x + text + y);\n}}\n";
            comparePrograms(temp, "Boundary.java", boundary);

            Path core = Files.createDirectories(temp.resolve("core"));
            String coreSource = Files.readString(Path.of("CommentStripper.java"));
            Files.writeString(core.resolve("CommentStripper.java"), new CommentStripper().strip(coreSource));
            if (compile(core, "CommentStripper.java")) {
                Path sample = Path.of("test/Sample.java").toAbsolutePath();
                assertEquals("去注释后的工具仍可处理文件", 0,
                        runJava(core, core.toString(), "CommentStripper", sample.toString()).exitCode);
                assertStringEquals("工具自处理后的输出一致", new CommentStripper().strip(Files.readString(sample)),
                        Files.readString(core.resolve("output/Sample.java")));
            }

            Path input = temp.resolve("中文 input.java");
            String source = "class Example { /* 注释 */ String s = \"你好//世界\"; }\r\n";
            Files.writeString(input, source);
            String expected = new CommentStripper().strip(source);
            assertEquals("CLI 默认输出退出码", 0, runCli(temp, input.toString()).exitCode);
            assertStringEquals("CLI 默认输出内容与 UTF-8", expected,
                    Files.readString(temp.resolve("output/中文 input.java")));
            assertEquals("CLI 裸文件名退出码", 0, runCli(temp, input.toString(), "renamed.java").exitCode);
            assertStringEquals("CLI 裸文件名放入 output", expected,
                    Files.readString(temp.resolve("output/renamed.java")));
            assertEquals("CLI 创建多层目录", 0, runCli(temp, input.toString(), "nested/dir/result.java").exitCode);
            assertStringEquals("CLI 指定路径内容", expected,
                    Files.readString(temp.resolve("nested/dir/result.java")));
            assertEquals("CLI 无参数退出码", 2, runCli(temp).exitCode);
            assertEquals("CLI 多余参数退出码", 2, runCli(temp, input.toString(), "x.java", "extra").exitCode);
            ProcessResult missing = runCli(temp, temp.resolve("missing.java").toString());
            assertEquals("CLI 输入不存在退出码", 1, missing.exitCode);
            assertContains("CLI 文件错误有明确提示", missing.output, "处理失败:");
            assertEquals("CLI 输入目录退出码", 1, runCli(temp, temp.toString()).exitCode);
            assertEquals("CLI 输出目录退出码", 1, runCli(temp, input.toString(), temp.toString()).exitCode);
            assertEquals("CLI 同文件保护", 1, runCli(temp, input.toString(), input.toString()).exitCode);
            assertEquals("CLI 规范化路径同文件保护", 1,
                    runCli(temp, input.toString(), temp.resolve("./中文 input.java").toString()).exitCode);
            Path link = temp.resolve("alias.java");
            Files.createSymbolicLink(link, input);
            assertEquals("CLI 符号链接同文件保护", 1, runCli(temp, input.toString(), link.toString()).exitCode);
            assertStringEquals("CLI 原始输入未被覆盖", source, Files.readString(input));
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.delete(path);
                }
            }
        }
    }

    void comparePrograms(Path temp, String filename, String source) throws Exception {
        Path original = Files.createDirectories(temp.resolve(filename + "-original"));
        Path stripped = Files.createDirectories(temp.resolve(filename + "-stripped"));
        Files.writeString(original.resolve(filename), source);
        Files.writeString(stripped.resolve(filename), new CommentStripper().strip(source));
        boolean compiledOriginal = compile(original, filename);
        boolean compiledStripped = compile(stripped, filename);
        if (compiledOriginal && compiledStripped) {
            String className = filename.substring(0, filename.length() - ".java".length());
            ProcessResult before = runJava(original, original.toString(), className);
            ProcessResult after = runJava(stripped, stripped.toString(), className);
            assertEquals(filename + " 原始程序运行", 0, before.exitCode);
            assertEquals(filename + " 去注释程序运行", 0, after.exitCode);
            assertStringEquals(filename + " 运行结果一致", before.output, after.output);
        }
    }

    boolean compile(Path directory, String filename) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("测试需要完整 JDK（包含 Java 编译器）");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int result = compiler.run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-d",
                directory.toString(), directory.resolve(filename).toString());
        assertEquals(filename + " 编译 " + directory.getFileName(), 0, result);
        if (result != 0) failureLog.append(diagnostics.toString(StandardCharsets.UTF_8));
        return result == 0;
    }

    ProcessResult runCli(Path cwd, String... args) throws Exception {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            entries.add(Path.of(entry).toAbsolutePath().toString());
        }
        return runJava(cwd, String.join(File.pathSeparator, entries), "CommentStripper", args);
    }

    ProcessResult runJava(Path cwd, String classPath, String mainClass, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-cp", classPath, mainClass));
        command.addAll(List.of(args));
        Path log = Files.createTempFile(cwd, "process-", ".log");
        Process process = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Java 子进程运行超时: " + mainClass);
        }
        return new ProcessResult(process.exitValue(), Files.readString(log));
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    // ---------- 断言辅助 ----------

    void assertStringEquals(String name, String expected, String actual) {
        if (expected.equals(actual)) passed++;
        else {
            failed++;
            failureLog.append(String.format("✗ %s%n  期望: %s%n  实际: %s%n%n",
                    name, quote(expected), quote(actual)));
        }
    }

    void assertContains(String name, String haystack, String needle) {
        if (haystack.contains(needle)) passed++;
        else failed++;
        if (!haystack.contains(needle)) {
            failureLog.append("✗ ").append(name).append(": 未找到 ").append(quote(needle)).append("\n\n");
        }
    }

    void assertNotContains(String name, String haystack, String needle) {
        if (!haystack.contains(needle)) passed++;
        else {
            failed++;
            failureLog.append("✗ ").append(name).append(": 不应包含 ").append(quote(needle)).append("\n\n");
        }
    }

    void assertEquals(String name, int expected, int actual) {
        if (expected == actual) passed++;
        else {
            failed++;
            failureLog.append(String.format("✗ %s: 期望 %d，实际 %d%n%n", name, expected, actual));
        }
    }

    // ---------- 输出辅助 ----------

    void section(String title) {
        System.out.println("== " + title + " ==");
    }

    static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + '"';
    }

    static int lineOf(String s, int index) {
        int line = 1;
        for (int i = 0; i < index && i < s.length(); i++) {
            if (s.charAt(i) == '\n') line++;
        }
        return line;
    }

    static String snippet(String s, int index) {
        int from = Math.max(0, index - 12);
        int to = Math.min(s.length(), index + 12);
        return quote(s.substring(from, to));
    }

    static int lineCount(String s) {
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\r' || (s.charAt(i) == '\n'
                    && (i == 0 || s.charAt(i - 1) != '\r'))) lines++;
        }
        return lines;
    }
}
