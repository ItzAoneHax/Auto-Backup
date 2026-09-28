# Auto-Backup

[![CI](https://github.com/ItzAoneHax/Auto-Backup/actions/workflows/maven.yml/badge.svg)](https://github.com/ItzAoneHax/Auto-Backup/actions/workflows/maven.yml)

基于 [百度网盘开放平台](https://pan.baidu.com/union/doc/) 的 Linux 服务器自动备份工具。

把服务器上的指定目录每天自动打包上传到你的百度网盘, 云端只保留近几天, 每年另留一份永久存档, 全程日志随备份一起上传。

## 功能特性

- **自动备份**: 常驻进程每天在设定时间自动执行; 也支持外部 crontab 调用
- **临时单次备份**: `java -jar auto-backup.jar run /任意目录` 不改配置即可临时备份指定目录(可多个空格分隔), 固定全量上传到当日 daily 目录, 不影响定时任务与增量备份链
- **每天一个完整文件**: 默认每天全量, 每个备份源(服务器)当天在云端就是一个完整的 `.tar.zst`, 下载解压即用, 无需拼接
- **zstd 多线程压缩**: 默认 `tar.zst` 归档(线程数自动取 CPU 核数, 比单线程 gzip 快一个数量级, 压缩率略优), 可配置回退 `gzip` 兼容旧版
- **分片并发上传**: 官方 FAQ 明确支持分片并发, 默认 4 并发(1-16 可调), 显著缩短大文件上传时间; 分片级重试+指数退避兜底
- **可选增量模式**: `backup.incremental.enabled=true` 后每天只上传有变化的文件(提速明显), 且程序会把"最近一次全量 + 其后增量"云端复制进当天日期文件夹, 文件夹仍然自包含, 一条命令解出完整服务端
- **按日期与服务器分目录**: 云端每天一个 `yyyy-MM-dd` 日期文件夹, 每个备份源(服务器)在其下单独一个子文件夹, 一目了然
- **滚动清理**: 云端只保留近 N 天(默认 3 天; 增量模式下自动取 `max(N, 全量间隔+2)`)的日期文件夹与日志, 保证增量恢复链完整, 到期整夹自动删除
- **年度永久备份**: 每年第一次成功备份后, 在云端复制一份到 `yearly/<年份>/<服务器>/` 目录(服务端复制, 零上传流量), 永不清理
- **月度永久备份**: 每月第一次成功备份后, 在云端复制一份到 `monthly/<年-月>/<服务器>/` 目录, 永不清理
- **快照自动补全**: 每轮备份收尾时核对 daily 与 monthly/yearly 的备份对象集合, 月中新加的备份源、曾复制中断或失败的对象会自动从 daily 云端复制补全; 同月内已留档的批次不会重复复制
- **运行日志云端留存**: 每次备份生成 `backup-<日期时间>.log`, 备份完成后上传, 与备份文件同期清理
- **无浏览器授权**: OAuth 设备码方式, 服务器上一次 `login` 即可, 令牌自动续期
- **分片上传 + 秒传 + 断片重试**: 按官方 precreate → superfile2 → create 流程, 4/8/16/32MB 分片
- **配置集中**: 所有序项都在 `config/application.properties`, 模板带中文注释
- **零侵入**: 单个可执行 jar + JRE 17 即可运行, 无数据库无 Web 服务

## 程序编写完成后的部署步骤(从零到跑起来)

以下是你把代码构建好之后, 在 **Linux 服务器** 上要做的事。

### 第 0 步: 准备

- 一台 Linux 服务器(Ubuntu/Debian/CentOS 均可), 能访问外网;
- 安装 Java 17 或更高版本:
  ```bash
  # Ubuntu / Debian
  sudo apt update && sudo apt install -y openjdk-17-jre-headless
  # CentOS / RHEL / Rocky
  sudo dnf install -y java-17-openjdk-headless
  java -version   # 确认输出 17.x 或更高
  ```
- 安装 zstd(默认压缩格式 `.tar.zst` 解压时需要; 服务器不装也能正常备份, 只是恢复时要另找环境解压):
  ```bash
  sudo apt install -y zstd     # 或 sudo dnf install -y zstd
  ```
- 已在[百度网盘开放平台控制台](https://pan.baidu.com/union/console)创建**个人网盘应用**,
  拿到 `AppKey` / `SecretKey`, 并记下**应用目录名**(控制台"基本资料"里, 不是应用名称)。

### 第 1 步: 构建并上传 jar

在开发机(本仓库根目录)构建:

```bash
./mvnw clean package        # 没有本地 Maven 时用自带 wrapper; 或用你自己的 mvn
```

产物为 `target/auto-backup.jar`(单个可执行 fat jar)。上传到服务器:

```bash
scp target/auto-backup.jar user@your-server:/opt/auto-backup/
scp config/application.properties.example user@your-server:/opt/auto-backup/config/application.properties
scp scripts/auto-backup.service user@your-server:/tmp/
```

### 第 2 步: 修改配置

在服务器上编辑 `/opt/auto-backup/config/application.properties`:

```bash
cd /opt/auto-backup
vi config/application.properties
```

必填四项:

| 配置项 | 说明 |
| --- | --- |
| `pan.appKey` | 控制台应用凭证 |
| `pan.secretKey` | 控制台应用凭证 |
| `pan.remoteDir` | 网盘上的备份根目录, 个人应用为 `/apps/你的应用目录名` |
| `backup.sources` | 要备份的本地目录, 逗号分隔可多个 |

其他项(备份时间、保留天数、年度备份开关、分片大小等)都有默认值, 按需调整, 全部说明见文件内注释或下文[配置项一览](#配置项一览)。

### 第 3 步: 首次授权(只需一次)

```bash
cd /opt/auto-backup
java -jar auto-backup.jar login
```

按提示在**你自己的电脑或手机**上打开验证网址、输入验证码、确认授权。
成功后凭证保存在 `config/token.json`, 之后自动续期, 无需再管。

### 第 4 步: 验证

```bash
java -jar auto-backup.jar verify
```

会显示授权账号、网盘容量、远程 daily/yearly 目录现状。

### 第 5 步: 手动试跑一次

```bash
java -jar auto-backup.jar run
```

观察输出的打包/分片上传/清理日志, 然后到网盘 `/apps/你的应用目录名/daily/` 下确认
`.tar.zst` 备份和 `.log` 日志都在。恢复数据时直接下载后 `tar --zstd -xf xxx.tar.zst` 即可
(增量备份的完整恢复方法见[常见问题](#常见问题))。

### 第 6 步: 设为开机自启的常驻服务(systemd)

```bash
sudo cp /tmp/auto-backup.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now auto-backup     # 立即启动并开机自启
systemctl status auto-backup                # 查看状态
journalctl -u auto-backup -f                # 实时看运行日志
```

服务脚本默认每天 `backup.dailyTime`(默认 03:00)触发备份; 若服务器白天才开机错过时间, 启动后会立即补跑当天这一次。

> 不想用 systemd 也可以用系统 cron: `0 3 * * * cd /opt/auto-backup && java -jar auto-backup.jar run`
> (此时无需 daemon 模式, 两种方式二选一即可)。

### 日常维护

| 场景 | 操作 |
| --- | --- |
| 查看每次备份的详细日志 | 服务器 `logs/` 目录, 或网盘 `daily/` 目录里的 `.log` |
| 临时备份某个目录(不改配置) | daemon 运行中在终端/面板控制台输入 `run /path/to/dir`(可多个空格分隔, 含空格路径用引号包裹); 或命令行 `java -jar auto-backup.jar run /path/to/dir`。固定全量上传到当日 daily 目录, 到期随日常清理删除, 不影响定时任务 |
| 查看备份状态 | 面板/终端控制台输入 `status`(上次运行结果、是否执行中); `help` 查看全部控制台命令 |
| 修改备份目录/时间/保留天数 | 改 `config/application.properties` 后 `sudo systemctl restart auto-backup` |
| 长期停用后令牌失效(提示重新 login) | 再执行一次第 3 步, 然后 `sudo systemctl restart auto-backup` |
| 升级程序 | 替换 jar 后 `sudo systemctl restart auto-backup` |

## 配置项一览

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `pan.appKey` | (必填) | 开放平台应用 AppKey |
| `pan.secretKey` | (必填) | 开放平台应用 SecretKey |
| `pan.remoteDir` | (必填) | 网盘备份根目录, 个人应用为 `/apps/应用目录名` |
| `backup.sources` | (必填) | 备份的本地目录, 逗号分隔, 各自独立打包 |
| `backup.excludes` | 空 | 打包排除规则, glob 语法, 如 `*.tmp,cache/**` |
| `backup.dailyTime` | `03:00` | 每日备份触发时间(daemon 模式) |
| `backup.retainDays` | `3` | 云端保留天数(至多留存近 N 天) |
| `backup.yearly.enabled` | `true` | 每年首份成功备份云端复制留档到 `yearly/` |
| `backup.monthly.enabled` | `true` | 每月首份成功备份云端复制留档到 `monthly/` |
| `backup.workDir` | `./work` | 打包临时目录 |
| `backup.logDir` | `./logs` | 本地日志目录 |
| `backup.logRetainDays` | `30` | 本地日志保留天数 |
| `backup.deleteLocalArchive` | `true` | 上传成功后是否删除本地归档 |
| `backup.incremental.enabled` | `false` | 增量备份(可选提速模式): 每天只上传有变化的文件, 默认每天全量单文件 |
| `backup.incremental.fullIntervalDays` | `7` | 增量模式下两次全量备份的间隔天数(1-365) |
| `archive.compressor` | `zstd` | 压缩算法: `zstd`(多线程)或 `gzip`(兼容旧版) |
| `archive.zstdLevel` | `3` | zstd 压缩级别 1-22, 数值越大压缩越狠越慢 |
| `archive.zstdWorkers` | `0` | zstd 线程数, 0 自动取 CPU 核数 |
| `upload.chunkSizeMB` | `4` | 分片大小, 4-32 且为 4 的倍数 |
| `upload.parallelChunks` | `4` | 分片并发上传数 1-16, 官方支持, 越高失败率越高 |
| `upload.splitSizeMB` | `0` | 归档分卷大小(MB), 0 不分卷; 超过单文件上限时开启, 见下方说明 |
| `upload.retries` | `3` | 分片失败重试次数 |

## 云端目录结构

每个备份源目录的**目录名**(如 `/home/mc/myserver` 的 `myserver`)即「服务器名」。

```
/apps/你的应用目录名
├── daily/                                      # 近 N 天滚动(到期整个日期文件夹删除)
│   ├── 2026-08-26/
│   │   └── myserver/
│   │       └── myserver-2026-08-26_030000.tar.zst          # 每天每源一个完整归档
│   └── 2026-08-27/                             # 每天一个日期文件夹
│       ├── myserver/                         # 每个备份源(服务器)一个子文件夹
│       │   ├── myserver-2026-08-27_030000.tar.zst          # 不带 .partNNN 为单卷
│       │   └── mysql_data-2026-08-27_030000.tar.zst.part001  # 分卷归档(超单文件上限时)
│       └── backup-2026-08-27_030000.log        # 同次运行的日志(放日期文件夹根部)
├── monthly/                                    # 每月一份, 永久保留
│   └── 2026-08/
│       └── myserver/
│           └── myserver-2026-08-01_030000.tar.zst   # 复制自当月首份成功备份(保留原文件名)
└── yearly/                                     # 每年一份, 永久保留
    └── 2026/
        └── myserver/
            └── myserver-2026-01-01_030000.tar.zst
```

开启增量模式(`backup.incremental.enabled=true`)后, 增量日的服务器子文件夹里是"复制来的链首全量
+ 复制来的历史增量(带 `.inc`)+ 当日实际上传的增量"若干个文件, 文件夹仍自包含, 恢复方法见[常见问题](#常见问题)。

旧版本(v1.1 及更早)的扁平结构(`daily/data-2026-08-23_030000.tar.gz`)无需处理:
过期文件仍会被自动识别并删除; `monthly/`、`yearly/` 里的旧快照原地保留。

**Q: yearly/monthly 里的快照文件名为什么是完整日期?**
快照是当月/当年第一份成功备份在云端的副本(服务端复制, 不重新上传、秒级完成), 因此保留原始文件名。旧版本快照命名为 `name-年份.tar.gz`, 同样有效。

**Q: 月中在 `backup.sources` 里新加了一个备份源, monthly/yearly 会补上它吗?**
会。新源的第一次成功备份即写入当月/当年快照; 此后每轮备份收尾还会核对一轮——只要 daily 里存在而 monthly/yearly 缺失(新加源、快照复制曾中断或失败), 都会自动从 daily 云端复制补全。同一月份内已留档的批次不会被重复复制。

## 常见问题

**Q: `login` 提示 redirect/error?**
确认 AppKey/SecretKey 正确, 且应用已通过审核、开通了网盘接口权限。

**Q: 上传报 errno=-6 / 20016 / 31045?**
令牌无效或过期(111 不是令牌错误, 是"有其他异步任务冲突", 程序已自动重试)。正常会自动刷新; 若长期停机导致 refresh_token 也失效, 重新执行 `login` 即可。

**Q: 单个备份能有多大?**
官方限制与账号等级挂钩: 普通用户分片固定 4MB、单文件上限 4GB; 会员分片最大 16MB、单文件上限 10GB; 超级会员 32MB、20GB。超过单文件上限的目录可开启归档分卷(`upload.splitSizeMB`, 建议普通 3900 / 会员 9500 / 超级会员 19500): 归档被切成 `xxx.tar.gz.part001、.part002...` 多个文件分别上传, 恢复方法见下一问(官方接口也不支持空文件上传, 本程序的归档与日志文件始终非空, 不受影响)。

**Q: 分卷备份怎么恢复?**
文件名不带 `.partNNN` 后缀的备份就是一个完整的压缩归档(`.tar.zst` 或 `.tar.gz`), 下载后可直接用 WinRAR/7-Zip/tar 解压。
带 `.partNNN` 的多分卷备份, 需把同一归档的**所有分卷**下载到同一目录(缺一不可), 先按序号拼接成一个文件再解压(以 `.tar.zst` 为例, `.tar.gz` 同理):

Linux / macOS / Git Bash(通配符自动按序号排序, 推荐):
```bash
cat xxx.tar.zst.part* > full.tar.zst
tar --zstd -xf full.tar.zst   # 解出的目录结构与原始一致
```

Windows cmd(分卷少时逐个列出, 用 + 连接):
```cmd
copy /b xxx.tar.zst.part001+xxx.tar.zst.part002+xxx.tar.zst.part003 full.tar.zst
```

拼接得到的文件即可用支持 zstd 的工具(WinRAR/7-Zip/tar)打开解压。注意: 分卷文件名中日期时间相同才属于同一份归档; 拼接顺序必须按序号, `cat` 通配符已保证这一点, `copy /b` 需要自己按序号书写。

**Q: 增量备份怎么恢复?**(仅 `backup.incremental.enabled=true` 时适用, 默认关闭)
默认模式下每天就是一个完整归档文件, 下载解压即用, 不涉及本问。开启增量后:
**恢复只需下载"一天的日期文件夹"**——程序每天(增量日)会自动把"最近一次全量 + 其后所有增量"用云端复制补进当天的服务器文件夹(零上传流量), 因此**任意一天的日期文件夹都是自包含的**。操作:

1. 在网盘下载**目标日期的整个日期文件夹**(如 `daily/2026-09-11/`, 各服务器子文件夹都在其中);
2. 进入对应服务器的子文件夹, 把里面的**全部归档**(`.tar.zst`, 含全量与增量)按**文件名顺序**(即文件名内嵌的日期时间顺序)依次解压到同一目录——后解压的覆盖先解压的同名文件, 结果就是当天备份时刻的完整服务端。

一条命令完成(文件名内嵌日期, 通配符展开天然有序):
```bash
for f in myserver-*.tar.zst; do tar --zstd -xf "$f"; done
```

说明与注意:
- 单个归档若带 `.partNNN` 分卷, 先按上一问拼接该归档的所有分卷;
- daily 的保留天数已自动保证链完整(增量模式下取 `max(保留天数, 全量间隔+2)` 天);
- `monthly/`、`yearly/` 里的快照永远是全量归档, 单文件直接解压即完整;
- 增量包不含"当天被删除文件"的墓碑记录, 恢复结果里已被删除的文件仍会存在; 需要精确同步删除时以全量归档为准。

**Q: 备份太慢?**
默认已启用 zstd 多线程压缩(打包快一个数量级)与分片并发上传(默认 4, 可在 `upload.parallelChunks` 调到 8 试试)。上传速度受百度网盘限速影响: 若调大并发仍无改善, 说明限速按账号而非按连接, 只能靠开启增量模式减少每日上传量, 或用 `backup.excludes` 精简备份内容。留意日志中的"第 N 次上传失败"重试记录, 并发过高失败率上升时应回调。


**Q: 会不会误删网盘上其他文件?**
不会。程序只管理 `pan.remoteDir` 下 `daily/` 中本程序自己生成的两类内容: 名字严格为 `yyyy-MM-dd` 的日期文件夹(早于保留期限的整夹删除), 以及旧版扁平结构中文件名内嵌 `yyyy-MM-dd` 日期且早于保留期限的文件; `monthly/`、`yearly/` 与其他一律不动。

**Q: 怎么恢复?**
在网盘(或客户端)下载归档后解压: `tar --zstd -xf data-2026-09-23_030000.tar.zst`(旧版 `.tar.gz` 用 `tar -xzf`), 解出的目录结构与原始一致。增量归档(`.inc`)的恢复见上文。

## 安全注意

- `config/application.properties` 与 `config/token.json` 含凭证, 已被 `.gitignore` 排除, **永远不要提交或外传**;
- 建议服务器上 `chmod 600 config/application.properties config/token.json`;
- 一切传输走 HTTPS。

## 从源码构建与开发

```bash
./mvnw clean package     # 产物 target/auto-backup.jar
./mvnw test              # 运行单元测试
```

要求 JDK 17+。设计文档见 [docs/设计方案.md](docs/设计方案.md)。

## 许可证

本项目以 [GNU Affero General Public License v3.0](LICENSE) 发布。AGPL-3.0 是强 copyleft 许可证：任何人修改本程序并通过网络提供服务，也必须以 AGPL-3.0 开放其修改后的完整源代码。
