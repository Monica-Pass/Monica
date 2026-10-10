# 脚本说明

## README 自动更新脚本（爱发电）

`update_afdian_sponsors.py` 从爱发电开放接口读取支持者和订单数据，并更新项目根目录 `README.md` 中的 `afdian-sponsors` 标记区块。

### GitHub 配置

在仓库的 `Settings → Secrets and variables → Actions` 中创建 Repository secret：

| 名称 | 内容 |
| --- | --- |
| `AFDIAN_TOKEN` | 爱发电开放接口 token |

爱发电 user ID 已作为公开标识写入工作流。token 只通过 Secret 注入，禁止提交到仓库。

工作流每天北京时间 10:17 自动运行，也可在 Actions 页面手动执行 `Update Afdian sponsors`。

本地验证命令：

```powershell
$env:AFDIAN_TOKEN = "从本地安全位置读取"
python .github/scripts/update_afdian_sponsors.py
Remove-Item Env:AFDIAN_TOKEN
```

## AtomGit Release 同步脚本

`sync-atomgit-release.js` 把 GitHub Release 的标题、说明、预发布标记和附件同步到 AtomGit。代码与 tag 由 AtomGit 自动镜像，本脚本只补 Release 本体；重复运行安全：已有发行版改为更新说明，已有附件按名称跳过。

### GitHub 配置

在仓库的 `Settings → Secrets and variables → Actions` 中创建 Repository secret：

| 名称 | 内容 |
| --- | --- |
| `GITCODERELEASE` | AtomGit 访问令牌（需仓库读写与 Release 读写权限） |

可选 Repository variables：`ATOMGIT_REPO_OWNER`／`ATOMGIT_REPO_NAME`（默认 `Monica-Pass`／`Monica`）、`ATOMGIT_API_BASE`（默认 `https://api.atomgit.com`）、`ATOMGIT_HOSTS_HACK=true`（写入实测较快的解析地址，默认关闭）。

### 触发方式

工作流 `Sync Releases to AtomGit`：

- 发布新 Release 时自动同步（`release: published`，含草稿转正式发布）。
- Actions 页面手动 `Run workflow`，必填 `release_tag`（例如 `V1.0.318`）：同步指定版本；历史 Release 与出错重跑都按 tag 逐个触发，与 Telegram 推送的手动方式一致。

历史体量（2026-10-10 实测）：86 个已发布 Release、132 个附件、共 3.82 GiB，按需逐个回填。

### 本地验证命令

```bash
GITHUB_TOKEN="$(gh auth token)" DRY_RUN=1 node .github/scripts/sync-atomgit-release.js --tag V1.0.318
```

本地要真实写入时去掉 `DRY_RUN=1` 并提供 `ATOMGIT_TOKEN`；不带 AtomGit 令牌的 dry-run 会跳过 AtomGit 查询并在输出里注明。
