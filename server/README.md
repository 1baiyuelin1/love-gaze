# love gaze 后端部署

Android 跨设备同步需要自行部署此服务。公开仓库提供 Python API 源码和通用接入步骤，不提供实际服务器地址、部署账号、生产配置、设备凭据、数据库或可直接使用的连接码。可选择能够被两台手机访问的服务器，不限云厂商。

## 运行要求

- Linux 服务器、Python 3.10 或以上，以及 `requirements.txt` 中的 Gunicorn。
- 可持久化且可写的 SQLite 数据目录。
- 具有有效证书的 HTTPS 入口，反向代理到仅在本机监听的 Gunicorn。
- 两个固定身份 `white`、`champion`，每个身份一份独立的 64 位小写十六进制凭据。

服务器配置、HTTPS 证书、服务启动管理和备份需要部署者自行完成；仓库不包含原部署机器的配置或一键部署脚本。

## 凭据与数据库配置

后端从 `LOVE_GAZE_CONFIG` 指定的 JSON 文件读取配置，格式如下：

```json
{
  "token_hashes": {
    "white": "<white 明文凭据的 SHA-256 十六进制摘要>",
    "champion": "<champion 明文凭据的 SHA-256 十六进制摘要>"
  }
}
```

上面的内容是结构说明，不能直接作为运行配置。数据库路径由 `LOVE_GAZE_DATABASE` 指定，父目录需预先创建，运行服务的账号需能读写。未设置环境变量时，代码默认读取 `/etc/love-gaze/devices.json` 和 `/var/lib/love-gaze/events.sqlite`；这些只是代码中的默认路径，不代表仓库提供相应文件。

首次独立部署时，可在项目根目录用 Python 运行以下内容，生成配置和两份手机连接码。先将示例地址改为自己的 HTTPS 根地址，与 Android 的 `sync.origin` 完全一致：

```python
from pathlib import Path
import base64
import hashlib
import json
import secrets

origin = "https://sync.example.com"  # 替换为自己的 HTTPS 根地址，不带末尾斜杠
root = Path("private")
root.mkdir(exist_ok=True)
roles = ("white", "champion")
outputs = [root / "devices.json"] + [root / f"{role}-connection.txt" for role in roles]
if any(path.exists() for path in outputs):
    raise SystemExit("配置已存在；不要覆盖正在使用的凭据或连接码。")
tokens = {role: secrets.token_hex(32) for role in roles}
config = {"token_hashes": {
    role: hashlib.sha256(token.encode("utf-8")).hexdigest()
    for role, token in tokens.items()
}}
outputs[0].write_text(json.dumps(config, indent=2), encoding="utf-8")
for role in roles:
    document = {"provider": "aliyun", "server": origin, "role": role, "token": tokens[role]}
    encoded = base64.urlsafe_b64encode(json.dumps(document).encode("utf-8")).decode().rstrip("=")
    (root / f"{role}-connection.txt").write_text("champion1:" + encoded + "\n", encoding="utf-8")
```

`private/` 已被 Git 忽略。将生成的 `devices.json` 安全复制到服务器上的私有配置位置；两份连接码分别交给对应设备的使用者，不要提交到 GitHub。`provider: "aliyun"` 是客户端识别的协议名称，不要求使用特定云厂商。

已有部署或旧 CloudBase 迁移应继续使用原凭据及其摘要，不运行上述首次生成流程来替换它们。

## 启动服务

以下为 Linux 示例，路径均需换成你自己的位置。在 `server/` 目录创建独立 Python 环境并安装依赖：

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
```

创建数据库父目录，并保证服务账号能够读取配置文件、写入数据库目录。然后在同一目录设置环境变量并启动：

```bash
export LOVE_GAZE_CONFIG=/absolute/private/devices.json
export LOVE_GAZE_DATABASE=/absolute/data/events.sqlite
python -m gunicorn --bind 127.0.0.1:8000 --workers 1 --threads 4 'sync_app:production_app()'
```

这是前台启动示例。长期运行需要自行配置服务管理和进程重启。通过反向代理将自己的 HTTPS 入口转发到 `127.0.0.1:8000`，代理允许至少 512 KiB 请求体；不要将配置或数据目录作为静态文件目录公开。

依次确认本机 `http://127.0.0.1:8000/health` 与自己的 `https://<服务器地址>/health` 正常返回，再安装已填写相同 HTTPS 根地址的 APK。服务器和 APK 都准备好之后，分别导入两份连接码。

## 接口与绑定

- `GET /health`：健康检查，不返回设备历史。
- `POST /v1/sync`：JSON 请求；支持固定身份的 `claim` 和 `sync`，校验身份凭据与安装 UUID。
- 一个身份绑定一个安装实例。清除手机数据或重装可能产生新实例，不能自动顶替已有绑定。
- 普通活动按事件 ID 去重并增量收发；头像和最新设备状态单独更新。
- 默认同步约每 15 秒一次，历史分页可约每 2 秒继续；终端被系统限制时，服务器无法代替手机采集。
- 手机清理本机历史不会自动删除服务器记录。数据库持久化、备份与恢复由部署者负责；备份运行中的 SQLite 应使用 SQLite 备份方式，避免只复制主文件而遗漏 WAL 中的数据。

## 测试与限制

`test_sync_app.py` 使用临时数据库和模拟身份，按需可在项目根目录运行：

```bash
python -m unittest discover -s server -p test_sync_app.py
```

这些测试不验证你的 HTTPS、进程托管、真实网络或手机后台表现，部署后需另行确认。
