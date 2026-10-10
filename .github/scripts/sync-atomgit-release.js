#!/usr/bin/env node
/* 把 GitHub Release 的标题、说明、预发布标记与附件同步到 AtomGit（与 gitcode 同一后端）。
 * 代码与 tag 已由 AtomGit 自动镜像，本脚本只补 Release 本体。
 * 幂等：已存在的发行版改走更新，已存在的附件跳过。
 * 用法见 .github/scripts/README.md。 */

'use strict';

const { Readable } = require('node:stream');
const { setTimeout: sleep } = require('node:timers/promises');
const https = require('node:https');

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

async function* countingStream(body, progress) {
  for await (const chunk of body) {
    progress.bytes += chunk.length;
    if (!progress.firstChunkAt) {
      progress.firstChunkAt = Date.now();
      log(`    首个数据块已到达（GitHub 下载侧正常）`);
    }
    yield chunk;
  }
}

// 进度必须由独立定时器打印：上传卡住时生成器会被背压暂停，
// 靠生成器自身输出会让日志静默，看不出是慢还是死。
function progressLine(progress, asset) {
  const elapsed = (Date.now() - progress.startedAt) / 1000;
  const rate = elapsed > 1 ? progress.bytes / 1024 / elapsed : 0;
  const percent = asset.size ? Math.min(99, Math.round((progress.bytes / asset.size) * 100)) : 0;
  return `    ${asset.name}: ${formatBytes(progress.bytes)}/${formatBytes(asset.size)}（${percent}%）${rate.toFixed(0)} KiB/s`;
}

// 对象存储的签名上传地址必须原样使用：只带接口返回的 headers，
// 不转发 AtomGit 令牌，也不跟随重定向（node:https 默认不跟随）。
function putStream(uploadUrl, headers, stream, size, options = {}) {
  const { idleTimeoutMs = 180000, label = '' } = options;
  return new Promise((resolve, reject) => {
    const target = new URL(uploadUrl);
    const request = https.request(
      {
        method: 'PUT',
        hostname: target.hostname,
        port: target.port || 443,
        path: `${target.pathname}${target.search}`,
        headers: { ...headers, 'Content-Length': String(size) },
      },
      (response) => {
        let body = '';
        response.on('data', (chunk) => {
          body += chunk;
        });
        response.on('end', () => resolve({ status: response.statusCode, body }));
      },
    );
    request.on('error', (error) => {
      stream.destroy();
      reject(error);
    });
    // 连接空闲超过阈值就中断，避免卡死的上传把整个 job 挂到超时。
    request.setTimeout(idleTimeoutMs, () => {
      request.destroy(new Error(`上传连接空闲 ${Math.round(idleTimeoutMs / 1000)}s 已中断${label ? `（${label}）` : ''}`));
    });
    stream.pipe(request);
  });
}

async function uploadAsset(tag, asset) {
  const upload = await requestJson(
    'AtomGit',
    atomgitUrl(`/releases/${encodeURIComponent(tag)}/upload_url`, { file_name: asset.name }),
    { headers: atomgitHeaders() },
  );
  const uploadUrl = upload?.url;
  if (!uploadUrl || !uploadUrl.startsWith('https://')) {
    fail(`AtomGit 未返回上传地址（${tag} / ${asset.name}）：${redact(JSON.stringify(upload)).slice(0, 300)}`);
  }

  const source = await fetch(asset.url, {
    headers: githubHeaders({ Accept: 'application/octet-stream' }),
    redirect: 'follow',
  });
  if (!source.ok) fail(`GitHub 附件下载失败：HTTP ${source.status}（${asset.name}）`);
  if (!source.body) fail(`GitHub 附件响应没有内容（${asset.name}）`);

  log(`[upload] ${tag}: ${asset.name}（${formatBytes(asset.size)}）`);
  const progress = { bytes: 0, startedAt: Date.now() };
  const ticker = setInterval(() => log(progressLine(progress, asset)), 30000);
  let response;
  try {
    response = await putStream(
      uploadUrl,
      upload.headers || {},
      Readable.from(countingStream(source.body, progress)),
      asset.size,
      { label: asset.name },
    );
  } finally {
    clearInterval(ticker);
  }
  if (response.status >= 200 && response.status < 300) {
    const elapsed = (Date.now() - progress.startedAt) / 1000;
    const rate = elapsed > 0 ? progress.bytes / 1024 / elapsed : 0;
    log(`[done] ${tag}: 已上传 ${asset.name}（${elapsed.toFixed(0)}s，平均 ${rate.toFixed(0)} KiB/s）`);
    return;
  }
  fail(`AtomGit 附件上传失败：HTTP ${response.status} ${redact(response.body).slice(0, 300)}（${asset.name}）`);
}

