# 运维手册

> 本文不含主机名、IP、端口以外的敏感值；网关与数据库的连接参数只存在于 `config/secrets.yml` / `deploy/config/trader.env`。

## 1. 前置条件

- JDK 21（脚本自动探测：`$JDK21_HOME` → `$JAVA_HOME` → `/usr/libexec/java_home -v 21` → `~/Java/jdk-21*.jdk` → 常见 Linux 路径 → PATH），Node 20+。
- PostgreSQL 18 库 `db_trader_dev`（开发）/ `db_trader`（生产），角色 `trader`。数据库只监听服务器本机，开发机经 SSH 隧道（§1a）把本地端口转发到服务器；密码写在 `~/.pgpass`（`chmod 600`），psql 与应用共用。
- 两家网关（IB Gateway、OpenD）同样跑在服务器上，开发机经隧道访问；第 1 期启用连接层时把 host/port 填进 `config/secrets.yml`。
- Maven 本地仓库：`mvnw`（Maven 3.9.x）读 `~/.m2/settings.xml`；若机器上另有独立安装的 Maven 且改过 `localRepository`，两边要指向同一目录，否则 `install-sdks.sh` 装进的构件另一边找不到。

## 1a. SSH 隧道（开发机 → 服务器）

数据库、两家网关、生产实例都只监听服务器本机，开发机上的一切访问（psql、开发实例连网关、Postman 打生产、`check-daily.sh`）都经同一条 SSH 隧道。主机、SSH 端口、用户名不入库，下面用占位符。

**建立**（用 `autossh`，`brew install autossh`；裸 `ssh -L` 断了不会自愈，表现是 `curl` 挂住而不是连接被拒）：

```bash
autossh -M 0 -f -N \
  -o "ServerAliveInterval=30" -o "ServerAliveCountMax=3" -o "ExitOnForwardFailure=yes" \
  -p <ssh端口> \
  -L 11111:localhost:11111 -L 4001:localhost:4001 -L 5432:localhost:5432 \
  -L 8093:localhost:8093 \
  <用户>@<主机>
```

| 转发 | 用途 |
| --- | --- |
| 11111 | OpenD（富途行情 / 交易） |
| 4001 | IB Gateway 实盘端口 |
| 5432 | PostgreSQL（`db_trader_dev` / `db_trader`） |
| 8093 | 本项目生产实例（Postman、`check-daily.sh`、前端联调） |

兄弟项目的 8090 / 8091 若也要用，一并加到同一条命令里；本机只保留**一条**隧道。

三个 `-o` 参数缺一不可：`-M 0` 关掉 autossh 自带的监控端口，改由 ssh 心跳判活；`ServerAliveInterval` + `ServerAliveCountMax` 让半开连接在 90 秒内被判死、触发重建；`ExitOnForwardFailure=yes` 让端口被占用时 ssh 直接失败，而不是留下一条「已连接但没转发」的假隧道。`-f` 由 autossh 自己处理，并把 `AUTOSSH_GATETIME` 置 0，开机或断网恢复后会一直重试。

**检查**：

```bash
pgrep -fl autossh; for p in 11111 4001 5432 8093; do nc -z localhost $p && echo "$p 通" || echo "$p 不通"; done
curl -s --max-time 5 http://127.0.0.1:8093/api/system/info      # 应回 environment=PROD
```

**改转发列表 / 停隧道**：先杀 autossh 再重起，**不要叠着起第二条**——第二条会因端口被占而反复失败，`pgrep` 里看到多个 autossh 就是这个症状（只有一个真正持有 ssh）。只 `pkill ssh` 没用，autossh 会立刻把它拉起来。

```bash
pkill autossh          # 停；随后按上面的命令重起
```

隧道是手动进程，Mac 重启后要重新执行建立命令。本机 Trader Workstation 桌面版若在跑，它是直连服务器的，与隧道无关。

**开发实例走隧道的坑**：Mac 上开着系统级 SOCKS 代理时 JVM 会自动带 `socksProxyHost`，pgjdbc 连 127.0.0.1 报 `UnknownHostException`；`run-local.sh` 与 IDEA 运行配置已带 `-DsocksNonProxyHosts='localhost|127.*|[::1]'`。

