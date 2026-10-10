#!/usr/bin/env node
/* 把 GitHub Release 的标题、说明、预发布标记与附件同步到 AtomGit（与 gitcode 同一后端）。
 * 代码与 tag 已由 AtomGit 自动镜像，本脚本只补 Release 本体。
 * 幂等：已存在的发行版改走更新，已存在的附件跳过。
 * 用法见 .github/scripts/README.md。 */

'use strict';

const { Readable } = require('node:stream');
const { setTimeout: sleep } = require('node:timers/promises');

const GITHUB_API = 'https://api.github.com';
const USER_AGENT = 'monica-atomgit-release-sync';

function truthy(value) {
  return ['1', 'true', 'yes', 'on'].includes(String(value ?? '').trim().toLowerCase());
}

function argValue(name) {
  const i = process.argv.indexOf(name);
  return i !== -1 ? String(process.argv[i + 1] ?? '') : '';
}

const GITHUB_TOKEN = process.env.GITHUB_TOKEN || '';
const GITHUB_OWNER = process.env.GITHUB_OWNER || 'Monica-Pass';
const GITHUB_REPO = process.env.GITHUB_REPO || 'Monica';

const ATOMGIT_TOKEN = process.env.ATOMGIT_TOKEN || '';
const ATOMGIT_API_BASE = (process.env.ATOMGIT_API_BASE || 'https://api.atomgit.com').replace(/\/+$/, '');
const ATOMGIT_OWNER = process.env.ATOMGIT_OWNER || 'Monica-Pass';
const ATOMGIT_REPO = process.env.ATOMGIT_REPO || 'Monica';

const RELEASE_TAG = argValue('--tag') || process.env.RELEASE_TAG || '';
const SYNC_ALL = process.argv.includes('--all') || truthy(process.env.SYNC_ALL_RELEASES);
const RELEASE_LIMIT = Math.max(0, Number(argValue('--limit') || process.env.RELEASE_LIMIT || 0) || 0);
const DRY_RUN = process.argv.includes('--dry-run') || truthy(process.env.DRY_RUN);

const hasAtomgitToken = Boolean(ATOMGIT_TOKEN);

