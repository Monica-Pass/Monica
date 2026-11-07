'use strict';

const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

const exec = promisify(execFile);

async function runGit(args, options = {}) {
  try {
    const result = await exec('git', args, {
      timeout: 120000, maxBuffer: 8 * 1024 * 1024, windowsHide: true,
      ...options,
      env: { ...process.env, GIT_TERMINAL_PROMPT: '0', GIT_TRACE: '0', GIT_TRACE_CURL: '0',
        GIT_CURL_VERBOSE: '0', ...options.env },
    });
    return result.stdout.trim();
  } catch (error) {
    throw new Error(`git ${args[0]} failed: ${error.stderr || error.message}`);
  }
}

function tagFromRefs(text, ref) {
  // ls-remote is SHA then ref; select only the exact requested names.
  const byName = new Map(text.split(/\r?\n/).filter(Boolean).map(line => {
    const [sha, name] = line.split(/\s+/);
    return [name, sha];
  }));
  const object = byName.get(ref);
  if (!object) return null;
  const commit = byName.get(`${ref}^{}`) || object;
  if (!/^[a-f0-9]{40}$/.test(object) || !/^[a-f0-9]{40}$/.test(commit)) {
    throw new Error(`Invalid tag object for ${ref}`);
  }
  return { object, commit };
}

// Copy just one missing tag and its reachable objects. Existing refs are never rewritten.
// The isolated partial clone avoids changing the caller's checkout or downloading old APK blobs.
async function ensureReleaseTag(tag, { sourceUrl, targetUrl, targetEnv = {}, dryRun = false,
  log = console.log, git = runGit } = {}) {
  const ref = `refs/tags/${tag}`;
  await git(['check-ref-format', ref]);
  const source = tagFromRefs(await git(['ls-remote', '--', sourceUrl, ref, `${ref}^{}`]), ref);
  if (!source) throw new Error(`GitHub tag ${tag} does not exist`);
  const readTarget = async () => tagFromRefs(await git(['ls-remote', '--', targetUrl, ref, `${ref}^{}`], { env: targetEnv }), ref);
  const validate = (target) => {
    if (target && target.commit !== source.commit) {
      throw new Error(`AtomGit tag ${tag} conflicts: expected ${source.commit}, found ${target.commit}; no tag was overwritten`);
    }
    return target;
  };
  if (validate(await readTarget())) {
    log(`[tag] ${tag}: AtomGit commit verified ${source.commit}`);
    return source.commit;
  }
  if (dryRun) {
    log(`[dry-run] ${tag}: would copy missing tag and commit ${source.commit}`);
    return source.commit;
  }

  const tempParent = await fs.realpath(os.tmpdir());
  const temp = await fs.mkdtemp(path.join(tempParent, 'monica-release-tag-'));
  const repo = path.join(temp, 'source.git');
  try {
    log(`[tag] ${tag}: fetching source tag ${source.commit}`);
    await git(['clone', '--bare', '--filter=blob:none', '--single-branch', '--branch', tag,
      '--', sourceUrl, repo], { timeout: 600000 });
    const object = await git(['rev-parse', '--verify', ref], { cwd: repo });
    const commit = await git(['rev-parse', '--verify', `${ref}^{commit}`], { cwd: repo });
    if (object !== source.object || commit !== source.commit) {
      throw new Error(`GitHub tag ${tag} changed while fetching; no target writes were made`);
    }
    if (validate(await readTarget())) return source.commit;
    log(`[tag] ${tag}: copying only ${ref} to AtomGit`);
    try {
      await git(['push', '--porcelain', '--', targetUrl, `${ref}:${ref}`], {
        cwd: repo, env: targetEnv, timeout: 600000,
      });
    } catch (error) {
      // A concurrent mirror or a lost push response is safe only after verifying the exact commit.
      if (!validate(await readTarget())) throw error;
    }
    if (!validate(await readTarget())) throw new Error(`AtomGit tag ${tag} is still missing after push`);
    log(`[tag] ${tag}: AtomGit commit verified ${source.commit}`);
    return source.commit;
  } finally {
    const resolved = await fs.realpath(temp);
    if (path.dirname(resolved) !== tempParent || !path.basename(resolved).startsWith('monica-release-tag-')) {
      throw new Error('Unexpected tag workspace path; refused cleanup');
    }
    await fs.rm(resolved, { recursive: true, force: true });
  }
}

module.exports = { ensureReleaseTag, tagFromRefs, runGit };