**隧道端口全不通、autossh 却在跑**：后台的 autossh 不能输密码、也不能确认主机指纹，ssh 一要交互就失败、autossh 静默重试。
先 `pkill autossh`，前台 `ssh -p <ssh端口> <用户>@<主机> 'echo SSH_OK'` 看提示：问指纹就在服务器上 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` 核对后 yes；
要密码就 `ssh-copy-id -p <ssh端口> <用户>@<主机>`。能免密登录后再起隧道（2026-10-11 换服务器时实际发生）。

## 1b. 服务器环境搭建（从空服务器起）

生产是单机部署：应用、PostgreSQL、OpenD、IB Gateway 同在一台 Ubuntu 24.04 上，**除 SSH 外只监听回环**。
本节按执行顺序写，每步末尾的验证不过不要往下走。两处必须人工在场：OpenD 首次登录（可能要手机验证码）、IB Gateway 登录（每次都要手机二次验证）。
2026-10-11 按本节把生产整体迁到新服务器，下面的坑都是那次实测的。

| 组件 | 当前 | 托管 | 监听 |
| --- | --- | --- | --- |
| trading-signal | 3.4.1 | `bin/trader.sh` | 127.0.0.1:8093 |
| PostgreSQL | 18.6（PGDG） | systemd `postgresql` | 127.0.0.1 / ::1 :5432 |
| 富途 OpenD | 10.10.7008 | systemd `futu-opend` | 127.0.0.1:11111，Telnet 127.0.0.1:22222 |
| IB Gateway | 10.51（latest 通道） | VNC 桌面里手工启动，每日自动重启 | 4001（应只听回环，见 ④） |
| JDK | 21 | `JAVA_HOME` 指向 | — |
| 桌面 / VNC | Xfce + TigerVNC `:1` | systemd `vncserver@1` | 5901（应只听回环，见 ③） |

### ① 系统：用户、时区、防火墙

- 部署用户用普通用户，开发机用**公钥**登录它（autossh 需要，见 §1a）。完成后建议禁止 root 密码登录。
- **时区两处都要改，且必须在装 PostgreSQL 之前**：

  ```bash
  sudo timedatectl set-timezone Asia/Shanghai
  echo Asia/Shanghai | sudo tee /etc/timezone
  ```

  `timedatectl` 只改 `/etc/localtime`；Linux 上的 OpenJDK 定默认时区**先读 `/etc/timezone`**。只改前者时 JVM 仍是 UTC——日志时间带 `Z` 而不是 `+08:00`、
  JDBC 会话时区跟着变、REST 里不传日期时的 `LocalDate.now()` 差 8 小时。PostgreSQL 初始化时把系统时区写进自己的配置，所以要先改时区再装库。
  定时作业显式用 `America/New_York`，不受影响。验证：`cat /etc/timezone`、`timedatectl`（`System clock synchronized: yes`）。
- 出现 `*** System restart required ***` 就趁还没承担生产时 `sudo reboot`。
- 防火墙只放 SSH（**先 allow 再 enable**，SSH 不在 22 就把 `OpenSSH` 换成 `<ssh端口>/tcp`；启用前另开一个已登录会话备用）：

  ```bash
  sudo ufw allow OpenSSH && sudo ufw default deny incoming && sudo ufw default allow outgoing && sudo ufw enable
  ```

### ② JDK 21 与 PostgreSQL 18

- JDK 只能 21（富途 SDK 的 protobuf 3.5.1 用 `sun.misc.Unsafe`，24 起告警、26 抛异常）。压缩包解压到 `/opt/jdk-21.x` 再 `ln -sfn` 成 `/opt/java`，
  或 `sudo apt install openjdk-21-jre-headless`；路径写进 `trader.env` 的 `JAVA_HOME`，不必进 PATH。IB Gateway 自带 JRE，不用它。
- PostgreSQL 用官方 Apt 源（官方支持 Ubuntu 26.10 / 26.04 / 24.04 / 22.04）：

  ```bash
  sudo apt install -y postgresql-common && sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh
  sudo apt update && sudo apt install -y postgresql-18
  sudo -u postgres psql -Atc "show listen_addresses; show timezone; show log_timezone;"   # localhost / Asia/Shanghai ×2
  ss -ltn | grep 5432                                     # 只能是 127.0.0.1 与 [::1]
  sudo -u postgres createuser --pwprompt trader
  sudo -u postgres createdb -O trader db_trader && sudo -u postgres createdb -O trader db_trader_dev
  ```

  时区显示 `Etc/UTC`（改晚了）就在 `/etc/postgresql/18/main/postgresql.conf` 里把 `timezone`、`log_timezone` 改成 `'Asia/Shanghai'` 后重启。
  `~/.pgpass`（600）每库一行 `localhost:5432:<库>:trader:<密码>`，**主机栏必须写 `localhost`**（libpq 按字面匹配，写 127.0.0.1 取不到；`pg_dump` 也用 `-h localhost`）。

### ③ 图形桌面与 VNC（给 IB Gateway 用）

IB Gateway 是图形程序，登录与二次验证都在界面上。现用 Xfce + TigerVNC（`sudo apt install -y xfce4 xfce4-goodies tigervnc-standalone-server`，`vncpasswd`），
`/etc/systemd/system/vncserver@.service`：

```ini
[Service]
Type=forking
User=<用户>
WorkingDirectory=/home/<用户>
ExecStartPre=-/usr/bin/vncserver -kill :%i
ExecStart=/usr/bin/vncserver :%i -geometry 1280x800 -depth 24 -localhost yes
ExecStop=/usr/bin/vncserver -kill :%i
```

`sudo systemctl enable --now vncserver@1`。**用 `-localhost yes`**，开发机临时 `ssh -N -L 5901:localhost:5901 -p <ssh端口> <用户>@<主机>` 后连 `localhost:5901`。
（2026-10-11 时现场是 `-localhost no`、5901 对公网只靠 VNC 密码，待改；重启 vncserver 会连带退出桌面里的 IB Gateway，放在周日重登时做。）

### ④ 富途 OpenD

依据官方文档 v10.11（命令行 OpenD、运维命令、OpenD Q&A）。**换机与升级分开做**：当前生产 10.10.7008，官方最新 10.11.7108。

```bash
curl -fLO https://softwaredownload.futunn.com/Futu_OpenD_10.10.7008_Ubuntu18.04.tar.gz   # 466,920,616 字节；官方不给校验值，至少核对大小
tar -xzf Futu_OpenD_10.10.7008_Ubuntu18.04.tar.gz
ln -sfn ~/Futu_OpenD_10.10.7008_Ubuntu18.04/Futu_OpenD_10.10.7008_Ubuntu18.04 ~/OpenD
```

包名的 Ubuntu18.04 只是构建名，24.04 上正常。目录里必须有 `FutuOpenD.xml` 与 `Appdata.dat`。10.10 起 XML **不再放账号密码**；生效项：
`ip=127.0.0.1`、`api_port=11111`、`lang=chs`、`log_level=info`、`push_proto_type=0`、`auto_hold_quote_right=1`、
**`telnet_ip=127.0.0.1` + `telnet_port=22222`（必须配：服务以 `-console=0` 运行没有控制台，验证码与重登只能走 Telnet）**；不配 RSA 与 WebSocket。XML `chmod 600`。

首次在前台 `cd ~/OpenD && ./FutuOpenD` 交互登录：输入账号、密码，**选记住密码**；新设备触发设备锁就输 `req_phone_verify_code`，收到短信后
`input_phone_verify_code -code=<验证码>`；看到"登录成功"后输入 `exit`。**不要把旧机器的 `~/.com.futunn.FutuOpenD` 整个拷过来**：
`F3CNN/Device.dat` 是设备标识，多机相同会反复验证设备锁。境外 IP 登录为国际档（美股 LV3、港股 LV1、无 A 股），本项目只用美股，无差别。

`/etc/systemd/system/futu-opend.service`（`User` 必须是做过交互登录的用户，记住的凭据在它家目录下）：

```ini
[Unit]
Description=Futu OpenD (command line)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=<用户>
Group=<用户>
WorkingDirectory=/home/<用户>/OpenD
ExecStart=/home/<用户>/OpenD/FutuOpenD -console=0 -no_monitor=1 -login_account=<牛牛号> -login_by_remember=1
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

