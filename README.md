# Immich Batch Uploader

Immich Batch Uploader 是一个 Java 命令行工具，用于把本地图片、视频和 Live Photo 批量、可恢复地上传到 [Immich](https://immich.app/)。

它面向大目录和长期照片库：扫描、SHA1、预检和上传形成流水线，状态写入追加式 TSV，上传结果生成自包含 HTML 报告。

## 功能特性

- **批量上传**：支持一个或多个目录，递归扫描，图片和视频混合处理。
- **流式流水线**：ForkJoinPool Reader 扫描目录并读取/哈希文件，上传任务进入有界 Sender 队列，读盘、预检和 HTTP 上传相互重叠。
- **断点续传**：成功记录写入 TSV；文件大小或修改时间未变时自动跳过，失败项下次重试。
- **服务端预检**：按目录批量调用 Immich `bulk-upload-check`，提前跳过 duplicate 和不支持格式。
- **SHA1 核对**：上传后可自动核对本地 SHA1、服务端资产和 Live Photo 关联；也可单独运行 `verify`。
- **Live Photo 配对**：同目录、同文件名主干的图片 + 视频自动配对，图片通过 `livePhotoVideoId` 关联视频。
- **可控内存**：默认只缓存不超过 20 MB 的文件；大文件流式上传。任务窗口和 Sender 队列有界。
- **实时进度**：控制台显示扫描、读取/哈希、预检、上传、核对进度。
- **HTML 报告**：概览、成功、失败、跳过和核对报告，可离线打开、筛选和打印。
- **日志**：Log4j2 只写入 `./.immich-uploader/logs/immich-uploader.log`；新进程启动和达到 10 MB 时滚动，不自动删除历史日志。正常流程记录命令启动/完成、上传/扫描/核对阶段开始与完成、报告路径；上传、读取/哈希失败会记录 ERROR 和异常堆栈。
- **线程级进度**：显示 Reader/Sender 线程当前文件、大小、速率和百分比。
- **安全**：API Key 不写入日志、TSV 或 HTML；主程序不包含删除或修改资产的业务代码。

## 环境要求

- Java 17 或更高版本（开发和测试使用 Java 21）。
- Maven 3.9+。
- 可访问的 Immich 服务，建议使用近期版本；当前接口按 Immich v1.136 文档核对。

构建：

```bash
mvn package
```

可执行 fat jar：

```text
target/immich-uploader.jar
```

运行测试：

```bash
mvn test
```

## 快速开始

推荐通过环境变量提供服务器和 API Key，避免密钥进入命令历史：

```bash
export IMMICH_SERVER_URL=http://192.168.1.10:2283
export IMMICH_API_KEY=your-api-key

java -jar target/immich-uploader.jar upload --dir "D:\Photos"
```

Windows PowerShell：

```powershell
$env:IMMICH_SERVER_URL = "http://192.168.1.10:2283"
$env:IMMICH_API_KEY = "your-api-key"

java -jar target/immich-uploader.jar upload --dir "D:\Photos"
```

也可以显式传参：

```bash
java -jar target/immich-uploader.jar upload \
  --server http://192.168.1.10:2283 \
  --api-key "your-api-key" \
  --dir "D:\Photos"
```

默认状态目录是 `.immich-uploader`，记录和报告位于其下。

## 命令

### upload

```bash
java -jar target/immich-uploader.jar upload \
  --dir "D:\Photos" \
  --dir "D:\Videos" \
  --sender-threads 2
```

常用参数：

| 参数 | 说明 |
|---|---|
| `-d, --dir, --folder` | 目录，可重复指定；也可作为位置参数 |
| `--no-recursive` | 不递归子目录 |
| `--follow-links` | 跟随符号链接 |
| `--media ALL\|IMAGE\|VIDEO` | 只处理图片或视频，默认全部 |
| `--extensions jpg,png,mp4,...` | 覆盖默认扩展名清单，并自动关闭全文件模式 |
| `--no-all-files` | 关闭默认全文件模式，只扫描允许清单内或可识别的媒体 |
| `--no-magic` | 关闭文件头识别，仅按扩展名/MIME 判断 |
| `--live-photo AUTO\|STRICT\|OFF` | Live Photo 配对策略，默认 `AUTO` |
| `--sender-threads` | Sender 上传线程数，默认 `2` |
| `--reader-threads` | Reader/ForkJoin 线程数；默认自动（SSD 4，HDD/未知 1） |
| `--precheck-batch-size` | 每个 Reader 本地预检批上限；默认按堆内存 `maxMemory/2/cache/readers` 计算，最大 500 |
| `--cache-max-file-size` | 单文件缓存上限，默认 `20M`；超过后流式上传 |
| `--sender-queue-length` | Sender 有界队列长度，默认 `32`；队列满时 Reader 阻塞 |
| `--batch-size` | `verify` 使用的 bulk-check 批次大小，默认 500 |
| `--device-id` | Immich deviceId，默认使用主机名 |
| `--no-resume` | 忽略已有记录，重新处理所有文件 |
| `--no-verify` | 上传后不自动核对 |
| `--no-verify-links` | 不检查 Live Photo 关联 |
| `--verify-scope RUN\|ALL` | 自动核对本次上传或全部历史记录 |
| `--state-dir` | 状态目录，默认 `.immich-uploader` |
| `--record` | 自定义 TSV 记录文件 |
| `--report-dir` | 自定义 HTML 报告目录 |
| `--dry-run` | 只扫描，不联网、不写记录 |
| `--insecure` | 关闭 TLS 证书校验（仅测试） |
| `--verbose` | DEBUG 日志和异常堆栈 |

### verify

重新计算记录中资产的本地 SHA1，调用 Immich bulk-check 比对，并检查 Live Photo 关联。

```bash
java -jar target/immich-uploader.jar verify \
  --server http://192.168.1.10:2283 \
  --api-key "$IMMICH_API_KEY"
```

常用参数：

```text
--record <path>          记录文件
--concurrency <n>        核对哈希并发数
--batch-size <n>         bulk-check 批次
--verify-links           检查 Live Photo 关联
--no-verify-links        不检查关联
--strict                 回收站、assetId 不一致等警告也视为失败
```

### scan

只扫描目录，不联网：

```bash
java -jar target/immich-uploader.jar scan --dir "D:\Photos"
```

## 扫描与媒体识别

默认扩展名清单覆盖常见图片、视频、RAW 和相机/无人机格式，扩展名大小写不敏感。

- 图片：`jpg/jpeg/png/gif/webp/bmp/tif/tiff/heic/heif/avif/jxl/jp2/psd/svg/dng/cr2/cr3/nef/arw/raf/rw2/orf/pef/...`
- 视频：`mp4/m4v/mov/qt/avi/mkv/webm/wmv/flv/mpg/mpeg/m2ts/mts/3gp/3g2/mxf/insv/insp/lrv/braw/crm/...`
- 有歧义的扩展名（如 `.ts`、`.raw`、`.h264`）会检查文件头，避免把源码误判为视频。
- **全文件模式默认开启**：所有普通文件都进入候选；无法识别的文件标记为“其他”，由 Immich 决定是否接受。
- `--no-all-files` 只处理允许清单内或可识别的媒体。
- `--extensions` 提供自定义扩展名清单，并自动关闭全文件模式。
- Immich 返回 `unsupported-format` 或 HTTP 400 不支持格式时，进入“跳过清单”，不计为上传失败。
- `bulk-upload-check` 只携带 asset id 和 checksum，不携带扩展名/格式，因此预检不会判断 unsupported；格式不支持只会在上传响应中出现。

## Live Photo

默认 `--live-photo AUTO`：

- 同目录、同文件名主干（不区分大小写）的图片和视频配对。
- 图片候选：`heic/heif/hif/jpg/jpeg/jpe/jfif`
- 视频候选：`mov/qt/mp4/m4v`
- `STRICT` 只配对 HEIC/HEIF/HIF + MOV/QT。
- 同主干存在多张图片或多个视频时，不自动猜测，保持普通上传并给出警告。
- 已关联其他视频的图片不会被覆盖，报告为冲突。

上传流程：

1. 视频先按默认可见性上传。
2. 图片上传时携带 `livePhotoVideoId`；Immich 建立关联后自动隐藏视频。
3. 已存在的图片可以通过 `PUT /api/assets/{id}` 补建关联。
4. 图片成功但补链失败时写入 `link-failed` 记录，`livePhotoVideoId` 留空；下次运行只补链，不重传。

## 断点续传与记录

默认记录：

```text
.immich-uploader/upload-record.tsv
```

- 追加式 TSV，带文件锁。
- 成功记录写入后立即持久化。
- 列包含状态、媒体类型、Live Photo 角色、pairKey、路径、大小、修改时间、SHA1、assetId、livePhotoVideoId、服务器等。
- 只有完整成功项会被跳过：
  - 普通图片/视频：状态成功且有 assetId。
  - Live Photo 图片：还必须有关联视频 ID。
- 文件大小或修改时间变化时会重新处理。
- 上传/哈希失败不写入成功记录，下次自动重试。
- 补链失败会追加一条 `link-failed` 修正记录，`livePhotoVideoId` 为空；该路径的最新记录不完整，下次只重试补链，不重传。
- TSV 是追加式的，因此补链失败时原始行数可能多于文件数；判断断点状态时按路径取最新记录。

## 控制台进度

上传开始时会输出本次计算出的配置：

```text
上传配置: readers=1 senders=2 precheckBatch=25 senderQueue=32 cacheMax=20.0 MB heap=1.0 GB
```

随后上传阶段会显示：

```text
[上传] 已处理=200  读=132.2 MB/s  传=33.1 MB/s  上传=188 重复=12 失败=0 补链失败=0 跳过=0
```

文件处理口径固定为：

```text
已处理 = 上传 + 重复 + 失败 + 跳过
```

其中：

- `上传`：实际创建成功的资产数。
- `重复`：预检 duplicate 和服务端 duplicate 的合计。
- `失败`：读取/哈希/上传失败的文件数。
- `补链失败`：Live Photo 补链失败次数，单独显示，不重复计入文件处理数。
- `跳过`：格式不支持、回收站 duplicate、断点续传跳过等。

命令结束时概要中的 `失败` 是失败清单总数，包含补链失败；控制台进度中的 `失败` 只表示文件失败，`补链失败` 单独统计。

读取/上传速率按实际读写字节统计，不只统计成功上传的字节：

- `读`：Reader 读取/哈希的累计速率。
- `传`：Sender 实际写出的 HTTP 请求体速率。

交互式终端按“统计行 + 线程活动行”整个块重绘，不会不断追加新行；不支持 ANSI 的普通控制台退化为单行刷新。

上传阶段始终显示全部 Reader/Sender 槽位，空闲线程显示“空闲”：

```text
[上传] 已处理=100 ...
  [reader-1] D:\Photos\a.jpg  4.0 MB  51.1 MB/s  100%
  [reader-2] 空闲
  [sender-1] D:\Photos\b.jpg  4.0 MB  48.2 MB/s  37%
  [sender-2] 空闲
```

线程名固定为：

```text
reader-1, reader-2, ...
sender-1, sender-2, ...
```

线程完成后不会消失，而是回到“空闲”。活动行中的长路径会保留文件名和末尾目录，前面用 `...` 省略；HTML/TSV/日志仍保留完整路径。核对阶段显示已知总数百分比，并在阶段结束时打印最终 `100% 完成`。

## HTML 报告

默认位置：

```text
.immich-uploader/reports/report-YYYYMMDD-HHmmss.html
```

包含：

- **概览**：服务器、版本、目录、扫描/跳过/上传/重复/失败数量、Live Photo 统计；`哈希文件` 按文件数统计，Live Photo 对计两个资产。
- **成功清单**：按文本、媒体类型、上传状态筛选。
- **失败清单**：按文本、媒体类型、阶段筛选。
- **跳过清单**：路径、扩展名、阶段、跳过原因。
- **核对报告**：SHA1、Live Photo 关联结果。

报告是自包含 HTML，不依赖外部 CDN，可离线保存和打印。

## 日志

日志默认输出到进程工作目录：

```text
./.immich-uploader/logs/immich-uploader.log
```

可通过 `-Dlog.dir=/custom/path` 覆盖；路径由 Log4j 配置解析，不依赖操作系统路径分隔符。

- Log4j2，UTF-8。
- 默认 INFO 级别，记录命令启动/完成、各阶段开始/完成和 HTML 报告路径。
- `--verbose` 提升到 DEBUG。
- 每个新进程启动时滚动一次。
- 达到 10 MB 时滚动。
- `DefaultRolloverStrategy fileIndex="nomax"`：索引持续递增，不自动删除历史日志。
- 控制台进度和摘要输出到 stdout；日志不混入控制台。
- 未捕获异常和参数错误仍由 CLI 输出到 stderr。
- API Key 不会写入日志。

## API Key 权限

至少需要：

```text
asset.upload
asset.read
asset.update
```

说明：

- `asset.upload`：上传资产。
- `asset.read`：读取资产和 Live Photo 关联信息。
- `asset.update`：给已存在图片补建 Live Photo 关联。
- 缺少 `asset.update` 时，补链请求失败会进入失败清单并计入失败数，但不会导致进程以失败码退出；首次确认缺少权限后，后续需要补链的 Live Photo 不再请求 API，直接记失败并继续。
- 缺少 `asset.upload` 时，进程立即终止，不再继续尝试上传其他图片，退出码为 1。
- 如使用外部测试脚本清理资产，还需要 `asset.delete`；主程序本身不调用删除接口。

## 测试

```bash
mvn test
mvn verify   # 同时执行统计类覆盖率门禁和 JaCoCo 报告
```

统计类 `RunStats`、`RunSummary`、`Progress`、`VerificationSummary` 的行覆盖率为 100%。JaCoCo 报告位于：

```text
target/site/jacoco/index.html
```

当前提交不包含测试媒体、测试记录或集成测试数据。真实 Immich 集成测试应使用专用服务器和专用 API Key，并在测试后由外部脚本清理；API Key 不应提交到仓库。

## 集成测试

标准集成矩阵：

```text
2,000 个文件
1,880 图片 / 120 视频
100 个 Live Photo 对
5 类分支各 20 对：
  new
  video-only
  image-only
  both-unlinked
  both-linked
```

使用真实 Immich 和 `-Xmx1G`，预期结果：

```text
上传 1,880
服务端已存在 120
补链失败 40
Live Photo 已关联 60
核对匹配 1,960
核对关联缺失 40
退出码 0
```

补链失败只来自 `image-only` 和 `both-unlinked`；它们进入失败清单和失败数，不导致进程失败退出。TSV 按路径取最新记录时，40 个失败图片为 `link-failed` 且 `livePhotoVideoId` 为空。

详细工程约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 架构与设计

项目架构、设计原则、权衡和已知问题见 [DESIGN.md](DESIGN.md)。

## 说明

- 视频时长、缩略图等元数据由 Immich 自行提取，不依赖 ffprobe。
- Live Photo 的图片和视频在 Immich 中是两个资产，通过 `livePhotoVideoId` 关联。
- 上传的 Live Photo 视频会被 Immich 设为隐藏，这是 Immich 的正常行为。
- API Key 不会写入日志、TSV 或 HTML。
- 主程序不包含删除或修改资产的业务代码。
