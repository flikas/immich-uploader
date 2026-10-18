# 项目长期记忆与工作约束

本文件是本项目的长期经验记录。后续修改代码、生成测试数据或执行集成测试前，先读本文件。

## 零、跨项目原则：简单优先

- **简单的就是美的。** 优先最小、最直接的实现，避免为了“未来可能变化”预先增加抽象。
- **奥卡姆剃刀：** 如无必要，勿增实体。能用标准库默认行为、现有 API、最小配置解决的，不要手写包装、反射、字符集推断或额外兼容层。
- **先验证，再设计。** 不要凭记忆或猜测判断 Java/类库的默认行为；先看源码/字节码，或做一个最小实验。
- **默认行为优先。** 只有在最小复现证明默认行为确实不满足需求时，才增加复杂度。
- **不要用过度设计掩盖不确定性。** 不确定性应该用实验消除，而不是用更多代码包围。
- **改动应能解释为“删掉什么”或“让什么回归标准”。** 如果改动只是增加包装层而没有可复现的失败用例，停下来重新评估。
- **编码、兼容性、平台差异：先做原始字节/最小探针测试，再改代码。**

本原则适用于所有项目，不限于本项目。


## 一、控制台编码：最高优先级

### 已验证的正确事实

- Java 21 的 `PrintWriter(OutputStream, boolean)` 在参数是 `PrintStream` 时，会使用：
  ```java
  ((PrintStream) out).charset()
  ```
  而不是 `Charset.defaultCharset()`。
- 因此以下写法是正确的，会继承 `System.out` / `System.err` 的实际控制台编码：
  ```java
  commandLine.setOut(new PrintWriter(System.out, true));
  commandLine.setErr(new PrintWriter(System.err, true));
  ```
- Windows `cmd` 当前代码页 936 时：
  - `System.out.charset()` = GBK
  - `stdout.encoding` = GBK
  - `file.encoding` = UTF-8（Java 21）
  - `native.encoding` = GBK
  - `sun.stdout.encoding` = null

### 禁止事项

- **不要**再把 `System.out` / `System.err` 强制替换为 UTF-8。
- **不要**手写 `stdout.encoding`、`native.encoding`、`Charset.forName`、反射 `PrintStream.charset()` 来处理控制台帮助输出。
- **不要**使用：
  ```java
  new PrintWriter(new OutputStreamWriter(System.out, 手工字符集), true)
  ```
  这是过度设计，且已证明没有必要。
- **不要**把 HTML/TSV/报告文件的 UTF-8 编码需求和控制台编码混为一谈。

### Picocli 注意

- 当前 Picocli 4.7.6/4.7.7 默认 writer 仍读取旧的 `sun.stdout.encoding`；Java 21 下该属性为 null，默认 writer 会回退到 UTF-8。
- 所以仍需显式 `setOut/setErr`，但必须使用：
  ```java
  new PrintWriter(System.out, true)
  new PrintWriter(System.err, true)
  ```
- 不要因为 Picocli 默认行为不可靠就去手工指定 GBK。
- 未来升级 Picocli 后，先在 `cmd` 中验证默认行为，再决定是否删除 `setOut/setErr`。

### 文件编码

- HTML、TSV、Markdown 报告：明确 UTF-8。
- 控制台：跟随 `System.out/err`。
- 不要把控制台编码写进报告文件。

## 二、媒体元数据生成经验

### 图片 EXIF

- Commons Imaging 的 `TiffOutputSet.setGpsInDegrees` 参数顺序是：
  ```java
  setGpsInDegrees(longitude, latitude)
  ```
  不是 `(latitude, longitude)`。
- 写入后必须用 Pillow 或 Immich 实际读取验证：
  - `DateTimeOriginal`
  - GPS 纬度/经度
  - Make/Model
- 不要只验证 EXIF 写入 API 返回成功。

### 视频 MP4 元数据

- 需要同时更新：
  - `mvhd`
  - `tkhd`
  - `mdhd`
- 递归修改 `moov` 时，end 必须是 `pos + size`，不要误写成 `body.length - 8`，否则只改到 `mvhd`，`tkhd/mdhd` 仍是旧时间。
- 上传前至少用一个探针视频验证 Immich 实际返回：
  - `fileCreatedAt`
  - `exifInfo.dateTimeOriginal`
  - `latitude/longitude`
  - `make/model`
- XMP UUID 可以作为补充，但不能只依赖它。

### 图片格式

- `TiffImageWriterLossless` 可能只输出元数据，生成的文件其他解码器无法识别。
- 生成 TIFF 后必须用 Pillow/ImageIO 验证能正常打开。
- 验证失败时，宁可减少图片格式（JPEG/PNG），也不要上传损坏的 TIFF。
- 每个生成格式都要抽样验证，不要假设 ImageIO 写出的文件 Immich 一定接受。