async function syncRelease(release) {
  const tag = release.tag_name;
  const meta = {
    name: release.name || tag,
    body: release.body || '',
    release_status: release.prerelease ? 'pre' : 'latest',
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
      && (existing.release_status || 'latest') === meta.release_status;
    if (sameMeta) {
      log(`[skip] ${tag}: 发行说明已是最新`);
    } else if (DRY_RUN) {
      log(`[dry-run] ${tag}: 将更新发行说明`);
    } else {
      await requestJson('AtomGit', atomgitUrl(`/releases/${encodeURIComponent(tag)}`), {
        method: 'PATCH',
        headers: atomgitHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(meta),
      });
      log(`[update] ${tag}: 已更新发行说明`);
    }
  } else if (DRY_RUN) {
    log(`[dry-run] ${tag}: 将新建 Release「${meta.name}」`);
  } else {
    try {
      await requestJson('AtomGit', atomgitUrl('/releases'), {
        method: 'POST',
        headers: atomgitHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ tag_name: tag, target_commitish: release.target_commitish || 'main', ...meta }),
      });
      log(`[create] ${tag}: 已新建 Release`);
    } catch (error) {
      // 查询接口可能滞后：创建冲突说明 Release 已存在，转去读现有附件。
      if (!/HTTP 409|already exist/i.test(String(error?.message))) throw error;
      log(`[warn] ${tag}: 创建返回冲突，改为读取现有 Release`);
      existing = await getAtomgitRelease(tag);
    }
  }

  const existingAssets = new Set(
    [...(existing?.assets || []), ...(existing?.attach_files || [])]
      .map((item) => item && (item.name || item.file_name))
      .filter(Boolean),
  );

  const assets = release.assets || [];
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

async function getTargetRelease() {
  if (!RELEASE_TAG) fail('未提供 Release tag（手动触发时必填 release_tag）');
  const release = await getGithubRelease(RELEASE_TAG);
  if (!release) fail(`GitHub 上找不到 Release：${RELEASE_TAG}`);
  return release;
}

// Release 事件可能先于附件上传到达（与 telegram-release.yml 同一套兜底）：
// 最多等 5 分钟，并要求附件数量在两次轮询之间稳定，避免只同步到一半的包。
// 本仓库 86 个 Release 全部至少带 1 个附件，所以等不到就是异常，直接失败，
// 宁可不建也不在 AtomGit 留一个只有源码包的空发行版。
const ASSET_WAIT_ATTEMPTS = 30;
const ASSET_WAIT_INTERVAL_MS = 10000;

async function waitForAssets(tag) {
  let previous = -1;
  for (let attempt = 1; attempt <= ASSET_WAIT_ATTEMPTS; attempt++) {
    const release = await getGithubRelease(tag);
    if (!release) fail(`GitHub 上找不到 Release：${tag}`);
    const count = (release.assets || []).length;
    if (count > 0 && count === previous) return release;
    previous = count;
    if (attempt === ASSET_WAIT_ATTEMPTS) break;
    log(`[wait] ${tag}: 附件还没到齐（当前 ${count} 个，第 ${attempt}/${ASSET_WAIT_ATTEMPTS} 次），10 秒后重试…`);
    await sleep(ASSET_WAIT_INTERVAL_MS);
  }
  fail(`${tag}: 等待 5 分钟后 GitHub Release 仍没有附件，已放弃同步`);
}

async function main() {
  if (!GITHUB_TOKEN) fail('缺少 GITHUB_TOKEN');
  if (!hasAtomgitToken && !DRY_RUN) fail('缺少 AtomGit 令牌（仓库 Secret：GITCODERELEASE）');

  let release = await getTargetRelease();
  log(`[plan] tag：${release.tag_name}${DRY_RUN ? '（dry-run）' : ''}`);
  release = await waitForAssets(release.tag_name);

  try {
    await syncRelease(release);
  } catch (error) {
    console.error(`[fail] ${release.tag_name}: ${redact(error?.message || error)}`);
    process.exitCode = 1;
    return;
  }
  log(`完成：${release.tag_name} 同步成功${DRY_RUN ? '（dry-run，未写入 AtomGit）' : ''}`);
}

if (require.main === module) {
  main().catch((error) => {
    console.error(`[error] ${redact(error?.message || error)}`);
    process.exitCode = 1;
  });
}

module.exports = { putStream, countingStream, progressLine };