`sudo systemctl daemon-reload && sudo systemctl enable --now futu-opend`；验证 OpenD 日志 `~/.com.futunn.FutuOpenD/Log/GTWLog_*.log` 有「记住密码登录」「登录成功」，
再 `sudo reboot` 一次确认无人值守自动登录。

Telnet 运维（回复是 UTF-8，不要转码；**在 OpenD 里 `exit` 是退出 OpenD 程序**；Telnet 无鉴权，22222 不得对外也不进隧道）：

```bash
(printf 'show_sub_info\r\n'; sleep 3) | nc -w 5 127.0.0.1 22222    # 生产正常时约 19 只 Basic
```

常用：`show_sub_info`、`ping`（到连接点时延）、`request_highest_quote_right`、`req_phone_verify_code` / `input_phone_verify_code`、`relogin`（每小时 10 次）、`close_api_conn`（不带参数关全部）。
官方要点：同账号最多 10 个 OpenD 同时登录，但只有一个拿最高行情权限（互踢），换机期间别让两台长期并行；单个 OpenD 最多 128 条连接；没有 Docker 镜像。

### ⑤ IB Gateway

官方安装源两个通道：`https://download2.interactivebrokers.com/installers/ibgateway/{stable,latest}-standalone/ibgateway-{stable,latest}-standalone-linux-x64.sh`，
当前版本看同目录的 `version.json`（2026-10-11：stable 10.50.1f、latest 10.51.1b）。**2026-12-15 起最低 10.50.1**。在 VNC 桌面里 `sh ibgateway-…-linux-x64.sh`，装到 `~/Jts`。

- **同一用户名同时只能一个会话**：换机先在旧机器 File → Exit，再在新机器登录。换出口 IP 后首次登录可能多一道安全校验。
- API 设置：Enable ActiveX and Socket Clients；端口与 `TRADER_IBKR_PORT` 一致；Trusted IPs `127.0.0.1`；**勾选 Allow connections from localhost only**
  （2026-10-11 现场 `*:4001` 对所有地址监听，待改）；只读与 Master client ID 照旧环境。
- Lock and Exit：**Auto restart，15:45**（北京时间，美东 03:45 / 冬令时 02:45）。不开自动重启时网关改为每日自动登出、每天都要人工重登。
- **网关时区只认 `~/Jts/jts.ini` 的 `[Logon] TimeZone=`，不跟系统走**。新装是 `Africa/Abidjan`（格林尼治），15:45 可能按格林尼治执行＝美东 11:45 盘中。
  改法：网关 File → Exit（退出时会回写 jts.ini，必须先退再改），然后——**jts.ini 是 CRLF**，`$` 前有 `\r`：

  ```bash
  ps -eo args | grep '[i]bgateway/.*/jre/bin/java' || echo 网关已退出
  cp -p ~/Jts/jts.ini ~/Jts/jts.ini.bak-$(date +%Y%m%d)
  sed -i 's#^TimeZone=.*\r$#TimeZone=Asia/Shanghai\r#' ~/Jts/jts.ini
  diff ~/Jts/jts.ini.bak-$(date +%Y%m%d) ~/Jts/jts.ini       # 必须看到差异，确认真的落盘
  ```

  再启动网关登录。验证 `grep -E "Default timezone|auto-restart is" ~/Jts/launcher.log | tail -3` 出现 `Default timezone:Asia/Shanghai` 与 `Daily auto-restart is enabled.`
- 判断进程在不在别用 `pgrep -f <模式>`：在 `ssh … '…'` 里它会匹配到自己的命令行而误报"仍在运行"，用 `ps -eo args | grep '[x]yz'`。

### ⑥ 应用与验证

按 §3 部署。迁移场景下 `trader.env` **整份从旧环境原样拷贝、只改 `JAVA_HOME`**——用模板新建会让两家网关 `DISABLED`，
`TRADER_ACCOUNT_KEY_SECRET`、client-id、账户三项也必须是旧值。数据库按 §4 迁移或首轮装载。然后：