## 三、Immich 上传与回收站语义

- 实测 Immich 3.2.2：视频按默认可见性上传后，再上传带 `livePhotoVideoId` 的图片，Immich 会自动把视频设为 `hidden`。不需要预先隐藏视频。
- 直接调用 `POST /api/assets` 上传一个校验和已存在于回收站的文件，Immich 返回：
  ```json
  {"status":"duplicate","id":"原资产ID"}
  ```
  原资产仍然是 `isTrashed=true`，**不会自动恢复**。
- 因此：
  - 预检发现 `duplicate + isTrashed` 时跳过上传是正确的。
  - 若要恢复，必须在 duplicate 后显式调用 Immich 恢复 API，不能靠重传。
- 服务端可见统计为 0 不代表回收站为空；回收站资产仍会让 `bulk-upload-check` 返回 duplicate。
- 测试前如果要确保干净：
  - 清空回收站，或
  - 给所有新文件加入唯一盐值，使校验和不同于回收站资产。
- 不要假设 API Key 具备 `asset.delete`；主程序不执行删除，测试清理使用外部脚本并先确认 Key 权限。

## 四、测试执行纪律

- 生成大规模测试集后，先做本地校验：
  - 文件总数
  - 图片/视频/非媒体数量
  - 顶层目录数
  - 最大嵌套深度
  - 时间范围与年份分布
  - EXIF/GPS/设备
  - MP4 时间字段
  - Live Photo 配对数量
- 正式批量上传前，先用 **1 个探针资产**验证 Immich 的实际元数据解析。
- 不要在没有探针验证的情况下直接上传几千个文件。
- 用户要求“只执行一次 upload”时，严格只执行一次；失败后先报告阻塞原因，不要擅自重跑。
- 如果必须修正数据后重跑，先说明原因，并清理/归档失败产物。
- 不要手工编辑最终 HTML 报告；应修改 `HtmlReportWriter` 并用程序重新生成。
- 不要覆盖或删除历史结果，除非用户明确要求。删除前先列出绝对路径并确认。
- 测试数据、TSV、HTML、日志、探针文件都属于结果资产，默认保留。

## 五、Java/字符集表述纪律

- 不要说“Java 默认字符集是 UTF-8”而不区分版本：
  - Java 17：默认字符集跟随系统区域设置（中文 Windows 通常是 GBK）。
  - Java 18+：`Charset.defaultCharset()` 默认 UTF-8（JEP 400）。
  - 控制台 I/O 始终是例外：由 `stdout.encoding` / `stderr.encoding` / `PrintStream.charset()` 决定。
- `PrintWriter(OutputStream)` 的行为不能靠记忆判断；涉及编码时先查 JDK 源码/字节码或做原始字节测试。
- 不要用“默认值应该可靠”代替验证。

## 六、Picocli 选项经验

- 不要在同一个布尔选项上同时定义正向和 `--no-` 形式，Picocli 可能自动取反，导致：
  - `--verify` 变成 `verify=false`
  - `--no-verify` 变成 `verify=true`
- 需要默认开启时，只定义 `--no-xxx` 关闭选项，并写测试验证解析结果。
- 修改 CLI 选项后必须重新打包 jar 并实际运行 `--help` 检查。

## 七、本次已犯错误清单

1. 强制把 `System.out/err` 设置为 UTF-8，导致 Windows `cmd` 中文乱码。
2. 误以为 `new PrintWriter(System.out)` 使用 JVM 默认字符集；实际 JDK 21 会继承 `PrintStream.charset()`。
3. 在 `setOut` 中手工读取字符集、反射、包装 `OutputStreamWriter`，属于过度设计。
4. 把 Java 默认字符集一概说成 UTF-8，没有区分 Java 17、Java 18+ 和控制台 I/O。
5. 图片 GPS 经纬度参数顺序写反，导致 Immich GPS 为空。
6. MP4 只更新了 `mvhd`，没有正确更新 `tkhd/mdhd`，导致 Immich 仍使用旧拍摄时间。
7. 生成的 TIFF 实际不可被常规解码器读取；没有先验证就继续使用。
8. 在 Immich 回收站仍有旧资产时直接批量上传，产生大量 duplicate 和 Live Photo 失败。
9. 失败后未经充分说明就重新上传，增加了服务器残留资产。
10. 曾手工修改最终 HTML 报告，而不是用生成器重建。

## 八、以后遇到类似问题的工作顺序

1. 先复现问题，保存原始字节/请求/响应。
2. 查 JDK/库源码或字节码，确认默认行为。
3. 用最小探针验证。
4. 再修改代码。
5. 单元测试 + 实际 CLI + Immich 探针验证。
6. 最后才批量执行。
7. 保留所有结果，并在最终答复中说明残留风险。

