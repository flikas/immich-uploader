# Contributing

## 开发环境

- Java 17+（开发和测试使用 Java 21）
- Maven 3.9+
- 默认 UTF-8 源文件编码
- Windows 下控制台编码由 `System.out` / `System.err` 决定，不要强制改成 UTF-8

常用命令：

```bash
mvn test          # 运行单元测试
mvn package       # 打包 target/immich-uploader.jar
mvn clean test    # 干净构建
```

## 设计约束

修改代码前先阅读 [DESIGN.md](DESIGN.md)。

必须保持：

1. **主程序不包含删除/修改资产的业务代码。**
   - 上传、读取、建立 Live Photo 关联可以存在于主程序。
   - `asset.delete`、批量删除、清空回收站只能出现在外部测试脚本。

2. **阶段状态和汇总状态分离。**
   - Reader/Sender 只写 `Progress` 阶段状态。
   - 汇总只在阶段结束时由调用者合并到 `RunSummary`。
   - 不要从多个工作线程直接写 `RunSummary`。

3. **任务创建后不可变。**
   - 路径、大小、时间、SHA1、contentType、deviceAssetId 应在任务创建时填好。
   - 上传路径不再重新 `stat`、`probeContentType` 或计算 SHA1。
   - 预检/上传结果放在独立对象中。

4. **有界队列和显式背压。**
   - 不使用无界队列承载文件内容。
   - Sender 队列满时阻塞 Reader，不使用 `CallerRunsPolicy`。
   - 不使用 permits 或额外 semaphore 作为任务窗口。
   - 并发边界是预检批 + 有界 Sender 队列。

5. **缓存上限和文件大小。**
   - 默认单文件缓存 20 MB。
   - 超过上限使用流式上传。
   - 不要为了性能把整个目录读入内存。

6. **Reader 数默认保守。**
   - SSD 4，HDD/未知 1。
   - 显式 `--reader-threads` 可以覆盖。
   - 自动识别失败必须回退 1，不做媒体微基准。

7. **默认参数要可解释。**
   - `--sender-threads` 默认 2。
   - 不要在没有测量数据的情况下提高默认并发或降低缓存限制。

8. **预检 ID 不建全局任务映射。**
   - Reader 在本地 `PrecheckBatch` 完成预检后直接构造上传任务。
   - Live Photo 的已有 assetId 放进 `UploadTask.LivePhoto`，不要建 `Map<UploadTask, ...>`。
   - `UploadTask` 里的 `byte[]` 只能活到 Sender 完成。
   - 新增全局 map/list 时必须证明任务完成后会移除，并补内存回归测试。

9. **控制台统计口径固定。**
   - `已处理 = 上传 + 重复 + 文件失败 + 跳过`。
   - `补链失败` 单独计数，不计入文件处理数；命令结束概要的 `失败` 包含它。
   - 新增加的结果类型必须同时更新 `Progress`、`StageSnapshot`、`RunSummary` 和测试。
   - 读取/上传速率按实际读写字节统计，不能只统计成功上传。

10. **线程级活动必须清理。**
    - Reader/Sender 的 `started`/`finished` 必须成对出现。
    - 异常路径也要在 `finally` 中调用 `finished`，避免活动 map 泄漏。

## 测试约定

### TDD

修 bug 或改行为时：

1. 先写一个会失败的测试。
2. 确认失败原因和预期一致。
3. 再改实现。
4. 跑完整 `mvn test`。

### 测试层次

- 单元测试：core、CLI 解析、报告、记录、API 客户端。
- MockWebServer：HTTP 接口行为。
- 真实 Immich 测试只能手动/外部执行，不提交测试媒体和密钥。

### 测试数据

- 测试数据放在 `.tmp/` 或系统临时目录。
- 不提交测试图片、视频、TSV、HTML、日志和状态目录。
- 集成测试使用专用服务器和专用 API Key。
- 测试完成后用外部脚本按 assetId 清理；不要在测试代码中删除用户资产。
- 不把真实 API Key 写入源码、测试、文档或提交历史。

### 测试断言

- 统计必须断言阶段/汇总口径，不能只看控制台文本。
- 涉及 Live Photo 时同时断言图片、视频、`livePhotoVideoId`。
- 涉及失败/跳过时区分 failed、skipped、duplicate，以及非致命的补链失败。
- 并发测试要断言最终计数、队列上限和任务队列退出，不依赖 sleep 猜时序。

## 代码风格

- Java 17 语法，优先 record、switch、try-with-resources。
- 4 空格缩进。
- 不使用通配符 import。
- 类名 `UpperCamelCase`，方法和变量 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`。
- 方法尽量保持单一职责；复杂流程抽阶段方法。
- 异常信息包含上下文：路径、阶段、HTTP 状态或具体原因。
- 不在生产代码中打印 API Key、完整响应体中的敏感字段。

## CLI 约定

- 选项使用 kebab-case，例如 `--sender-threads`、`--cache-max-file-size`。
- 默认值写在 `@Option(defaultValue = "...")`，帮助文本用 `${DEFAULT-VALUE}`。
- 布尔关闭选项使用 `--no-xxx`，不要同时定义正向和负向同名布尔选项。
- 修改 CLI 后必须：
  - 更新 README 参数表
  - 重新打包 jar
  - 实际运行 `--help` 检查
  - 更新 CLI smoke test

当前 upload 关键默认值：

```text
--sender-threads 2
--reader-threads auto（SSD 4 / HDD 1）
--cache-max-file-size 20M
--precheck-batch-size auto（按堆内存计算，最大 500）
--sender-queue-length 32
```

## 并发约定

- 工作线程不能直接写汇总统计。
- 任务/队列的可见性依赖线程安全队列和不可变任务；不使用 permits。
- Sender 队列满时用阻塞拒绝策略形成背压。
- `RecordStore` 的 append 由文件锁/写锁保护；并发写 TSV 必须串行化。
- `Progress` 使用原子计数；阶段快照在阶段结束时读取。
- 关闭时必须保证：
  - Reader 能退出
  - Sender 队列能 drain
  - 不产生死锁

## 日志

- Log4j2 配置在 `src/main/resources/log4j2.xml`。
- 默认 INFO 级别；`--verbose` 提升到 DEBUG。
- 正常流程至少记录：
  - 命令启动和整体完成
  - upload/scan/verify 阶段开始和完成
  - 核对哈希、服务端比对阶段开始和完成
  - HTML 报告输出路径
- 默认日志目录 `${sys:user.dir}/.immich-uploader/logs`；通过 `-Dlog.dir` 覆盖。
- 日志按新进程和 10 MB 滚动。
- `fileIndex="nomax"`，不自动删除旧日志。
- 不记录 API Key。
- 新增错误路径时优先记录上下文和异常堆栈，而不是只打印一句“失败”。

## Git 约定

- 提交前运行：

```bash
mvn clean test
mvn package
```

- 不提交：

```text
target/
.immich-uploader/
.tmp/
logs/
*.log
测试媒体/TSV/HTML/状态目录
API Key 和凭据
```

- 提交信息使用简短祈使句，说明改了什么，例如：

```text
Add streaming upload pipeline
Fix Live Photo pair precheck
Update sender CLI option
```

- 一个提交只做一类变更；不要把格式化、重构和功能混在一起。

## 文档约定

- 用户可见行为变化更新 README。
- 架构/线程模型/设计权衡变化更新 DESIGN.md。
- 工程约定变化更新 CONTRIBUTING.md。
- 不在文档中写入真实服务器地址以外的敏感信息；API Key 一律用占位符。