```bash
curl -s http://127.0.0.1:8093/api/system/info     # environment=PROD、database.marker=PROD
curl -s http://127.0.0.1:8093/actuator/health     # UP；两家网关 CONNECTED
head -c 40 ~/trading-signal/logs/trading-signal.log   # 时间戳带 +08:00；带 Z 说明 /etc/timezone 没改
ss -ltn                                            # 对外只有 SSH
```

**部署后能启动不算通过**：还要跨过首个北京 15:45 网关自动重启（应用一两分钟内自动重连）、美股开盘（实时报价有推送、`/api/account/live` 有当日盈亏）、
首个作业日的四个定时作业与 `check-daily.sh`、连续 2 个交易日无 322 / 326 且 ERROR 为零、下一个周日令牌失效后重登可恢复。

## 2. 克隆后第一次

```bash
./scripts/install-sdks.sh                 # tws-api + futu-api-shaded → 本地仓库
./scripts/install-git-hooks.sh            # 提交前敏感信息扫描
cp config/secrets.yml.example config/secrets.yml && chmod 600 config/secrets.yml
./mvnw clean verify
./mvnw -pl trader-app -am -DskipTests package
./scripts/run-local.sh                    # :8083；首次在 db_trader_dev 建表并盖 DEV 标记
curl -s http://127.0.0.1:8083/actuator/health
curl -s http://127.0.0.1:8083/api/system/info
./scripts/run-local.sh stop
```

前端：`cd trader-web && npm install && npm run dev`（:5174）。

启用网关：在 `config/secrets.yml` 里把 `trader.ibkr.enabled` / `trader.futu.enabled` 置 true 并填 host/port/client-id（隧道时 host 为 127.0.0.1），
重启开发实例后系统页应显示两家 CONNECTED、心跳时刻每 30 秒更新，`gateway_event` 表出现 CONNECTED 记录。

手动验证：导入 `docs/postman/` 下的集合与 `dev` 环境，按 [docs/postman/README.md](postman/README.md) 的顺序点一遍；或 `npx --yes newman run docs/postman/trading-signal.postman_collection.json -e docs/postman/trading-signal.dev.postman_environment.json` 整套跑。

集成测试（对真实网关，只读；连接参数只从环境变量读）。**五个变量全是必填，缺一个判失败不跳过**（3.1.2 改）——原先缺失静默跳过，叠上类级 `@EnabledIfSystemProperty` 与 `-Dsurefire.failIfNoSpecifiedTests=false`，整条命令可以在一次网关都没连的情况下 BUILD SUCCESS。不想跑就别加 `-Dtrader.integration=true`（整类跳过）：

```bash
export TRADER_IBKR_HOST=127.0.0.1 TRADER_IBKR_PORT=<隧道端口> TRADER_IBKR_TEST_CLIENT_ID=<临时 id> \
       TRADER_FUTU_HOST=127.0.0.1 TRADER_FUTU_PORT=<隧道端口>
./mvnw -pl trader-app -am verify -Dtrader.integration=true -Dtest='IbkrGatewayIT,FutuGatewayIT,ReconnectIT' -Dsurefire.failIfNoSpecifiedTests=false
```

`ReconnectIT` 会用 `TRADER_IBKR_TEST_CLIENT_ID` + 1 经本地 TCP 中继连盈透；不要与运行中的实例撞 id。

## 3. 打包与部署

```bash
./scripts/package.sh                      # dist/trading-signal-<版本>-<时间戳>.tar.gz（含前端）
```

服务器约定：解压到部署用户家目录下的 `~/trading-signal-<版本>-<时间戳>/`，用软链 `~/trading-signal` 指向当前版本；配置与日志都在该目录内。服务器本身怎么搭见 §1b。

首装：

```bash
scp -P <ssh端口> dist/trading-signal-<版本>-<时间戳>.tar.gz <用户>@<主机>:~/
ssh -p <ssh端口> <用户>@<主机>
mkdir -p ~/trading-signal-<版本>-<时间戳> && tar -C ~/trading-signal-<版本>-<时间戳> --strip-components=1 -xzf ~/trading-signal-<版本>-<时间戳>.tar.gz
ln -sfn ~/trading-signal-<版本>-<时间戳> ~/trading-signal && cd ~/trading-signal
cp config/trader.env.example config/trader.env && chmod 600 config/trader.env && vi config/trader.env   # JAVA_HOME、两家网关 host/port/client-id、enabled
bin/trader.sh start && bin/trader.sh status
```

- 数据库密码：服务器用户的 `~/.pgpass`（600）里有 `localhost:5432:db_trader:trader:<密码>` 时 `TRADER_DB_PASSWORD` 留空即可。
- 首次启动在空库 `db_trader` 上自动建表并盖 PROD 标记；`GET /api/system/info` 应显示 `environment=PROD`、`database.marker=PROD`。
- 发布包默认 `schedule-enabled=true`、`realtime.auto-subscribe=true`，启动后即为生产形态。

升级：解压新版本到新的时间戳目录，把旧目录的 `config/trader.env` 拷过去，`bin/trader.sh stop`（旧）→ 改软链 → `bin/trader.sh start`（新）。不要混用 `bin/trader.sh` 与 systemd。

