'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const COMMIT = '5f58fa95a000bdca7e7787dcc2bcc04735bb9f91';
const TAG = 'V1.0.325';

function harness({ existing = null, prepareError, postConflict = false } = {}) {
  const events = [];
  let prepared = false;
  let created = false;
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
      return require(name);
    },
    module: { exports: {} },
    process: { env: { GITHUB_TOKEN: 'fixture-github-token', ATOMGIT_TOKEN: 'fixture-atomgit-token' }, argv: [] },
    console: { log() {}, error() {} }, URL, Buffer, setInterval, clearInterval,
    async fetch(url, options = {}) {
      const target = new URL(url);
      const method = options.method || 'GET';
      const body = options.body && JSON.parse(options.body);
      events.push({ kind: 'http', path: target.pathname, method, body });
      if (target.pathname === '/api/v5/user') return new Response(JSON.stringify({ login: 'fixture-user' }));
      if (target.pathname.endsWith(`/releases/${TAG}`) && method === 'GET') {
        const value = created ? { ...existing, tag_name: TAG } : existing;
        return new Response(value ? JSON.stringify(value) : '{}', { status: value ? 200 : 404 });
      }
      if (target.pathname.endsWith('/releases') && method === 'POST') {
        // Captured 325 failure: the mirror lacks the exact source commit until tag preparation.
        if (!prepared) return new Response(JSON.stringify({ error_code: 400, error_message: `${COMMIT} is not exist` }), { status: 400 });
        assert.equal(body.target_commitish, COMMIT);
        created = true;
        return new Response('{}', { status: postConflict ? 409 : 200 });
      }
      if (target.pathname.endsWith(`/releases/${TAG}`) && method === 'PATCH') return new Response('{}');
      throw new Error(`Unexpected request ${method} ${target.pathname}`);
    },
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'sync-atomgit-release.js'), 'utf8'), context);
  return { events, sync: vm.runInContext('syncRelease', context), redact: vm.runInContext('redact', context) };
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

test('Git credentials are scoped to the target and absent from source and target URLs', async () => {
  const h = harness();
  await h.sync({ tag_name: TAG, assets: [] });
  const { options } = h.events.find(e => e.kind === 'tag');
  assert.equal(options.sourceUrl, 'https://github.com/Monica-Pass/Monica.git');
  assert.equal(options.targetUrl, 'https://atomgit.com/Monica-Pass/Monica.git');
  assert.equal(options.targetEnv.GIT_CONFIG_KEY_1, 'http.https://atomgit.com/Monica-Pass/Monica.git.extraHeader');
  assert.equal(options.targetEnv.GIT_CONFIG_VALUE_1,
    `Authorization: Basic ${Buffer.from('fixture-user:fixture-atomgit-token').toString('base64')}`);
  assert.equal(options.targetEnv.GIT_CONFIG_VALUE_2, 'false');
  assert.equal(h.redact(options.targetEnv.GIT_CONFIG_VALUE_1), 'Authorization: Basic ***');
});
