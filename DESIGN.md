# Design

本文描述 Immich Batch Uploader 的架构、设计原则、关键权衡、已遇到的问题和后续方向。

## 1. 目标

把本地大目录尽可能稳定、可恢复地上传到一个 Immich 服务，同时：

- 不把整个文件库读进内存
- 不因为单文件失败中断整个批次
- 支持断点续传
- 支持 Live Photo
- 生成可审计的 TSV 和 HTML
- 主程序不执行删除或修改资产的业务操作

## 2. 总体架构

```text
CLI
 │
 ├─ scan：全量扫描，仅供 scan/dry-run
 └─ upload：流式处理
      │
      ▼
 ForkJoinPool（Reader 数）
      │
      └─ DirectoryTask
           ├─ 扫描当前目录直接文件
           ├─ 目录内 Live Photo 配对
           ├─ 生成 Normal / LivePhoto 任务
           ├─ 本地 PrecheckBatch
           └─ finally flush 剩余任务
                │
                ▼
         Sender 线程池
         有界 ArrayBlockingQueue
                │
                ▼
         上传 / 写 TSV / 阶段统计
```

### Reader

- 使用专用 `ForkJoinPool`，parallelism 等于 Reader 数。
- 每个 `DirectoryTask` 负责一个目录的直接文件。
- 子目录创建子 `DirectoryTask` 并 `fork`。
- 上传模式不使用旧的 `MediaScanner.scan`；它在 `DirectoryTask` 内直接遍历目录。
- 每个目录任务有自己本地的 `PrecheckBatch`，任务结束时在 `finally` 中 flush。

### Sender

- 独立 `ThreadPoolExecutor`。
- 默认 2 个 Sender。
- 使用有界 `ArrayBlockingQueue`，默认队列长度 32。
- 队列满时由阻塞拒绝策略 `BlockingQueuePolicy` 在 Reader 线程中等待空位，不使用 `CallerRunsPolicy`。
- 没有 permits 或额外 semaphore；并发边界就是 Reader 本地预检批 + Sender 队列 + Sender 线程数。

### 任务模型

```text
UploadTask
  ├─ Normal
  ├─ LivePhoto
  └─ AssetPart（LivePhoto 的图片/视频部分）
```

任务创建时尽量填入后续要用的信息：

- 路径、文件名、扩展名
- kind / role / pairKey
- size / createdAt / modifiedAt
- contentType
- SHA1（Base64 和 Hex）
- deviceAssetId
- 缓存字节或流式路径

Sender 不再重新 `stat`、`probeContentType`、计算 SHA1。TSV 也直接使用任务字段。

### 预检

- 每个 `DirectoryTask` 的 Reader 线程在本地 `PrecheckBatch` 中直接预检。
- 预检批次大小在启动时按堆内存计算：

  ```text
  max(1, min(500, (maxMemory / 2) / cacheMaxFileSize / readers))
  ```

  `--precheck-batch-size` 可以在该上限内覆盖；硬上限 500。
- `bulk-upload-check` 只发送 id + checksum，不发送文件名/格式，因此**预检不会返回 unsupported-format**；unsupported 只会由上传接口返回。
- Live Photo 的图片和视频 hash 始终放入同一个 `bulkUploadCheck` 请求；这一点有 `UploadPipelinePrecheckTest` 覆盖。
- 预检后才生成上传任务：
  - `duplicate` 普通文件直接写记录，不进入 Sender。
  - Live Photo 的已有 assetId 直接写入 `UploadTask.LivePhoto`，不再经过全局 map。
- Live Photo 的一条腿在上传时返回 unsupported：
  - 只跳过该腿；
  - 另一条腿按普通图片/视频上传；
  - 不再把整个 Live Photo 对一起跳过。
- 补链失败：
  - `LIVE_PHOTO_LINK` 进入失败清单，并计入失败数；
  - 标记为非致命失败（`RunSummary.addNonFatalFailure`）；
  - 不导致进程以失败码退出；
  - 首次确认缺少 `asset.update` 后，后续需要补链的 Live Photo 不再请求 API，直接记失败并继续；
  - 补链失败会追加一条 `link-failed` 记录，`livePhotoVideoId` 为空，避免下次 resume 跳过；
  - 若核对阶段出现对应的 linkMissing，只忽略该 linkMissing，不忽略其他核对失败。
- 统计拆分：
  - `已处理 = 上传 + 重复 + 文件失败 + 跳过`；
  - `补链失败` 单独计数，不计入文件处理数；
  - 命令结束概要的 `失败` 是失败清单总数，包含补链失败。
