<h1 align="center">JavaCommentFSM</h1>

一个独立的 `CommentStripper.java` 文件，使用有限状态机读取 Java 源码、删除行注释 `//` 和块注释 `/* … */`（包含 Javadoc），然后输出新的 Java 文件。保留字符串、字符字面量及文本块中的注释样式内容，并防止代码在删除注释后粘连。

只需要这个 Java 文件和 JDK 15+，不需要测试文件、第三方库或构建工具。

## 直接使用

在项目根目录执行，可使用 JDK 的单文件源码运行方式：

```bash
# 将路径替换成你实际要处理的 Java 文件
java CommentStripper.java /path/to/Input.java

# 显式指定输出路径（自动创建父目录）
java CommentStripper.java /path/to/Input.java output/custom/Input.java
```

默认输出到当前工作目录下的 `output/`，文件名与输入相同。显式输出为裸文件名时也放入 `output/`，带目录时按指定路径写入。输入和输出均使用 UTF-8，拒绝覆盖输入文件自身。

也可以先编译再运行：

```bash
javac -encoding UTF-8 -d out/classes CommentStripper.java
java -cp out/classes CommentStripper /path/to/Input.java
```

命令行退出码：成功 `0`，文件或路径错误 `1`，参数数量错误 `2`。

如需在其他 Java 程序中调用：

```java
new CommentStripper().stripFile(Path.of("Input.java"), Path.of("output/Input.java"));
```

`Path` 来自 `java.nio.file.Path`，调用方处理或声明 `IOException`。此外提供 `strip(String source)`，可直接处理内存中的源码字符串。

> FSM（Finite State Machine，有限状态机）是一种由有限个状态、输入以及状态转移规则组成的计算模型，系统会根据当前状态和输入按照既定规则转移到下一个状态。

## 设计思想

设计状态，通过代码字符进行状态的进入与转换。

在 Java 中，有行注释 `//` 和块注释 `/* */`，都是以 `/` 开始，但是以 `/` 开始又不一定会到注释状态（也可能是除法），所以检索到 `/` 时会是一种单独的状态。

但若 `//` 和 `/* */` 在字符串中，则不算注释，所以字符串也得作为一种状态，且优先级最高。

在 Java 中，字符和字符串的表达方式有 3 种：`'…'`（字符字面量）、`"…"`（字符串）、`"""…"""`（文本块）。文本块以三个引号开头，但 `""` 也可能是空字符串、`""""` 可能是两个相邻的空字符串，因此不能把“读到第三个引号”直接判为文本块：按 JLS 3.10.6，`"""` 之后（只允许夹带空白）必须紧跟行终止符才是文本块，本实现对不符合开启条件的引号序列按“空字符串 + 普通字符串开头”进行防御性恢复；这不代表该非法源码可以通过 Java 编译。

综上：

- 字符串类状态（优先级最高）：`Char`、`Quote_One`、`Quote_Two`、`In_String`、`In_Text` 共 5 种；
- 以 `/` 开头设置 1 种状态：`Slash`；
- 注释 2 种表达方式，故设置 2 种状态：`Line`、`Block`；
- 正常代码设置 1 种状态：`Normal`。

## 状态定义

| 状态 | 含义 |
| :--: | :--- |
| `Normal` | 普通代码 |
| `Slash` | 读到一个 `/`，待定：除法 / 行注释 / 块注释 |
| `Line` | 行注释 `// …` 内部 |
| `Block` | 块注释 `/* … */` 内部 |
| `Char` | 字符字面量 `'…'` 内部 |
| `Quote_One` | 读到第 1 个 `"`：字符串或文本块待定 |
| `Quote_Two` | 读到第 2 个 `"`：空串 `""` 或文本块待定 |
| `In_String` | 普通字符串 `"…"` 内部 |
| `In_Text` | 文本块 `"""…"""` 内部 |

### 辅助标志

为保持状态表紧凑，5 个辅助变量随扫描状态生效（不单设状态，其中引号连续计数为整数）：

| 标志 | 所属状态 | 含义 |
| :--: | :--: | :--- |
| `escape` | `Char` / `In_String` / `In_Text` | 上一个字符是 `\`，下一个字符原样抄录；普通字符串和字符字面量遇换行优先恢复 |
| `star` | `Block` | 刚读到 `*`，等待 `/` 闭合 |
| `textQuoteRun` | `In_Text` | 连续 `"` 计数，满 3 个即闭合文本块 |
| `textOpening` | `In_Text` | `"""` 之后尚未见到行终止符（开启确认阶段） |
| `separatorPending` | 删除块注释后 | 后续代码与既有输出均无空白分隔时，先补一个空格 |

## 状态转移表

表中“输出”指把当前字符写入结果；`\r`、`\n` 为行终止符。表按条件描述，转义标志为真时优先于普通引号和反斜杠规则；普通字符串与字符字面量的换行恢复规则优先于转义规则。删除块注释后，在输出后续代码前按 `separatorPending` 补充必要的空格。