**2.0.0 起 `trader.env` 新增两项敏感配置**（升级时补上；不补应用照样启动，但账户快照作业 FAILED、`jobs` 健康降级）：
`TRADER_ACCOUNT_KEY_SECRET`（账户号 HMAC 密钥，至少 16 个字符，`openssl rand -hex 24` 生成，**启用后不要更换**，换了同一账户的快照会变成另一个键）
与 `TRADER_ACCOUNT_CASH_EQUIVALENTS`（现金管理工具代码，逗号分隔：只进快照，不进池）。

**发布到生产的必须是正式版**（`<revision>` 不带 `-SNAPSHOT`，见 README「版本规则」）。
发布要打 tag `v<版本>` 标记生产跑的确切版本；**tag 需单独推送**（`git push origin v<版本>`），
GitHub 客户端默认只推提交，漏推会导致事后无法 checkout 到发布点。

**验证通过后清理**：删掉旧版本目录与**全部** `.tar.gz` 安装包（含当前版本的，包已解压不再需要），
只留当前版本目录与 `~/trading-signal-logs/`。清理前先确认三件事：当前配置在位、应用能响应、
只有一个进程且工作目录指向当前版本。回滚从本机 `dist/` 重传。

**升级前必看**（2026-09-09 踩过）：

- 生产 `schedule-enabled=true` 而开发是 false，**调度器只在生产装配**。新增 `@Scheduled` 用到的配置项如果只写在
  `MarketDataProperties` 的 `@DefaultValue` 上、没进 jar 内 `application.yml`，本地怎么跑都正常，一上生产启动直接失败。
  `ScheduledPlaceholdersTest` 现在会守住这条，但改调度相关配置时仍要留意这个开发/生产差异。
- **切换避开定时任务整点**：停旧实例到新实例就绪之间约 5 秒，落在这个空档里的 cron **直接丢失、重启后也不补触发**
  （Spring `@Scheduled` 不追补）。按美东时间，工作日的 17:30 增量、17:40 估值、18:00 账户快照、18:10 信号评估、21:00 行情补偿、
  22:00 信号补偿，以及周六 06:30 成分股同步、07:00 财报刷新（以 jar 内 `application.yml` 的 `*-cron` 为准），前后各留 2 分钟不切换。
  撞上了就在部署后按该任务的条件手工核对，必要时手工补跑。2026-09-18 美东 22:00 部署 3.0.5 就吞掉了当晚的信号补偿检查
  （日志与 `GET /api/jobs` 都没有记录；补偿"齐了"时本来就只写日志、不写作业行）。当晚评估齐全、没有过期跳过，所以没有实际损失。
- 起不来先看 `logs/console.log` 的第一条 `Application run failed`，配置类问题在那里说得很清楚。
- 旧版本目录不要马上删：回滚就是把软链指回去再 `bin/trader.sh start`。
- **清理旧版本目录前先把 `logs/` 拷出来**：日志在版本目录里，删目录等于把事后追查的依据一起删掉
  （2026-09-10 复查时就发生过前一晚增量的日志已找不到）。`logs/trading-signal.log` 按天滚动保留 14 天，
  `console.log` 是 nohup 重定向、不滚动。
- 本机偶发 `Could not resolve hostname`（macOS 系统解析器坏了，`host` 命令却能解）：
  用 `host <主机名>` 取到地址后按地址连，或重启 mDNSResponder。隧道已建立的连接不受影响。

systemd（需要 sudo，可选）：`systemd/trading-signal.service` 里把 `WorkingDirectory`、`PIDFile`、`ExecStart` 路径改成 `~/trading-signal`（软链）后 `sudo cp` 到 `/etc/systemd/system/`，`daemon-reload`、`enable --now`。之后只用 systemctl 管理。

首轮数据装载（生产空库）：`POST /api/universe/sync` → `POST /api/bars/refresh/universe?count=1000` → `POST /api/bars/rehab/refresh?all=true` → 逐只 `POST /api/pool/{symbol}?role=POOL|BENCHMARK`（HOLDING 由 `POST /api/account/holdings/sync?apply=true` 按盈透持仓生成） → `POST /api/bars/backfill` → `GET /api/bars/audit`。

基本面首轮装载：`POST /api/fundamentals/valuation/refresh`（520 只，几秒）→ `POST /api/fundamentals/financials/refresh?all=true`（520 只四类报表，约 42 分钟）→ `GET /api/fundamentals/audit`。两者都不占订阅与历史 K 线额度，但会占住作业线程，别和轮转类作业排一起。

## 4. 数据库

- 迁移由启动时 Flyway 执行；不启动应用也能建表：`./mvnw -pl trader-storage -am flyway:migrate -Dflyway.url=... -Dflyway.user=trader`（密码走 `PGPASSWORD` 或 `-Dflyway.password`，不要写进命令历史）。
- 环境标记：`SELECT * FROM app_environment;`。错连时应用拒绝启动并打印修复提示。从生产 dump 灌开发库后：`UPDATE app_environment SET name='DEV', stamped_at=now(), note='从生产 dump 灌入后改标记';`
- 密码来源顺序：环境变量 `TRADER_DB_PASSWORD` → `config/secrets.yml` → `~/.pgpass`（日志会打印"已从 ~/.pgpass 读取 …"，不打印密码）。关闭 pgpass 回落：`trader.storage.pgpass.enabled=false`。

**整库迁移到另一台服务器**（2026-10-11 做法；新库按 §1b ② 建好且为空）：

1. 停旧生产 `~/trading-signal/bin/trader.sh stop`，确认没有残留 java 进程——冻结写入。
2. 两边各跑一次各表行数，结果存文件，恢复后 `diff`：

   ```bash
   psql -h localhost -U trader -d db_trader -Atc "select table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', table_name), false, true, '')))[1]::text from information_schema.tables where table_schema='public' and table_type='BASE TABLE' order by 1"
   ```

