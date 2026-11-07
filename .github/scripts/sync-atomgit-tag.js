'use strict';

const { execFile } = require('node:child_process');
const { promisify } = require('node:util');

const exec = promisify(execFile);

async function runGit(args, options = {}) {
  const env = { ...process.env, GIT_TERMINAL_PROMPT: '0', ...options.env };
  // GIT_CURL_VERBOSE is enabled by its presence, including the value "0".
  for (const name of ['GIT_TRACE', 'GIT_TRACE_CURL', 'GIT_CURL_VERBOSE', 'GIT_TRACE_PACKET']) delete env[name];
  try {
    const result = await exec('git', args, {
      timeout: 120000, maxBuffer: 8 * 1024 * 1024, windowsHide: true,
      ...options, env,
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

class MirrorPendingError extends Error {
  constructor(tag, commit) {
    super(`AtomGit tag ${tag} has not reached commit ${commit}; waiting for the read-only mirror`);
    this.code = 'MIRROR_PENDING';
  }
}

// Mirror repositories reject Git pushes. Read both tags without modifying any ref;
// a scheduled workflow retries publication after the provider's mirror catches up.
async function ensureReleaseTag(tag, { sourceUrl, targetUrl, git = runGit } = {}) {
  const ref = `refs/tags/${tag}`;
  await git(['check-ref-format', ref]);
  const source = tagFromRefs(await git(['ls-remote', '--', sourceUrl, ref, `${ref}^{}`]), ref);
  if (!source) throw new Error(`GitHub tag ${tag} does not exist`);
  const target = tagFromRefs(await git(['ls-remote', '--', targetUrl, ref, `${ref}^{}`]), ref);
  if (!target) throw new MirrorPendingError(tag, source.commit);
  if (target.commit !== source.commit) {
    throw new Error(`AtomGit tag ${tag} conflicts: expected ${source.commit}, found ${target.commit}; no release or tag was changed`);
  }
  return source.commit;
}

module.exports = { ensureReleaseTag, tagFromRefs, runGit, MirrorPendingError };