| 现态 | 输入 | 次态 | 输出 / 动作 |
| :--: | :--: | :--: | :--- |
| `Normal` | `/` | `Slash` | 不输出，暂存待定 |
| `Normal` | `'` | `Char` | 输出 |
| `Normal` | `"` | `Quote_One` | 输出 |
| `Normal` | 其他（含换行） | `Normal` | 输出 |
| `Slash` | `/` | `Line` | 两个 `/` 均不输出 |
| `Slash` | `*` | `Block` | `/`、`*` 均不输出，清 `star`，置 `separatorPending`（开启符与结束符不重叠） |
| `Slash` | 其他 | 按 `Normal` 规则处理当前字符 | 先输出暂存的 `/` |
| `Line` | `\r` 或 `\n` | `Normal` | 输出（保留行号） |
| `Line` | 其他 | `Line` | 不输出 |
| `Line` | EOF | — | 结束，无输出 |
| `Block` | `*` | `Block` | 不输出，置 `star` |
| `Block` | `/`（`star` 为真） | `Normal` | 不输出，清 `star` |
| `Block` | `\r` 或 `\n` | `Block` | 输出（保留行号，见下），清 `star` |
| `Block` | 其他 | `Block` | 不输出，清 `star` |
| `Block` | EOF | — | 结束（未闭合：吞到文件尾） |
| `Char` | `\` | `Char` | 输出，置 `escape` |
| `Char` | 任意（`escape` 为真） | `Char` | 输出，清 `escape` |
| `Char` | `'` | `Normal` | 输出 |
| `Char` | `\r` 或 `\n` | `Normal` | 输出，清 `escape`（即使上一字符为反斜杠也恢复） |
| `Char` | 其他 | `Char` | 输出 |
| `Quote_One` | `"` | `Quote_Two` | 输出 |
| `Quote_One` | `\r` 或 `\n` | `Normal` | 输出（防御：未闭合） |
| `Quote_One` | 其他 | `In_String` | 按 `In_String` 规则处理当前字符 |
| `Quote_Two` | `"` | `In_Text` | 输出，进入开启确认阶段（`textOpening`） |
| `Quote_Two` | 其他 | 按 `Normal` 规则处理当前字符 | `""` 为空字符串，视为已结束 |
| `In_String` | `\` | `In_String` | 输出，置 `escape` |
| `In_String` | 任意（`escape` 为真） | `In_String` | 输出，清 `escape` |
| `In_String` | `"` | `Normal` | 输出 |
| `In_String` | `\r` 或 `\n` | `Normal` | 输出，清 `escape`（即使上一字符为反斜杠也恢复） |
| `In_String` | 其他 | `In_String` | 输出 |
| `In_Text`（开启阶段） | 空格 / `\t` / `\f` | `In_Text` | 输出 |
| `In_Text`（开启阶段） | `\r` 或 `\n` | `In_Text`（内容阶段） | 输出 |
| `In_Text`（开启阶段） | 其他 | `In_String` | 回退：按 `In_String` 规则处理当前字符（即 `""` + 普通字符串） |
| `In_Text`（内容阶段） | `\` | `In_Text` | 输出，置 `escape`，清 `textQuoteRun` |
| `In_Text`（内容阶段） | 任意（`escape` 为真） | `In_Text` | 输出，清 `escape`、`textQuoteRun` |
| `In_Text`（内容阶段） | `"` | `In_Text` | 输出，`textQuoteRun` +1；满 3 → `Normal`（闭合） |
| `In_Text`（内容阶段） | 其他 | `In_Text` | 输出，`textQuoteRun` 清零 |

## 边界与设计决策

1. **保留换行**：行注释结束处的行终止符、块注释内部的换行都原样输出，因此去注释后代码的**行号与原文一致**（编译器报错行号可直接对照原文）。若删除注释会让原本分离的 CR 与 LF 相邻，补一个空格以防它们合并成一次 CRLF 换行。块注释换行保留可通过构造参数关闭（`new CommentStripper(false)`）。
2. **EOF 处理**：文件以单个 `/` 结尾按除法输出；未闭合的 `/* …` 吞到文件尾；未闭合的字符串/字符原样保留。
3. **防御性闭合**：未闭合的 `'…`、`"…` 遇到换行视为结束，避免“污染”后续代码。
4. **文本块语义**：`"""` 后必须先遇到行终止符才确认文本块；否则按“空串 + 普通字符串”进行防御性恢复；连续引号、未闭合字面量等非法源码不保证可编译。
5. **词法分隔**：块注释相当于分隔符，删除后必要时补一个空格。例如 `int/**/value` → `int value`，`x+/**/+y` → `x+ +y`，避免改变代码含义。若两侧已有空白、保留的换行或注释位于文件首尾，则不增加空格。`/*/` 不包含完整结束符，会吞到文件尾。
6. **实例复用与复杂度**：每次 `strip` 调用使用独立扫描状态，支持同一实例重复或并发调用。时间复杂度 O(n)，除输出缓冲外状态空间 O(1)；传入 `null` 抛出 `NullPointerException`。
7. **已知局限**：不处理 `\uXXXX` Unicode 转义（如 `\u0022` 实际是引号、行注释里的 `\u000A` 实际换行）；对非法源码的输出不做保证。