3. 在旧服务器上用 nohup 导出（链路断了不影响），记下 sha256；开发库同理：

   ```bash
   nohup sh -c 'pg_dump -h localhost -U trader -Fc -f ~/db-backup/db_trader-<日期>.dump db_trader && echo OK > ~/db-backup/db_trader-<日期>.done' >/dev/null 2>&1 &
   sha256sum ~/db-backup/db_trader-<日期>.dump
   ```

4. 传到新服务器并核对 sha256 一致。慢链路用 `rsync --partial` 断点续传；`scp -3 scp://<主机>:<端口>/<路径>` 里的路径**从根目录算**，不是家目录。
5. 恢复并核对标记（`db_trader|PROD`、`db_trader_dev|DEV`）：

   ```bash
   pg_restore -h localhost -U trader -d db_trader --no-owner --exit-on-error ~/db_trader-<日期>.dump
   for d in db_trader db_trader_dev; do psql -h localhost -U trader -d $d -Atc "select current_database(), name from app_environment"; done
   ```

2026-10-11 实测：生产库压缩 dump 约 38 MB、开发库约 55 MB，21 张表恢复后逐行一致。旧服务器在验证通过前保留不动，作回滚依据。

## 4a. 行情数据底座（第 2 期·步骤 1）

首次建库后的顺序：`POST /api/universe/sync`（约 1 分钟）→ `POST /api/bars/refresh/universe?count=1000`（约 7 分钟，零额度）→ `POST /api/bars/rehab/refresh?all=true`（约 5 分钟，零额度）→ 把候选加入池 `POST /api/pool/{symbol}`（每只占 1 个历史额度，自动排深度回补）。

- 历史额度：`GET /api/bars/quota`，7 天滚动、预留 10；额度不足时深度回补作业标 PARTIAL，下周由周六的定时作业续补（或手工 `POST /api/bars/backfill`）。
- 定时：发布包 `trader.marketdata.schedule-enabled=true`（每日 17:30 ET 增量、周六 06:30 ET 成分股同步），开发/测试实例一律 false，手工用 `POST /api/bars/increment` 触发。**同一时刻只允许一个实例开启**，且定时只在实例运行时触发。
- 复权口径：读取时算，默认 `factor-mode=PER_EVENT`（实测与富途前复权一致）；不要改成 CUMULATIVE。
- 订阅额度：轮转每批 90 只，跑批期间富途订阅额度接近用满，此时不要在同一 OpenD 上做别的订阅。

## 4b. 实时报价（第 2 期·步骤 2）

- 生产实例 `trader.marketdata.realtime.auto-subscribe=true`：富途连上即订阅池与持仓；开发机 false，需要时 `POST /api/quotes/subscriptions/reconcile`。**不要让两个实例同时订阅**（额度按标的 × 类型计，两份就超 100）。
- 巡检：`GET /api/quotes/status` 的 `subscribed` 应等于 `desired`，`pushesLastMinute` 在交易时段（含盘前盘后）大于 0，`lastError` 为空。
- 全量轮转期间实时订阅自动暂停、结束后恢复（`pause-during-refresh`）；手工 `pause` 后记得 `resume`。
- 报价不落库；重启后缓存为空，首推后恢复。

## 4c. 账户与持仓（第 3 期）

- 快照：每个交易日美东 18:00 自动拍（`ACCOUNT_SNAPSHOT`），21:00 没有就补拍。快照窗口是交易日 16:15 至次日 04:00，窗口外手工拍 409；
  **错过的日子补不回来**（盈透只给当前状态）。周日令牌过期不影响快照（周末不拍），但**周一美东 18:00 前必须重新登录盈透**。
- 持仓同步：快照作业里先按持仓同步池里的 HOLDING；生产实例盈透连上 60 秒后也同步一次。要看会改什么先
  `POST /api/account/holdings/sync`（默认只看计划），确认后加 `?apply=true`。HOLDING 不接受手工添加。
- 基准：SPY、QQQ 是 BENCHMARK，买卖不会改它们的角色；新增基准用 `POST /api/pool/{symbol}?role=BENCHMARK`。
- 对账不过时看 `GET /api/account/snapshots/latest` 的 `snapshot.recon`：市值偏差多半是缺价（看 `positions[].priceSource`），
  恒等式不过多半是账户里有期权等非股票资产。
- 开发实例验证快照用 `POST /api/account/snapshot?force=true`（只在 DEV 可用，写开发库）。

## 4d. 入场信号与模型第二意见（第 4 期）

- 评估：每个交易日美东 18:10 `SIGNAL_EVALUATION`（全量成分股 ∪ 池与持仓，去掉基准），22:00 缺评估或有过期跳过但现已补齐的就补跑。
  手工 `POST /api/signals/evaluate?date=`；过去的日期是补跑，信号记 `BACKFILL`、不调模型。
- 模型：只在 `trader.ai.signal-veto-enabled=true` 的实例调用（生产外置配置已打开，开发实例保持关闭——两个实例都开会重复计费）。
  密钥放服务器 `trader.env` 的 `OPENAI_API_KEY`；本机开发放 `config/secrets.yml` 的 `trader.ai.api-key`（**缩进在 `ai:` 之下**，
  和 `ai:` 同级会被读成 `trader.api-key`，表现是 `GET /api/ai/usage` 的 `settings.configured=false`）。