function redact(value) {
  let text = String(value ?? '');
  if (ATOMGIT_TOKEN) text = text.split(ATOMGIT_TOKEN).join('***');
  return text.replace(/access_token=[^&\s'"]+/g, 'access_token=***');
}

function fail(message) {
  throw new Error(redact(message));
}

function formatBytes(size) {
  if (!Number.isFinite(size)) return String(size);
  if (size < 1024) return `${size} B`;
  const units = ['KiB', 'MiB', 'GiB'];
  let value = size;
  let unit = -1;
  do {
    value /= 1024;
    unit += 1;
  } while (value >= 1024 && unit < units.length - 1);
  return `${value.toFixed(1)} ${units[unit]}`;
}

function log(message) {
  console.log(message);
}

async function requestJson(service, url, options = {}) {
  const { method = 'GET', headers = {}, body, allow404 = false, retries = 3 } = options;
  let lastError;
  for (let attempt = 1; attempt <= retries; attempt++) {
    let response;
    try {
      response = await fetch(url, { method, headers, body });
    } catch (error) {
      lastError = new Error(`${service} 网络错误：${error?.cause?.message || error?.message || error}`);
      if (attempt < retries) {
        await sleep(2000 * attempt);
        continue;
      }
      break;
    }
    const text = await response.text();
    if (allow404 && response.status === 404) return null;
    if (response.ok) return text ? JSON.parse(text) : {};
    const summary = `${service} ${method} HTTP ${response.status}：${redact(text).slice(0, 500)}`;
    if ((response.status === 429 || response.status >= 500) && attempt < retries) {
      lastError = new Error(summary);
      await sleep(2000 * attempt);
      continue;
    }
    throw new Error(summary);
  }
  fail(`${lastError?.message || '未知错误'}（已重试 ${retries} 次）`);
}

function githubHeaders(extra = {}) {
  return {
    Authorization: `Bearer ${GITHUB_TOKEN}`,
    Accept: 'application/vnd.github+json',
    'User-Agent': USER_AGENT,
    'X-GitHub-Api-Version': '2022-11-28',
    ...extra,
  };
}

async function getGithubRelease(tag) {
  return requestJson(
    'GitHub',
    `${GITHUB_API}/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/tags/${encodeURIComponent(tag)}`,
    { headers: githubHeaders(), allow404: true },
  );
}

async function listGithubReleases() {
  const releases = [];
  for (let page = 1; ; page += 1) {
    const data = await requestJson(
      'GitHub',
      `${GITHUB_API}/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases?per_page=100&page=${page}`,
      { headers: githubHeaders() },
    );
    releases.push(...data.filter((release) => !release.draft));
    if (data.length < 100) break;
  }
  return releases;
}

function atomgitHeaders(extra = {}) {
  return {
    Authorization: `Bearer ${ATOMGIT_TOKEN}`,
    'PRIVATE-TOKEN': ATOMGIT_TOKEN,
    Accept: 'application/json',
    'User-Agent': USER_AGENT,
    ...extra,
  };
}

function atomgitUrl(path, params = {}) {
  const url = new URL(`${ATOMGIT_API_BASE}/api/v5/repos/${ATOMGIT_OWNER}/${ATOMGIT_REPO}${path}`);
  url.searchParams.set('access_token', ATOMGIT_TOKEN);
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
  return url.toString();
}

async function getAtomgitRelease(tag) {
  return requestJson('AtomGit', atomgitUrl(`/releases/${encodeURIComponent(tag)}`), {
    headers: atomgitHeaders(),
    allow404: true,
  });
}

async function* streamWithProgress(body, total, label) {
  let sent = 0;
  let nextMark = total ? total * 0.25 : 0;
  for await (const chunk of body) {
    sent += chunk.length;
    if (total && sent >= nextMark && sent < total) {
      log(`    ${label}: ${Math.min(99, Math.round((sent / total) * 100))}%`);
      nextMark += total * 0.25;
    }
    yield chunk;
  }
}

async function uploadAsset(tag, asset) {
  const uploaded = await requestJson(
    'AtomGit',
    atomgitUrl(`/releases/${encodeURIComponent(tag)}/upload_url`, { file_name: asset.name }),
    { headers: atomgitHeaders() },
  );
  const uploadUrl = uploaded?.upload_url;
  if (!uploadUrl) fail(`AtomGit 未返回上传地址（${tag} / ${asset.name}）`);

  const source = await fetch(asset.url, {
    headers: githubHeaders({ Accept: 'application/octet-stream' }),
    redirect: 'follow',
  });
  if (!source.ok) fail(`GitHub 附件下载失败：HTTP ${source.status}（${asset.name}）`);
  if (!source.body) fail(`GitHub 附件响应没有内容（${asset.name}）`);

  log(`[upload] ${tag}: ${asset.name}（${formatBytes(asset.size)}）`);
  const response = await fetch(uploadUrl, {
    method: 'PUT',
    headers: {
      'Content-Type': asset.content_type || 'application/octet-stream',
      'Content-Length': String(asset.size),
      'User-Agent': USER_AGENT,
    },
    body: Readable.from(streamWithProgress(source.body, asset.size, asset.name)),
    duplex: 'half',
  });
  const text = await response.text();
  if (response.status === 409) {
    log(`[skip] ${tag}: 附件 ${asset.name} 已存在（服务端 409）`);
    return;
  }
  if (!response.ok) {
    fail(`AtomGit 附件上传失败：HTTP ${response.status} ${redact(text).slice(0, 300)}（${asset.name}）`);
  }
  log(`[done] ${tag}: 已上传 ${asset.name}`);
}

async function syncRelease(release) {
  const tag = release.tag_name;
  const meta = {
    name: release.name || tag,
    body: release.body || '',
    prerelease: Boolean(release.prerelease),
  };

  let existing;
  if (hasAtomgitToken) {
    existing = await getAtomgitRelease(tag);
  } else {
    log(`[warn] ${tag}: 未提供 AtomGit 令牌，无法确认现状，按“新建”预演`);
  }

  if (existing) {
    const sameMeta = (existing.name || '') === meta.name
      && (existing.body || '') === meta.body
      && Boolean(existing.prerelease) === meta.prerelease;
    if (sameMeta) {
      log(`[skip] ${tag}: 发行说明已是最新`);
    } else if (DRY_RUN) {
      log(`[dry-run] ${tag}: 将更新发行说明`);
    } else {
      await requestJson('AtomGit', atomgitUrl(`/releases/${encodeURIComponent(tag)}`), {
        method: 'PATCH',
        headers: atomgitHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ tag_name: tag, ...meta }),
      });
      log(`[update] ${tag}: 已更新发行说明`);
    }
  } else {
    if (DRY_RUN) {
      log(`[dry-run] ${tag}: 将新建 Release「${meta.name}」`);
    } else {
      await requestJson('AtomGit', atomgitUrl('/releases'), {
        method: 'POST',
        headers: atomgitHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ tag_name: tag, target_commitish: release.target_commitish || 'main', ...meta }),
      });
      log(`[create] ${tag}: 已新建 Release`);
    }
  }

  const existingAssets = new Set(
    [...(existing?.assets || []), ...(existing?.attach_files || [])]
      .map((item) => item && (item.name || item.file_name))
      .filter(Boolean),
  );

  const assets = release.assets || [];
  if (!assets.length) {
    log(`[info] ${tag}: 没有附件`);
    return;
  }
  for (const asset of assets) {
    if (existingAssets.has(asset.name)) {
      log(`[skip] ${tag}: 附件 ${asset.name} 已存在（${formatBytes(asset.size)}）`);
      continue;
    }
    if (DRY_RUN) {
      log(`[dry-run] ${tag}: 将上传附件 ${asset.name}（${formatBytes(asset.size)}）`);
      continue;
    }
    await uploadAsset(tag, asset);
  }
}

