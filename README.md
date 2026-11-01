# jenkins-atom-github-checks

GitHub check runs with line-level annotations -- port of **github-checks** (3,534 installs). The plugin is 50 KB of
glue; the payload rules it relies on live in **checks-api**, so both jars were read (`javap` + upstream sources at
`710.v756946918b_dd`) and the behaviour that matters is reproduced here.

## What the plugin actually does

`GitHubChecksPublisher.publish` takes a `ChecksDetails`, wraps it in `GitHubChecksDetails`, and calls exactly one of
`GHRepository.createCheckRun(name, headSha)` or `updateCheckRun(id)`; the id comes from a `GitHubChecksAction` parked on
the build (`context.getId(name)`). Everything else is mapping:

| 插件 / checks-api 的行为 | 原子 |
|---|---|
| `ChecksStatus`: NONE, QUEUED -> `queued`, IN_PROGRESS -> `in_progress`, COMPLETED -> `completed` | 同 |
| `BuildStatusChecksPublisher.getConclusion`: SUCCESS -> success, UNSTABLE -> neutral, FAILURE -> failure, NOT_BUILT -> skipped, ABORTED -> canceled | 同, 但 `ABORTED` 发 `failure`: `GitHubChecksDetails` 把 CANCELED 映成 FAILURE, 因为 GitHub 只让自己的 Actions 写 `cancelled`; 要别的就显式填 `conclusion` |
| `truncateIfNeeded`: 超过 65535 字符时留头, 末尾加 `... [Output truncated; original size was N characters] ...`, 不切断代理对 | 同一条公式 (`maxMessageSize`, 默认 65535) |
| GitHub 回 `Only N characters are allowed; M were supplied` 时 `calculateReducedLimit` 重算上限再发一次 (`max(100, allowed - overshoot - 100)`) | 同, 只重试一次, 日志里写明新的上限 |
| 输出 `title`/`summary`/`text`/`annotations`/`images`; `summary` 必填, `title` 必填 | `title`/`summary`/`text`/`annotations`; `title`/`summary` 留空按构建结果生成; images 未移植 (需要可访问的图片地址) |
| 注解: `path`, `start_line`, `end_line`, `annotation_level`, `message`, 可选 `title`/`raw_details`; 插件不传列号 | 同, 另支持 `start_column`/`end_column` (API 接受; 只在单行上合法) |
| 每请求最多 50 条注解, 超出要多次 update, 每次 update **追加** | 同: 首请求 50 条, 其余按 50 一批 PATCH, 顺序保持 |
| check id 存在 build action 里, 同一构建重发就是 update | `external_id` + `GET /commits/{sha}/check-runs?check_name=` 找回同一个 check; `external_id` 不同就是另一个构建, 新建而不是覆盖 |
| `details_url` 缺省用构建 URL | 缺省 `BUILD_URL` |
| `external_id` 用 `Run.getExternalizableId()` | 缺省 `BUILD_TAG` |
| 多分支/PR 上下文 (`GitHubChecksContext`) 推断仓库与 sha | 不可移植: 只从 `GIT_URL` 推断 owner/name, `GIT_COMMIT` 取 sha, 其余报 USER 错 |
| 触发器 (`CheckRunGHEventSubscriber`, 在 GitHub 点 re-run) 与 job 生命周期事件 | 不移植: 原子是"一次调用", queued/in_progress/completed 由你在流水线里显式调 |

## 与插件不同的地方 (有意为之)

1. **回读才算数**. 写完 `GET /check-runs/{id}` 逐字段对 (name, head_sha, status, conclusion, output.title/summary/text),
   再 `GET /check-runs/{id}/annotations` 逐条对 (path, 行号, 列号, 级别, 正文). 差一个字段就 `40007`, 不是"HTTP 201 成功".
2. **注解先在本地验**. 相对路径, `startLine >= 1`, `endLine >= startLine`, 列号只在单行, 级别只接受
   `notice|warning|failure` — 报 `40005` 并指出第几条/哪个文件, 而不是把 422 丢给用户.
3. **50 条一批**. API 的硬限制; 一次发 120 条会被 422, 插件对此没有处理.
4. **同一构建才复用**. 只按名字找会把别的构建的 check 拿来改, 并把注解追加进去; 原子要求 `external_id` 相同.

## 输入

| 参数 | 说明 |
|---|---|
| `account` + `repo` | owner 与仓库; `repo` 也可写 `owner/name`; 都空时用 `GIT_URL` 推断 |
| `sha` | 提交 sha, 留空取 `GIT_COMMIT`; 开 `preflight` 时用接口解析出的完整 sha |
| `name` | 检查名, 留空取 `JOB_NAME`, 再不行 `jenkins/atom` |
| `status` | `queued` / `in_progress` / `completed` (默认) |
| `conclusion` | 留空按 `BUILD_STATUS` 推断; 只在 `completed` 时接受 |
| `title` / `summary` / `text` | 输出; 支持 `${VAR}`; `summaryFile`/`textFile` 从工作区读 |
| `annotationsFile` | JSON 数组 (也认 `{"annotations": [...]}`), 键可以是 camelCase 或 GitHub 的 snake_case |
| `detailsUrl` / `externalId` | 留空取 `BUILD_URL` / `BUILD_TAG` |
| `apiUrl` | 默认 `https://api.github.com`, GHE 填根地址 |
| `token` | 凭据 (Secret text), 只出现在请求头 |
| `preflight` | 默认开: 先 `GET /user`, `GET /repos/{o}/{r}`, `GET /commits/{sha}` |
| `reuseExisting` | 默认开: 同 `external_id` 的 check 走 PATCH |
| `verify` | 默认开: 回读 check 与注解 |
| `maxMessageSize` | 默认 65535 |

注解文件一行一条:

```json
[{"path": "src/main.c", "startLine": 12, "endLine": 12, "startColumn": 5, "endColumn": 19,
  "level": "failure", "message": "buffer is freed here and read below", "title": "use after free"}]
```

输出: `status` (HTTP), `id`, `name`, `headSha`, `conclusion`, `annotations`, `checkUrl`, `apiUrl`, `verified`,
`annotationsVerified`.

## 错误码

| 码 | 含义 |
|---|---|
| 40001 | 参数不全 / 组合非法 (未完成的 check 带 conclusion, 未知 status/conclusion) |
| 40002 | 凭据无效或缺失 (`GET /user` 401) |
| 40003 | 仓库不存在 |
| 40004 | 提交不在仓库里 |
| 40005 | 注解文件不可用 (读不到 / 不是 JSON / 某条字段非法) |
| 40006 | 接口拒绝了写入 (4xx/5xx, 附原文), 包括超过重试上限的体积 |
| 40007 | 回读与所发不一致 |
| 40009 | 回包不是 check run 对象 / 读不回来 |
| 40010 | 前置查询失败 (网络或非 2xx) |

## 构建与验证

```bash
./build.sh
./tools/smoke.sh             # 9 组场景, 假 API 独立校验并保存状态
python3 tools/mutations.py   # 7 个植入的 bug, 每个都必须被抓红
```

`tools/fake-check-api.py` 按 REST 文档校验报文: 必需字段, conclusion 与 status 的搭配, 每条注解的行/列/级别,
**每请求最多 50 条注解并在每次 update 时追加**, 超长时回 GitHub 的原话. 它把 check 与注解写进 state 文件,
`tools/check.py` 再从原子**自己的输入**独立重算一遍 (仓库推断, 结论映射, `${VAR}` 展开, 截断公式, 注解默认值),
与服务端状态、请求日志三方比对; 批次 (50/50/20) 也从请求日志里独立数出来.