- 看用量与失败：`GET /api/ai/usage`；某次分析的输入、结论、证据核对：`GET /api/ai/analyses/{id}`。没有结论（失败、拒答、截断、预算跳过）
  一律放行，信号审计 `aiAnalyses` 会提示。每日上限（默认 20 次）按美东自然日数实际发出的调用。
- 预览发给模型的输入（不计费）：`GET /api/signals/ai-input/{symbol}?date=`；手工分析（计费）：`POST /api/ai/analyses?symbol=&date=`。

## 5. 日常检查

> 定时作业不再静默丢失：碰撞时每 5 分钟重试、最多半小时，仍失败会写一行 `SKIPPED`；
> 美东 21:00 还有一次当天补偿检查，缺 K 线或估值就补跑（触发方式记 `CATCHUP`）；
> 检查本身每个工作日都写一行 `CATCHUP_CHECK`（非交易日、数据齐全也写），停摆或出错会让 `jobs` 健康指标降级。
> 补偿必须早于次日盘前——券商收盘后冻结当前价，过了盘前就取不到当日口径的估值快照了。


**收盘后必做**（美东 19:00 之后：增量 17:30、估值 17:40、账户快照 18:00、信号评估 18:10 都跑完；约北京时间次日 07:00，冬令时 08:00）：

```bash
./scripts/check-daily.sh http://127.0.0.1:8093        # 在服务器上跑；本机经隧道则改成隧道端口
```

本机执行需要 §1a 的隧道把 8093 转发到 127.0.0.1。

`historyGaps` 报警时：最近 90 天的缺口先重跑增量（`POST /api/bars/increment`）；补不回来的多是券商缺数。
全历史深扫用 `GET /api/bars/gaps`，已知长期缺口有 NBIS（停牌 664 天）与 SPY（券商缺数 26 天），两者都补不回来。

它依次调五处，一条命令覆盖全部：

1. `GET /api/bars/audit` 日线审计：完整性、字段合理性、前收连续性、复权新鲜度、同步错误、增量作业、日历覆盖、对照日历的近期缺口、网关。
2. `GET /api/fundamentals/audit` 基本面审计：估值完整性与合理性、财报陈旧度、估值作业。
3. `GET /api/account/audit` 账户审计：当天快照是否存在（美东 18:30 前、或刚启用还没有任何快照时，缺快照只提示）、对账状态（FAIL 为关键项）、缺价、快照作业。
4. `GET /api/signals/audit` 信号审计：当天评估是否存在（美东 19:00 前只提示）、覆盖是否完整、未平仓纸面账本是否算到当天为关键项；过期跳过超过 2%、缺日与公司行动口径失败、信号与重算结论不一致（多半是 K 线被订正，信号保留）、最近一次评估作业为提示项。缺评估时 22:00 补偿检查会补跑；手工补跑 `POST /api/signals/evaluate?date=`（过去的日期产生的信号记 BACKFILL）。
5. `GET /actuator/health` 运行健康：`jobs` 组件在任一定时作业 FAILED / SKIPPED / 逾期（`overdue` 每日增量、`catchupOverdue` 补偿检查、`accountSnapshotOverdue` 账户快照、`signalEvaluationOverdue` 信号评估）时降级，`gateways` 在网关掉线时降级。

`ok=false` 时看 `checks` 里失败项与样本；退出码 0 全通过 / 1 有关键项失败、某段接口报错或健康降级 / 2 有接口连不上。**一段出问题不掐掉后面几段**：某个审计接口 500 会原样打出状态码与错误正文，其余四段照跑（3.1.2 修；此前用 `curl -sf`，HTTP 500 与连不上同一个非零码，500 被打成"接口不可达"并当场 `exit 2`，后面一条都不跑。健康接口在系统 DOWN 时返回 503，同样被误报成"健康接口不可达"）。假日（如劳工节）不带参数跑会自动审计上一个交易日；显式传休市日则回"当天休市"并判通过。

固定节奏（北京时间）：

- **每天 15:45**：IB Gateway 自动重启、复用会话，应用断开后一两分钟内自动重连，无需处理。
- **每周日 15:45**：盈透令牌必然失效，网关端口不再起来、应用持续报 502。到 VNC 桌面里重新登录（手机二次验证）；日线全走富途不受影响，
  但**周一美东 18:00 账户快照前必须登好**。
- 定时作业的北京时间随美国夏令时变化：夏令时（至 11 月第一个周日）是美东 + 12 小时，冬令时 + 13 小时，例如每日增量 17:30 ET ＝ 北京次日 05:30 / 06:30。

- `GET /api/gateways`：两家 CONNECTED，`lastHeartbeatAt` 在 1 分钟内，`reconnectAttempts` 为 0；盈透 facts 里各 `farm.*` 为 OK 或 INACTIVE（INACTIVE 正常）。
- `GET /api/gateways/events?limit=20`：盈透每日自动重启会留下一对 DISCONNECTED / RECONNECTED，属正常；频繁出现则查隧道或网关。
- `GET /actuator/health` 为 UP（DEGRADED 表示有网关未连上，看 `components.gateways`）；`GET /api/system/info` 的 `environment` 与所在机器一致、`database.marker` 与之一致。
- 版本：`/api/system/info` 的 `version` / `buildTime` 与发布包一致。
- 行情：`GET /api/bars/coverage` 的 `latest` 应为最近一个已收盘交易日，`withErrors` 为 0，`rehabCovered` 等于 `universeSize`；`GET /api/jobs` 最近的 DAILY_INCREMENT 为 OK。
- 数据质量核查 SQL（只读，任一 psql 可跑）：每只最新交易日是否一致、每只根数分布、以某只标的日期集合为参照的缺口数、`last_close` 与上一根 `close` 是否相等（不等即漏日）、`|change_rate|>50` 的行应都能对应拆股。

