'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const COMMIT = '5f58fa95a000bdca7e7787dcc2bcc04735bb9f91';
const TAG = 'V1.0.325';

function harness({ existing = null, prepareError, postConflict = false, env = {}, sourceAssets = [], readbackMismatch = false } = {}) {
  const events = [];
  const logs = [];
  let prepared = false;
  let created = false;
  let current = existing;
  const context = vm.createContext({
    require(name) {
      if (name === './sync-atomgit-tag') return {
        async ensureReleaseTag(tag, options) {
          events.push({ kind: 'tag', tag, options });
          if (prepareError) throw prepareError;
          prepared = true;
          return COMMIT;
        },
      };
      if (name === 'node:timers/promises') return { setTimeout: async () => {} };
      if (name === 'node:fs') return { appendFileSync(file, text) { events.push({ kind: 'output', file, text }); } };
      return require(name);
    },
    module: { exports: {} },
    process: { env: { GITHUB_TOKEN: 'fixture-github-token', ATOMGIT_TOKEN: 'fixture-atomgit-token', RELEASE_TAG: TAG, ...env }, argv: [] },
    console: { log(message) { logs.push(message); }, error(message) { logs.push(message); } }, URL, Buffer, setInterval, clearInterval,
    async fetch(url, options = {}) {
      const target = new URL(url);
      const method = options.method || 'GET';
      const body = options.body && JSON.parse(options.body);
      events.push({ kind: 'http', path: target.pathname, method, body });
      if (target.hostname === 'api.github.com') {
        return new Response(JSON.stringify({ tag_name: TAG, target_commitish: 'main', assets: sourceAssets }));
      }
      if (target.pathname.endsWith('/repo_remote_mirror')) {
        return new Response(JSON.stringify({ mirroring_enabled: true, update_status: 'finished', url: 'https://private:credential@invalid.test/', last_error: 'private diagnostic' }));
      }
      if (target.pathname === '/api/v5/user') return new Response(JSON.stringify({ login: 'fixture-user' }));
      if (target.pathname.endsWith(`/releases/${TAG}`) && method === 'GET') {
        const value = created ? { ...current, tag_name: TAG, ...(readbackMismatch ? { body: 'different' } : {}) } : current;
        return new Response(value ? JSON.stringify(value) : '{}', { status: value ? 200 : 404 });
      }
      if (target.pathname.endsWith('/releases') && method === 'POST') {
        // Captured 325 failure: the mirror lacks the exact source commit until tag preparation.
        if (!prepared) return new Response(JSON.stringify({ error_code: 400, error_message: `${COMMIT} is not exist` }), { status: 400 });
        assert.equal(body.target_commitish, COMMIT);
        created = true;
        current = body;
        return new Response('{}', { status: postConflict ? 409 : 200 });
      }
      if (target.pathname.endsWith(`/releases/${TAG}`) && method === 'PATCH') {
        current = { ...current, ...body };
        return new Response('{}');
      }
      throw new Error(`Unexpected request ${method} ${target.pathname}`);
    },
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'sync-atomgit-release.js'), 'utf8'), context);
  return { events, logs, sync: vm.runInContext('syncRelease', context), main: vm.runInContext('main', context),
    targetRelease: vm.runInContext('getTargetRelease', context), redact: vm.runInContext('redact', context) };
}

test('missing mirror commit is prepared before release creation; target_commitish is the resolved tag', async () => {
  const h = harness();
  await h.sync({ tag_name: TAG, target_commitish: 'main', name: 'Monica 1.0.325', body: 'Exact existing notes', assets: [] });
  const create = h.events.findIndex(e => e.method === 'POST');
  assert.ok(h.events.findIndex(e => e.kind === 'tag') < create);
  assert.equal(h.events[create].body.target_commitish, COMMIT);
  assert.equal(h.events[create].body.body, 'Exact existing notes');
});

test('conflicting or unavailable source tag prevents all release writes', async () => {
  const h = harness({ prepareError: new Error('tag conflict') });
  await assert.rejects(h.sync({ tag_name: TAG, assets: [] }), /tag conflict/);
  assert.equal(h.events.filter(e => ['POST', 'PATCH', 'PUT'].includes(e.method)).length, 0);
});

test('an existing release still verifies its tag before changing metadata', async () => {
  const h = harness({ existing: { name: 'old', body: 'old', assets: [] }, prepareError: new Error('tag conflict') });
  await assert.rejects(h.sync({ tag_name: TAG, name: 'new', body: 'new', assets: [] }), /tag conflict/);
  assert.equal(h.events.filter(e => e.method === 'PATCH').length, 0);
});

test('both service credentials are redacted from diagnostic text', () => {
  const h = harness();
  assert.equal(h.redact('fixture-github-token fixture-atomgit-token access_token=another'), '*** *** access_token=***');
});

test('public Git tag reads receive no API credentials and request no user identity', async () => {
  const h = harness();
  await h.sync({ tag_name: TAG, assets: [] });
  const { options } = h.events.find(e => e.kind === 'tag');
  assert.equal(options.sourceUrl, 'https://github.com/Monica-Pass/Monica.git');
  assert.equal(options.targetUrl, 'https://atomgit.com/Monica-Pass/Monica.git');
  assert.equal(options.targetEnv, undefined);
  assert.equal(h.events.filter(e => e.path === '/api/v5/user').length, 0);
});

test('pending mirror emits pending output, no writes and no false synchronization success', async () => {
  const error = new Error('waiting');
  error.code = 'MIRROR_PENDING';
  const h = harness({ prepareError: error, sourceAssets: [{ name: 'fixture.apk', size: 1 }],
    env: { GITHUB_OUTPUT: 'output', GITHUB_STEP_SUMMARY: 'summary' } });
  await h.main();
  assert.ok(h.events.some(e => e.kind === 'output' && e.text === 'sync_status=pending\n'));
  assert.equal(h.events.filter(e => ['POST', 'PATCH', 'PUT'].includes(e.method)).length, 0);
  assert.ok(!h.logs.some(line => /同步成功|private|credential/.test(line)));
});

test('scheduled compensation resolves the latest stable release; manual runs keep their tag', async () => {
  const scheduled = harness({ env: { GITHUB_EVENT_NAME: 'schedule', RELEASE_TAG: '' } });
  assert.equal((await scheduled.targetRelease()).tag_name, TAG);
  assert.ok(scheduled.events.some(e => e.path.endsWith('/releases/latest')));
  const manual = harness({ env: { GITHUB_EVENT_NAME: 'workflow_dispatch' } });
  await manual.targetRelease();
  assert.ok(manual.events.some(e => e.path.endsWith(`/releases/tags/${TAG}`)));
});

test('successful create response alone does not count as synchronized if readback differs', async () => {
  const h = harness({ readbackMismatch: true });
  await assert.rejects(h.sync({ tag_name: TAG, name: 'Monica', body: 'Exact notes', assets: [] }), /回读尚未确认/);
  assert.ok(!h.logs.some(line => line.startsWith('[verify]')));
});

test('an already synchronized release skips existing attachments and verifies the final state', async () => {
  const existing = { name: 'Monica', body: 'Exact notes', assets: [{ name: 'fixture.apk' }] };
  const h = harness({ existing });
  await h.sync({ ...existing, tag_name: TAG, assets: [{ name: 'fixture.apk', size: 12 }] });
  assert.equal(h.events.filter(e => ['POST', 'PATCH', 'PUT'].includes(e.method)).length, 0);
  assert.ok(h.logs.some(line => line.startsWith('[verify]')));
});