async function resolveTargets() {
  if (RELEASE_TAG) {
    const release = await getGithubRelease(RELEASE_TAG);
    if (!release) fail(`GitHub 上找不到 Release：${RELEASE_TAG}`);
    return [release];
  }
  const all = await listGithubReleases();
  if (!all.length) fail('GitHub 上没有已发布的 Release');
  if (SYNC_ALL) {
    const selected = RELEASE_LIMIT > 0 ? all.slice(0, RELEASE_LIMIT) : all;
    log(`[plan] 回填模式：共 ${all.length} 个已发布 Release，本次处理最新 ${selected.length} 个`);
    return selected;
  }
  return [all[0]];
}

async function main() {
  if (!GITHUB_TOKEN) fail('缺少 GITHUB_TOKEN');
  if (!hasAtomgitToken && !DRY_RUN) fail('缺少 AtomGit 令牌（仓库 Secret：GITCODERELEASE）');

  let mode = '最新 Release';
  if (RELEASE_TAG) mode = `单个 tag：${RELEASE_TAG}`;
  else if (SYNC_ALL) mode = RELEASE_LIMIT > 0 ? `回填最新 ${RELEASE_LIMIT} 个` : '回填全部';

  const targets = await resolveTargets();
  log(`[plan] 模式：${mode}${DRY_RUN ? '（dry-run）' : ''}；待处理 ${targets.length} 个：${targets.map((r) => r.tag_name).join(', ')}`);

  const failures = [];
  for (const release of targets) {
    try {
      await syncRelease(release);
    } catch (error) {
      const message = redact(error?.message || error);
      failures.push({ tag: release.tag_name, message });
      console.error(`[fail] ${release.tag_name}: ${message}`);
    }
    if (targets.length > 1) await sleep(1500);
  }

  log(`完成：成功 ${targets.length - failures.length}，失败 ${failures.length}${DRY_RUN ? '（dry-run，未写入 AtomGit）' : ''}`);
  if (failures.length) {
    for (const item of failures) console.error(`  ✗ ${item.tag}: ${item.message}`);
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(`[error] ${redact(error?.message || error)}`);
  process.exitCode = 1;
});