## 6. 故障排查

| 现象 | 原因 | 处置 |
| --- | --- | --- |
| 启动报 `未声明 trader.environment` | 外置配置没生效 | 确认启动参数 `--spring.config.additional-location=file:./config/` 且工作目录是仓库根/部署目录 |
| 启动报 `环境不匹配` | JDBC URL 指错了库 | 核对 `spring.datasource.url`；开发只连 `db_trader_dev` |
| 启动报 `拒绝自动盖章` | 库里已有表但没有标记 | 确认库没指错后手工 `INSERT INTO app_environment ...` |
| `Failed to determine a suitable driver class` | 外置配置里 `trader.storage.enabled=true` 但没有 `spring.autoconfigure.exclude: []` | 补上 |
| `UnknownHostException: 127.0.0.1`（psql 正常） | 本机 SOCKS 代理劫持了 JDBC | 启动参数加 `-DsocksNonProxyHosts='localhost|127.*|[::1]'`（脚本与 IDEA 配置已带） |
| `NoClassDefFoundError: com/ib/client/...` 或 `com/futu/openapi/...` | fat jar 缺 SDK | 检查 `trader-app/pom.xml` 的 runtime 依赖是否被删 |
| `NoSuchMethodError ... Descriptors$FileDescriptor.internalBuildGeneratedFileFrom` | 有人把 `com.futunn.openapi:futu-api` 直接加进了依赖 | 只能依赖 `futu-api-shaded` |
| `./mvnw` 找不到 tws-api | wrapper 的 Maven 与安装 SDK 时用的本地仓库不同 | 统一 `~/.m2/settings.xml` 的 `localRepository`，或重跑 `install-sdks.sh` |
| 盈透状态 RECONNECTING，facts 有 `326 client-id 已被占用` | 另一个实例用了同一个 client-id | 给每个实例分配独立 id；实际分配只记在 `secrets.yml` / `trader.env` 里，不入库 |
| 盈透每天固定时间 DISCONNECTED → RECONNECTED | IB Gateway 每日自动重启 | 正常；几分钟内自动恢复。每周需人工 2FA 重新登录网关 |
| 盈透 `detail` 为「配置的 trader.ibkr.account 不在网关的受管账户列表里」且状态 ERROR | 账户号配错或登录了别的用户名 | 核对后重启实例 |
| 富途 CONNECTED 但 detail「OpenD 状态为 …」/「行情未登录」 | OpenD 未完成登录、需要验证码或未同意协议 | 到服务器上看 OpenD 日志，`relogin` / 输入验证码 |
| 富途状态 RECONNECTING 且 detail 含「连接 OpenD 失败」 | OpenD 未运行或隧道未建 | 检查 OpenD 进程与隧道 |
| 作业 FAILED，摘要含「限频」或「等待超过上限」 | 同一 OpenD 上有别的程序在频繁调用 | 错开时间再跑；限频阈值可在 `trader.futu.limits` 调低 |
| 深度回补 PARTIAL，摘要含「历史额度用尽」 | 7 天 100 只额度用完 | 正常；下周六自动续补 |
| 成分股同步 PARTIAL，摘要含「来源失败」 | Wikipedia 不可达或页面结构变了 | 保留旧成分；用 `POST /api/universe/import` 导入 CSV 兜底 |
| `/api/quotes/status` 的 `subscribed` < `desired` 且 `lastError` 含「额度」 | 订阅额度被别的实例或 App 占用 | `getSubInfo` 看全部连接；关掉多余实例或减小池 |
| 实时报价盘前盘后不动 | 看的是 `rthPrice`（非常规时段冻结） | 用 `price`（有效价）或 preMarket/afterMarket 字段 |
| 应用日志时间带 `Z`，旧环境是 `+08:00` | `/etc/timezone` 仍是 `Etc/UTC`，OpenJDK 先读它 | §1b ①：两处都改后重启应用 |
| 两家网关都 `DISABLED`（`trader.futu.enabled=false`） | `trader.env` 用模板新建、没搬旧值 | 整份从旧环境原样拷贝，只改 `JAVA_HOME` |
| IB Gateway `launcher.log`：`Default timezone:Africa/Abidjan` | 网关时区只认 `jts.ini` 的 `[Logon] TimeZone` | §1b ⑤：退出网关后改 jts.ini（CRLF） |
| `launcher.log`：`Daily auto-restart is not enabled` | Lock and Exit 没选 Auto restart | 选 Auto restart 15:45；否则每天自动登出、要人工重登 |
| 盈透 `TWS 1100` 后自行恢复，没有 1101 日志 | 网关与盈透服务器的链路抖动，1102 恢复（数据保持）不打日志 | 偶发不用管；反复出现查服务器网络 |
| 盈透 `2150 Invalid position trade derived value`，实时账户当日盈亏为空 | 逐只盈亏订阅（`reqPnLSingle`）被拒；2026-10-11（周日、网关 10.51）首见，**原因待定** | 开盘后复核：消失则是休市现象；持续则与网关版本有关，须在 12-15 前查清 |
| OpenD 新机器启动后要验证码 | 新设备触发设备锁 | Telnet `req_phone_verify_code` → `input_phone_verify_code -code=<验证码>` |
