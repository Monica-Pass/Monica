# 未发布 / Unreleased

# Monica Android 1.0.328

## 中文

- Bitwarden 自托管连接支持复用 Android 设置中安装的用户 CA；同一可信 CA 签发的有效续期证书无需重新导入，仍可使用已有的账号专用 PEM 和 mTLS 配置。
- Bitwarden 官方域名仅使用系统 CA；自托管的额外信任不传递到官方域名、外部附件下载或其他网络功能，保留域名与证书有效性校验。
- 修复“管理原始信息”中本地多层文件夹被平铺显示的问题，可逐层进入并返回上级；新建、重命名和移动正确保留子目录关系。

## English

- Bitwarden self-hosted connections can reuse user CAs installed in Android settings. Valid certificate renewals under the same trusted CA need no reimport; existing account-specific PEM and mTLS settings remain available.
- Official Bitwarden domains use system CAs only. Extra self-hosted trust does not extend to official hosts, foreign attachment downloads or other network features; hostname and certificate validity checks remain enforced.
- Fixed flattened local folder paths in Manage original information. Browse folders level by level, return to parents, and preserve descendant paths when creating, renaming or moving local folders.
