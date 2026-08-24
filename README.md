# Auto-Backup

[![CI](https://github.com/ItzAoneHax/Auto-Backup/actions/workflows/maven.yml/badge.svg)](https://github.com/ItzAoneHax/Auto-Backup/actions/workflows/maven.yml)

基于 [百度网盘开放平台](https://pan.baidu.com/union/doc/) 的 Linux 服务器自动备份工具。

把服务器上的指定目录每天自动打包上传到你的百度网盘, 云端只保留近几天, 每年另留一份永久存档, 全程日志随备份一起上传。

## 功能特性

- **自动备份**: 常驻进程每天在设定时间自动执行; 也支持外部 crontab 调用
- **滚动清理**: 云端只保留近 N 天(默认 3 天)的备份与日志, 到期自动删除
- **年度永久备份**: 每年第一次成功备份后, 在云端复制一份到 `yearly/` 目录(服务端复制, 零上传流量), 永不清理
- **月度永久备份**: 每月第一次成功备份后, 在云端复制一份到 `monthly/` 目录, 永不清理
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
`.tar.gz` 备份和 `.log` 日志都在。恢复数据时直接下载后 `tar -xzf xxx.tar.gz` 即可。

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
| `upload.chunkSizeMB` | `4` | 分片大小, 4-32 且为 4 的倍数 |
| `upload.splitSizeMB` | `0` | 归档分卷大小(MB), 0 不分卷; 超过单文件上限时开启, 见下方说明 |
| `upload.retries` | `3` | 分片失败重试次数 |

## 云端目录结构

```
/apps/你的应用目录名
├── daily/                              # 近 N 天滚动(自动清理)
│   ├── data-2026-08-23_030000.tar.gz   # 备份(目录名-日期时间)
│   └── backup-2026-08-23_030000.log    # 同次运行的日志
├── monthly/                            # 每月一份, 永久保留
│   └── data-2026-08-24_030000.tar.gz   # 复制自当月首份成功备份(保留原文件名)
└── yearly/                             # 每年一份, 永久保留
    └── data-2026-08-24_030000.tar.gz
```

**Q: yearly/monthly 里的快照文件名为什么是完整日期?**
快照是当月/当年第一份成功备份在云端的副本(服务端复制, 不重新上传、秒级完成), 因此保留原始文件名。旧版本快照命名为 `name-年份.tar.gz`, 同样有效。

## 常见问题

**Q: `login` 提示 redirect/error?**
确认 AppKey/SecretKey 正确, 且应用已通过审核、开通了网盘接口权限。

**Q: 上传报 errno=-6 / 20016 / 31045?**
令牌无效或过期(111 不是令牌错误, 是"有其他异步任务冲突", 程序已自动重试)。正常会自动刷新; 若长期停机导致 refresh_token 也失效, 重新执行 `login` 即可。

**Q: 单个备份能有多大?**
官方限制与账号等级挂钩: 普通用户分片固定 4MB、单文件上限 4GB; 会员分片最大 16MB、单文件上限 10GB; 超级会员 32MB、20GB。超过单文件上限的目录可开启归档分卷(`upload.splitSizeMB`, 建议普通 3900 / 会员 9500 / 超级会员 19500): 归档被切成 `xxx.tar.gz.part001、.part002...` 多个文件分别上传, 恢复方法见下一问(官方接口也不支持空文件上传, 本程序的归档与日志文件始终非空, 不受影响)。

**Q: 分卷备份怎么恢复?**
文件名不带 `.partNNN` 后缀的备份就是一个完整的 tar.gz, 下载后可直接用 WinRAR/7-Zip/tar 解压。
带 `.partNNN` 的多分卷备份, 需把同一归档的**所有分卷**下载到同一目录(缺一不可), 先按序号拼接成一个文件再解压:

Linux / macOS / Git Bash(通配符自动按序号排序, 推荐):
```bash
cat xxx.tar.gz.part* > full.tar.gz
tar -xzf full.tar.gz        # 解出的目录结构与原始一致
```

Windows cmd(分卷少时逐个列出, 用 + 连接):
```cmd
copy /b xxx.tar.gz.part001+xxx.tar.gz.part002+xxx.tar.gz.part003 full.tar.gz
```

拼接得到的 `full.tar.gz` 即可用 WinRAR/7-Zip 打开解压。注意: 分卷文件名中日期时间相同才属于同一份归档; 拼接顺序必须按序号, `cat` 通配符已保证这一点, `copy /b` 需要自己按序号书写。


**Q: 备份太慢?**
百度对普通会员有限速/QPS 限制; 大文件可调大分片, 超级会员更快。

**Q: 会不会误删网盘上其他文件?**
不会。程序只管理 `pan.remoteDir` 下 `daily/` 中的、文件名内嵌 `yyyy-MM-dd` 日期且早于保留期限的文件; `yearly/` 与其他文件一律不动。

**Q: 怎么恢复?**
在网盘(或客户端)下载 `.tar.gz` 后: `tar -xzf data-2026-08-23_030000.tar.gz`, 解出的目录结构与原始一致。

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