- Live Photo 哈希计数按两个资产计：图片和视频各计一次；报告中的 `哈希文件` 因此等于扫描文件数。
- 控制台只输出进度和摘要；Log4j 日志只写文件，不附加 Console appender。
- 交互式终端用 ANSI cursor-up 清除整个“统计行 + 线程活动行”块后重绘，避免多行活动导致每次更新追加新行；不支持 ANSI 的控制台退化为单行统计刷新。
- 上传阶段注册固定的 `reader-*` / `sender-*` 槽位；空闲线程也显示一行“空闲”，不因 `readerFinished/senderFinished` 消失。
- 控制台活动行只展示缩短后的路径（保留文件名和末尾目录），不改变 HTML、TSV 和日志中的完整路径。
- 上传权限失败：
  - 403 响应明确缺少 `asset.upload` 时，设置 fatal；
  - 不再提交/执行后续上传；
  - 进程以退出码 1 结束。
- 预检 API 失败时记录 warning，本批直接上传，由上传接口兜底判断。

### 缓存与背压

- 单文件缓存上限默认 20 MB；超过上限使用流式上传。
- 没有 permits。
- 队列有界，Reader 在队列满时阻塞，形成背压。
- 启动日志会记录实际计算值：`precheckBatch`、`senderQueue`、`cacheMaxFileSize`、`maxHeap`、来源（heap/override）。
- 内存主要由以下部分组成：
  - 每个 Reader 当前未 flush 的预检批次
  - Sender 队列中的任务
  - 正在上传的任务
- 任务完成后不保留全局 `UploadTask` 引用。

## 3. Reader 数选择

启动时：

```text
显式 --reader-threads
  >
OS 元数据识别
  >
回退 1
```

当前实现：

- Windows：`MSFT_PhysicalDisk.MediaType`
- SSD / NVMe → 4
- HDD / 未知 / 识别失败 → 1
- 多目录中任一 HDD/未知 → 1

不做微基准，不读取媒体文件做探测。

这是有意的保守设计：HDD 多 Reader 会增加寻道，收益不稳定；识别失败时优先保证顺序读。

## 4. 设计原则

### 简单优先

- 使用标准库和现有 API。
- 能用有界队列和线程池解决，不引入额外调度框架。
- 不为假想的未来需求增加抽象层。
- 配置项只在确有独立调优价值时暴露。

### 阶段状态与汇总状态分离

- Reader/Sender 只更新 `Progress` 的阶段状态。
- 运行结束由调用者把阶段快照合并到 `RunSummary`。
- 避免同一个统计值在多个对象中并发写入。

### 任务不可变

- 任务创建后不修改。
- 预检结果通过任务构造参数（Live Photo 的两个 assetId）传入，不使用全局 map。
- 预检后的任务构造和提交都在 Reader 线程内完成。
- 避免 Sender/Reader 并发修改同一任务。

### 有界队列和显式背压

- 不使用无界任务队列承载文件内容。
- 队列满时 Reader 阻塞，而不是继续读盘。
- 不使用 `CallerRunsPolicy` 制造额外上传线程。

### 主程序不删除资产

- 主程序只上传、读取、建立关联。
- 删除/修改资产的清理逻辑放在外部测试脚本。
- 避免误删用户照片。

### 默认值可解释

- `--sender-threads` 默认 2。
- `--reader-threads` 自动，HDD 1 / SSD 4。
- `--cache-max-file-size` 默认 20 MB。
- 这些值是在真实照片库和 HDD/SSD 测试基础上确定的，不追求理论最大并发。

## 5. 关键权衡

### 为什么 HDD 只用 1 Reader

HDD 顺序读接近 70 MB/s，但多个 Reader 同时访问会造成寻道竞争。上传阶段的瓶颈通常还包括 Immich 单文件处理和 HTTP 响应，单纯增加 Reader 不一定提高吞吐。

### 为什么默认只缓存 20 MB

照片通常 3–8 MB，20 MB 能覆盖绝大多数照片；100 MB 视频不缓存，避免内存被单个文件放大。缓存上限和任务窗口共同约束内存。

### 为什么按目录而不是按文件 fork

按目录 fork 能自然完成 Live Photo 目录内配对，任务数量也不会爆炸。代价是单层大目录可能只有一个 DirectoryTask，无法利用 Reader 并行。

这是当前实现的已知限制。不要在未验证前简单改成“每个文件 fork”，否则任务数和内存会快速上升。

### 为什么预检按目录批次

- 减少 `bulk-upload-check` 请求数。
- 与 Live Photo 目录内配对一致。
- 批次在目录结束或达到上限时 flush，不依赖全局协调器。

### 为什么记录仍是同步追加

目前 TSV 追加使用文件锁和 flush，逻辑简单、可恢复。若未来测出 fsync 是瓶颈，再考虑单写线程和 group commit；不应先引入异步持久化而破坏断点语义。

## 6. 已知问题和经验

1. **单层目录并行度不足**  
   当前 ForkJoin 只按子目录 fork。一个包含数千文件的平铺目录，只有一个 DirectoryTask；增加 Reader 不会自动并行。

