'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { ensureReleaseTag, tagFromRefs, runGit } = require('./sync-atomgit-tag');

const TAG = 'V1.0.325';

async function fixture(t) {
  const parent = await fs.realpath(os.tmpdir());
  const root = await fs.mkdtemp(path.join(parent, 'monica-tag-test-'));
  t.after(async () => {
    const resolved = await fs.realpath(root);
    assert.equal(path.dirname(resolved), parent);
    assert.ok(path.basename(resolved).startsWith('monica-tag-test-'));
    await fs.rm(resolved, { recursive: true, force: true });
  });
  const source = path.join(root, 'source');
  const target = path.join(root, 'target.git');
  await runGit(['init', '--initial-branch=main', source]);
  await runGit(['init', '--bare', '--initial-branch=main', target]);
  const git = (args) => runGit(args, { cwd: source });
  const commit = async (text) => {
    await fs.writeFile(path.join(source, 'tracked.txt'), text);
    await git(['add', '--', 'tracked.txt']);
    await git(['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-m', text]);
    return git(['rev-parse', 'HEAD']);
  };
  const old = await commit('already mirrored');
  await git(['push', target, 'main:refs/heads/main']);
  const current = await commit('release source');
  await git(['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'tag', '-a', TAG, '-m', 'release']);
  await fs.writeFile(path.join(source, 'private-untracked.txt'), 'never pushed');
  return { source, target, old, current, git,
    options: { sourceUrl: source, targetUrl: target, log() {} } };
}

test('a missing mirrored tag is explicitly pending and performs no Git writes', async t => {
  const f = await fixture(t);
  const calls = [];
  await assert.rejects(ensureReleaseTag(TAG, { ...f.options, git: async (args, opts) => {
    calls.push(args[0]); return runGit(args, opts);
  } }), { code: 'MIRROR_PENDING' });
  assert.deepEqual(calls, ['check-ref-format', 'ls-remote', 'ls-remote']);
  assert.equal(await runGit(['rev-parse', 'main'], { cwd: f.target }), f.old);
  assert.equal(await f.git(['status', '--porcelain']), '?? private-untracked.txt');
  assert.equal(await runGit(['for-each-ref', '--format=%(refname)'], { cwd: f.target }), 'refs/heads/main');
});

test('existing correct tag is idempotent and needs no clone or push', async t => {
  const f = await fixture(t);
  await f.git(['push', f.target, `refs/tags/${TAG}:refs/tags/${TAG}`]);
  const calls = [];
  assert.equal(await ensureReleaseTag(TAG, { ...f.options, git: async (args, opts) => {
    calls.push(args[0]); return runGit(args, opts);
  } }), f.current);
  assert.ok(!calls.includes('clone') && !calls.includes('push'));
});

test('an existing conflicting tag is not moved or replaced', async t => {
  const f = await fixture(t);
  await f.git(['push', f.target, `${f.old}:refs/tags/${TAG}`]);
  await assert.rejects(ensureReleaseTag(TAG, f.options), /conflicts/);
  assert.equal(await runGit(['rev-parse', `refs/tags/${TAG}`], { cwd: f.target }), f.old);
});

test('a later run becomes ready once the external mirror copies the exact annotated tag', async t => {
  const f = await fixture(t);
  await assert.rejects(ensureReleaseTag(TAG, f.options), { code: 'MIRROR_PENDING' });
  // Simulate the provider's mirror updating independently of the release script.
  await f.git(['push', f.target, `refs/tags/${TAG}:refs/tags/${TAG}`]);
  assert.equal(await ensureReleaseTag(TAG, f.options), f.current);
  assert.equal(await runGit(['rev-parse', 'main'], { cwd: f.target }), f.old);
});

test('missing or invalid source tags fail before writes', async t => {
  const f = await fixture(t);
  await assert.rejects(ensureReleaseTag('absent', f.options), /does not exist/);
  await assert.rejects(ensureReleaseTag('bad..tag', f.options), /check-ref-format failed/);
  assert.equal(await runGit(['tag', '--list'], { cwd: f.target }), '');
});

test('a Git network failure is an error, not pending or success', async t => {
  const f = await fixture(t);
  await assert.rejects(ensureReleaseTag(TAG, { ...f.options, git: async (args, opts) => {
    if (args[0] === 'ls-remote') throw new Error('network failed');
    return runGit(args, opts);
  } }), error => error.message === 'network failed' && error.code !== 'MIRROR_PENDING');
  assert.equal(await runGit(['tag', '--list'], { cwd: f.target }), '');
});

test('malformed tag object is rejected', () => {
  assert.throws(() => tagFromRefs(`invalid\trefs/tags/${TAG}`, `refs/tags/${TAG}`), /Invalid tag object/);
});

test('tag refs use exact names and peel annotations; unrelated matching SHA does not erase the tag', () => {
  const object = 'a'.repeat(40), commit = 'b'.repeat(40);
  assert.deepEqual(tagFromRefs(`${object}\trefs/tags/${TAG}\n${commit}\trefs/tags/${TAG}^{}\n${object}\trefs/tags/other`, `refs/tags/${TAG}`), { object, commit });
  assert.equal(tagFromRefs(`${object}\trefs/tags/${TAG}-other`, `refs/tags/${TAG}`), null);
});
