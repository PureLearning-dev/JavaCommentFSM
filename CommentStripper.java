import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 基于有限状态机（FSM）去除 Java 源码中的注释。
 *
 * <p>从左到右扫描一遍，按「当前状态 + 输入字符」转移。字符串类状态优先级最高：
 * 字符串 / 字符字面量 / 文本块中的 {@code /}、{@code //}、{@code /*} 永不开启注释。
 *
 * <pre>
 * Normal --'/'--&gt; Slash --'/'--&gt; Line    （行注释）
 *                    \____'*'__&gt; Block   （块注释）
 * Normal --'\''--&gt; Char                  （字符字面量）
 * Normal --'"'--&gt; Quote_One --'"'--&gt; Quote_Two --'"'--&gt; In_Text（需换行确认，否则回退）
 *                                  \________其他_______&gt; 按 Normal 处理（空串 ""）
 *                    \_______其他_______&gt; In_String   （普通字符串）
 * </pre>
 *
 * <p>完整的状态定义与转移表见 README.md；本类每个分支与表中行一一对应。
 */
public class CommentStripper {

    /** FSM 状态。 */
    private enum State {
        /** 普通代码 */
        NORMAL,
        /** 读到单个 '/'，尚未判定是除法还是注释 */
        SLASH,
        /** 行注释 '//' 内部 */
        LINE,
        /** 块注释 '/*' 内部 */
        BLOCK,
        /** 字符字面量 '…' 内部 */
        CHAR,
        /** 读到第 1 个 '"'（字符串或文本块待定） */
        QUOTE_ONE,
        /** 读到第 2 个 '"'（空串 "" 或文本块待定） */
        QUOTE_TWO,
        /** 普通字符串 "…" 内部 */
        IN_STRING,
        /** 文本块 """…""" 内部 */
        IN_TEXT,
    }

    /** 是否保留块注释内部的换行（保持去注释前后的行号一致） */
    private final boolean preserveLineBreaks;

    public CommentStripper() {
        this(true);
    }

    /** @param preserveLineBreaks 是否保留块注释内的换行；两种模式均防止代码词法单元粘连 */
    public CommentStripper(boolean preserveLineBreaks) {
        this.preserveLineBreaks = preserveLineBreaks;
    }

    /** 去除注释，保留字面量；每次调用使用独立状态，可重复使用或并发调用。 */
    public String strip(String source) {
        Objects.requireNonNull(source, "source");
        return new Scanner(preserveLineBreaks).strip(source);
    }

    /** 读取 UTF-8 Java 文件，去除注释并写入新的文件，自动创建输出目录。 */
    public void stripFile(Path input, Path output) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (!Files.isRegularFile(input)) {
            throw new IOException("输入不是可读取的普通文件: " + input);
        }
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                || (Files.exists(output) && Files.isSameFile(input, output))) {
            throw new IOException("输入和输出不能是同一个文件: " + input);
        }
        String source = Files.readString(input, StandardCharsets.UTF_8);
        String stripped = strip(source);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, stripped, StandardCharsets.UTF_8);
    }

    /** 单次扫描的状态，不跨调用共享。 */
    private static final class Scanner {
        private State state = State.NORMAL;
        /** 字符串类状态中：上一个字符是 '\'，下一个字符盲抄（转义） */
        private boolean escaped = false;
        /** BLOCK 状态中：刚读到 '*'，等待 '/' 闭合 */
        private boolean starSeen = false;
        /** IN_TEXT 内容阶段：连续 '"' 计数（满 3 个闭合） */
        private int textQuoteRun = 0;
        /** IN_TEXT 开启确认阶段：""" 后尚未见到行终止符 */
        private boolean textOpening = true;
        /** 删除块注释后，必要时在后续代码前补一个空格。 */
        private boolean separatorPending = false;
        /** 是否保留块注释内部的换行（保持去注释前后的行号一致） */
        private final boolean preserveLineBreaks;

        Scanner(boolean preserveLineBreaks) {
            this.preserveLineBreaks = preserveLineBreaks;
        }

        /** 去除 {@code source} 中的注释，返回处理后的源码。 */
        public String strip(String source) {
            StringBuilder out = new StringBuilder(source.length());
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                int previousLength = out.length();
                step(c, out);
                // 删除两次换行之间的注释时，不能让原本分离的 CR、LF 合并成一次 CRLF。
                if (c == '\n' && out.length() > previousLength && out.length() >= 2
                        && out.charAt(out.length() - 2) == '\r'
                        && (i == 0 || source.charAt(i - 1) != '\r')) {
                    out.insert(out.length() - 1, ' ');
                }
            }
            finish(out);
            return out.toString();
        }

        /** 处理一个输入字符（转移表中的一行）。 */
        private void step(char c, StringBuilder out) {
            switch (state) {
                case NORMAL -> normalStep(c, out);
                case SLASH -> {
                    if (c == '/') {
                        state = State.LINE;                  // // 两个斜杠都不输出
                    } else if (c == '*') {
                        state = State.BLOCK;
                        starSeen = false;   // 开头的 * 不能同时参与结束符
                        separatorPending = true;
                    } else {
                        appendCode('/', out);                // 除法：补上暂存的 '/'
                        normalStep(c, out);                  // 再按 Normal 处理当前字符
                    }
                }
                case LINE -> {
                    if (c == '\n' || c == '\r') {            // 行注释结束，保留换行
                        state = State.NORMAL;
                        out.append(c);
                    }                                   // 其余内容吞掉
                }
                case BLOCK -> {
                    if (starSeen && c == '/') {              // */ 闭合
                        state = State.NORMAL;
                        starSeen = false;
                    } else {
                        starSeen = (c == '*');
                        if (preserveLineBreaks && (c == '\n' || c == '\r')) {
                            out.append(c);                   // 保留换行，行号不变
                        }
                    }
                }
                case CHAR -> charStep(c, out);
                case QUOTE_ONE -> {
                    if (c == '"') {                          // 第 2 个引号
                        state = State.QUOTE_TWO;
                        out.append(c);
                    } else if (c == '\n' || c == '\r') {     // 防御：未闭合
                        state = State.NORMAL;
                        out.append(c);
                    } else {                                 // 确定为普通字符串
                        state = State.IN_STRING;
                        stringStep(c, out);
                    }
                }
                case QUOTE_TWO -> {
                    if (c == '"') {                          // 第 3 个引号：乐观进入文本块
                        state = State.IN_TEXT;
                        textOpening = true;
                        textQuoteRun = 0;
                        out.append(c);
                    } else {                                 // "" 是空串，字符串已结束
                        normalStep(c, out);
                    }
                }
                case IN_STRING -> stringStep(c, out);
                case IN_TEXT -> textStep(c, out);
                default -> throw new IllegalStateException("未知状态: " + state);
            }
        }

        /** Normal 状态的转移：见 README 表 Normal 行。 */
        private void normalStep(char c, StringBuilder out) {
            state = State.NORMAL;   // 进入 Normal 后按当前字符重新派发
            if (c != '/') {
                appendSeparator(c, out);
            }
            switch (c) {
                case '/' -> state = State.SLASH;             // 暂存，不输出
                case '\'' -> { state = State.CHAR; out.append(c); }
                case '"' -> { state = State.QUOTE_ONE; out.append(c); }
                default -> out.append(c);
            }
        }

        /** Char 状态的转移。 */
        private void charStep(char c, StringBuilder out) {
            state = State.CHAR;
            if (c == '\n' || c == '\r') {
                out.append(c);
                escaped = false;
                state = State.NORMAL;
            } else if (escaped) {                            // 转义后的字符盲抄
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                out.append(c);
                escaped = true;
            } else if (c == '\'') {                           // 闭合
                out.append(c);
                state = State.NORMAL;
            } else {
                out.append(c);
            }
        }

        /** In_String 状态的转移。 */
        private void stringStep(char c, StringBuilder out) {
            state = State.IN_STRING;
            if (c == '\n' || c == '\r') {
                out.append(c);
                escaped = false;
                state = State.NORMAL;
            } else if (escaped) {                            // 转义后的字符盲抄
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                out.append(c);
                escaped = true;
            } else if (c == '"') {                            // 闭合
                out.append(c);
                state = State.NORMAL;
            } else {
                out.append(c);
            }
        }

        /** In_Text 状态的转移：先完成开启确认，再按内容阶段处理。 */
        private void textStep(char c, StringBuilder out) {
            state = State.IN_TEXT;
            if (textOpening) {
                if (c == ' ' || c == '\t' || c == '\f') {    // 开启定界符允许夹带空白
                    out.append(c);
                } else if (c == '\n' || c == '\r') {         // 见到行终止符：文本块确认
                    textOpening = false;
                    out.append(c);
                } else {                                     // 回退：""" 实为 "" + 普通串
                    state = State.IN_STRING;
                    stringStep(c, out);
                }
                return;
            }
            if (escaped) {                                   // 转义后的字符盲抄（含 \"\"\"）
                out.append(c);
                escaped = false;
                textQuoteRun = 0;
            } else if (c == '\\') {
                out.append(c);
                escaped = true;
                textQuoteRun = 0;
            } else if (c == '"') {
                textQuoteRun++;
                out.append(c);
                if (textQuoteRun >= 3) {                     // """ 闭合
                    state = State.NORMAL;
                    textQuoteRun = 0;
                }
            } else {
                textQuoteRun = 0;
                out.append(c);
            }
        }

        /** 输入结束时的收尾：处理以单个 '/' 结尾的除法。 */
        private void finish(StringBuilder out) {
            if (state == State.SLASH) {
                appendCode('/', out);
            }
            // LINE / BLOCK 未闭合：吞掉剩余，无输出；字符串未闭合：保持原样
        }

        private void appendCode(char c, StringBuilder out) {
            appendSeparator(c, out);
            out.append(c);
        }

        private void appendSeparator(char c, StringBuilder out) {
            if (separatorPending) {
                if (out.length() > 0 && !isWhitespace(out.charAt(out.length() - 1))
                        && !isWhitespace(c)) {
                    out.append(' ');
                }
                separatorPending = false;
            }
        }

        private static boolean isWhitespace(char c) {
            return c == ' ' || c == '\t' || c == '\f' || c == '\r' || c == '\n';
        }
    }

    /**
     * 命令行入口：{@code java CommentStripper <输入.java> [输出文件]}。
     * 未指定输出文件时，默认写入 {@code output/} 目录下的同名文件。
     */
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("用法: java CommentStripper <输入.java> [输出文件]");
            System.err.println("未指定输出文件时，结果写入 output/ 目录下的同名文件。");
            System.exit(2);
            return;
        }
        try {
            processArguments(args);
        } catch (IOException | InvalidPathException e) {
            System.err.println("处理失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void processArguments(String[] args) throws IOException {
        Path input = Path.of(args[0]);
        Path output;
        if (args.length >= 2) {
            output = Path.of(args[1]);
            // 裸文件名也归入 output/ 目录，保证输出的 .java 都在新文件夹中
            if (output.getParent() == null) {
                output = Path.of("output").resolve(output);
            }
        } else {
            Path filename = input.getFileName();
            if (filename == null) {
                throw new IOException("输入必须是一个 Java 文件: " + input);
            }
            output = Path.of("output").resolve(filename);
        }
        new CommentStripper().stripFile(input, output);
        System.out.println("已写入: " + output);
    }
}