2. **上传速率不等于纯磁盘读速率**  
   `读` 是 Reader 实际读取/哈希字节，`传` 是 Sender 实际写出的请求体字节。两者都按阶段耗时计算，不是磁盘顺序读基准。

3. **Immich 回收站 duplicate**  
   已存在于回收站的校验和仍会让 bulk-check 返回 duplicate；直接重传不会恢复资产。需要外部清理回收站或接受跳过。

4. **API Key 权限边界**  
   `asset.upload` 不等于 `asset.delete`/`asset.update`。Live Photo 补链需要 update；测试清理需要 delete。主程序不应假设这些权限存在。

5. **Live Photo 自动隐藏**  
   视频先上传、图片建立关联后，Immich 会自动隐藏视频。不要预先假设视频可见。

6. **控制台编码**  
   Windows `cmd` 代码页可能不是 UTF-8。使用 `PrintWriter(System.out, true)` 继承 `PrintStream` 字符集，不强制 UTF-8，也不手工猜测。

7. **测试数据容易污染服务器**  
   大文件、重复校验和、Live Photo 和回收站会让结果难以解释。集成测试必须使用专用服务器/Key，并在测试后由外部脚本清理。

8. **不要为预检 ID 建全局任务映射**  
   早期设计用 `existingIds` 保存预检 assetId，任务完成后还必须手工清理，容易形成生命周期泄漏。当前实现改为：预检完成后直接构造带 `imageAssetId`/`videoAssetId` 的 `UploadTask.LivePhoto`，任务本身只活到 Sender 完成，不需要全局 map。

## 6.1 内存问题定位

判断 OOM 时先区分三类内存：

1. **单文件缓存**：受 `--cache-max-file-size` 限制，大文件流式上传。
2. **在途任务窗口**：受 Reader 本地预检批和 Sender 有界队列限制。
3. **全局引用**：任务完成后不能继续被全局 map/list 引用。

排查 OOM 时优先抓取 heap dump 检查：

- `UploadTask` 及 `byte[]` 的 retained size
- `PrecheckBatch` 的 pending 列表
- Sender 队列
- `resumeIndex`
- `RunSummary.successes`
- `Progress.activities`

线程级进度：

- Reader 调用 `readerStarted/readerProgress/readerFinished`，哈希流式回调更新已读字节。
- Sender 调用 `senderStarted/senderProgress/senderFinished`，HTTP RequestBody 包装器更新已写字节。
- 控制台每行显示 `[角色:线程名] 文件 大小 速率 百分比`；线程完成即移除。

## 7. 后续方向

按优先级：

1. **平铺目录分片**  
   把 DirectoryTask 的 ScanUnit 切成固定大小的 ProcessChunk，让单层大目录也能并行；必须同时限制 chunk 数量和缓存。

2. **存储识别扩展**  
   增加 Linux/macOS 元数据探测；当前 Windows 之外回退 1 是安全的，但不是最优。

3. **真实传输计时**  
   在流式 RequestBody 上统计实际写出的字节，区分“读盘慢”“等待服务端”和“完成吞吐”。

4. **Sender 并发矩阵**  
   在 HDD 1 Reader 下测试 2/4 Sender、不同目录批大小，确认默认值。

5. **异步 HTTP 或更细的流水线**  
   只有确认服务端延迟是主瓶颈后再做；不要为了异步而异步。

6. **记录写入优化**  
   测量 TSV fsync 占比，必要时使用单写线程和 group commit。

7. **补充自动化集成测试**  
   使用专用测试服务器和外部清理脚本，覆盖普通媒体、100 MB 视频、Live Photo、断点续传和核对。

## 8. 代码地图

```text
core/
  UploadPipeline        上传流水线、DirectoryTask、预检、Sender
  UploadTask            任务模型
  ScanConfig            上传扫描配置
  StorageDetector       Reader 数识别
  MediaScanner          scan/dry-run 全量扫描
  LivePhotoPairer       目录内配对
  Progress/RunStats/RunSummary
  VerificationService

immich/
  ImmichClient          Immich API 客户端
  AssetUploadRequest    支持文件或缓存字节上传

state/
  RecordStore           TSV 记录和 resume 快照
  RecordEntry

report/
  HtmlReportWriter     自包含 HTML 报告

cli/
  UploadCommand         upload 参数和编排
  VerifyCommand         verify
  ScanCommand           scan
```

## 9. 结论

当前设计优先保证：

```text
不 OOM
不丢任务
不误删资产
可恢复
可审计
```

性能优化必须建立在真实测量上。尤其是 HDD 场景，增加并发不必然提高完成吞吐；先确认 Reader、预检、Sender、服务端和记录各占多少时间，再决定是否增加并行度。
