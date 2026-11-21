'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const assets = path.join(__dirname, '../app/src/main/assets/parent');
const source = fs.readFileSync(path.join(assets, 'app.js'), 'utf8');
const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');

function element() {
  const listeners = new Map();
  const classes = new Set();
  const children = new Map();
  return {
    hidden: false, disabled: false, textContent: '', value: '', dataset: {}, style: {},
    isConnected: true,
    classList: { add: name => classes.add(name), remove: name => classes.delete(name),
      toggle(name, on) { on ? classes.add(name) : classes.delete(name); } },
    addEventListener(name, callback) { listeners.set(name, callback); },
    dispatch(name, event = {}) { return listeners.get(name)?.({ preventDefault() {}, ...event }); },
    querySelector(selector) {
      if (!children.has(selector)) children.set(selector, element());
      return children.get(selector);
    },
    reset() {}, setAttribute() {}, removeAttribute() {}, focus() {}, getClientRects() { return [1]; }
  };
}

function browser(reply) {
  const nodes = new Map();
  for (const tag of html.match(/<[^>]+\bid="[^"]+"[^>]*>/g)) {
    const id = tag.match(/\bid="([^"]+)"/)[1];
    const node = element();
    node.id = id;
    node.name = tag.match(/\bname="([^"]+)"/)?.[1] || '';
    node.hidden = /(?:\s)hidden(?:[\s>])/.test(tag);
    for (const key of ['code', 'action', 'page']) {
      const value = tag.match(new RegExp('\\bdata-' + key + '="([^"]+)"'));
      if (value) node.dataset[key] = value[1];
    }
    nodes.set(id, node);
  }
  for (const match of html.matchAll(/<form\b[^>]*\bid="([^"]+)"[^>]*>([\s\S]*?)<\/form>/g)) {
    const form = nodes.get(match[1]);
    form.fields = [...match[2].matchAll(/<input\b[^>]*\bid="([^"]+)"/g)]
      .map(input => nodes.get(input[1]));
    form.reset = () => { form.fields.forEach(input => { input.value = ''; }); };
  }
  const buttons = [...nodes.values()].filter(node => node.dataset.code);
  const forms = [...nodes.values()].filter(node => node.dataset.action);
  const doc = { hidden: false, body: element(), activeElement: null,
    getElementById: id => nodes.get(id),
    querySelectorAll(selector) {
      if (selector === '[data-code]') return buttons;
      if (selector === 'form[data-action]') return forms;
      if (selector === '[data-page]' || selector === '.page-nav [data-page]') {
        return [...nodes.values()].filter(node => node.dataset.page);
      }
      if (selector === '.page') return [...nodes.values()].filter(node => node.id.startsWith('page-'));
      return [];
    }, addEventListener() {} };
  const timers = [];
  const calls = [];
  vm.runInNewContext(source, {
    document: doc, AbortController, setTimeout, clearTimeout,
    FormData: class {
      constructor(form) { this.entries = form.fields.map(input => [input.name, input.value]); }
      [Symbol.iterator]() { return this.entries[Symbol.iterator](); }
    },
    setInterval(callback) { timers.push(callback); },
    fetch: async (url, options) => {
      calls.push({ url, options });
      const { status = 200, data } = await reply(url, options);
      return { ok: status >= 200 && status < 300, status, json: async () => data };
    }
  }, { filename: 'parent/app.js' });
  return { nodes, calls, timers, forms };
}

async function settle() {
  for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve));
}

function quotaState() {
  return { version: '2.6.9', enabled: true, locked: true, usedSeconds: 1800,
    dailyMinutes: 30, remainingSeconds: 0, bonusSeconds: 0,
    guardRunning: true, settingsProtection: true, adminSupported: true, adminActive: false,
    availableActions: ['00', '11', '12', '22', '33', '44', '55', '77'] };
}

test('admin activation is offered only on supported inactive firmware', async () => {
  for (const [adminSupported, adminActive, expected] of [
    [false, false, '当前系统不支持开启设备管理员防卸载'],
    [true, false, '未开启'],
    [true, true, '已开启'],
    [false, true, '已开启']
  ]) {
    const state = { ...quotaState(), adminSupported, adminActive };
    state.availableActions = state.availableActions.filter(code => code !== '55');
    if (adminSupported && !adminActive) state.availableActions.push('55');
    const page = browser(url => {
      if (url === '/api/session') return { data: { authenticated: true, csrf: 'parent-csrf' } };
      if (url === '/api/state') return { data: state };
      throw new Error('Unexpected request: ' + url);
    });
    await settle();
    assert.equal(page.nodes.get('admin-status').textContent, expected);
    const activate = page.nodes.get('enable-admin');
    assert.equal(activate.hidden, !(adminSupported && !adminActive));
    assert.equal(activate.disabled, !(adminSupported && !adminActive));
    if (activate.hidden) {
      activate.dispatch('click');
      await settle();
      assert.equal(page.calls.some(call => call.url === '/api/action'), false);
    }
  }
});

function fillPinForm(page) {
  page.nodes.get('old-pin').value = '024680';
  page.nodes.get('new-pin').value = '246810';
  page.nodes.get('confirm-pin').value = '246810';
  const form = page.nodes.get('pin-form');
  form.dispatch('input');
  return form;
}

test('restored login keeps logout available even when the first status request fails', async () => {
  const page = browser(url => {
    if (url === '/api/session') return { data: { authenticated: true, csrf: 'browser-csrf' } };
    if (url === '/api/logout') return { data: { message: 'logged out' } };
    return { status: 503, data: { message: 'state unavailable' } };
  });
  await settle();
  assert.equal(page.nodes.get('panel-view').hidden, false);
  assert.equal(page.nodes.get('login-view').hidden, true);
  assert.equal(page.nodes.get('notice').textContent, 'state unavailable');
  const logout = page.nodes.get('logout');
  assert.equal(logout.hidden, false, 'logout must not depend on a successful quota snapshot');
  assert.equal(logout.disabled, false);
  page.forms.forEach(form => assert.equal(form.querySelector('fieldset').disabled, true));
  logout.dispatch('click');
  await settle();
  const request = page.calls.find(call => call.url === '/api/logout');
  assert.ok(request);
  assert.equal(request.options.headers['X-CSRF-Token'], 'browser-csrf');
  assert.equal(page.nodes.get('login-view').hidden, false);
  assert.equal(page.nodes.get('panel-view').hidden, true);
  assert.equal(logout.hidden, true);
});

test('failed initial session discovery leaves only the login view', async () => {
  const page = browser(() => ({ status: 503, data: { message: 'session unavailable' } }));
  await settle();
  assert.equal(page.nodes.get('login-view').hidden, false);
  assert.equal(page.nodes.get('panel-view').hidden, true);
  assert.equal(page.nodes.get('logout').hidden, true);
  assert.equal(page.nodes.get('login-notice').textContent, 'session unavailable');
});

test('login submits only the six-digit shared parent password and renders state without an account', async () => {
  const page = browser(url => {
    if (url === '/api/session') return { data: { authenticated: false } };
    if (url === '/api/login') return { data: { authenticated: true, csrf: 'parent-csrf' } };
    if (url === '/api/state') return { data: quotaState() };
    throw new Error('Unexpected request: ' + url);
  });
  await settle();
  assert.equal(page.nodes.has('login-username'), false);
  assert.equal(page.nodes.has('account-form'), false);
  assert.deepEqual(page.nodes.get('login-form').fields.map(input => input.name), ['password']);
  page.nodes.get('login-password').value = '024680';

  await page.nodes.get('login-form').dispatch('submit');
  await settle();

  assert.deepEqual(page.calls.map(call => call.url), ['/api/session', '/api/login', '/api/state']);
  const login = page.calls.find(call => call.url === '/api/login');
  assert.equal(login.options.method, 'POST');
  assert.deepEqual(JSON.parse(login.options.body), { password: '024680' });
  assert.equal(page.nodes.get('panel-view').hidden, false);
  assert.equal(page.nodes.get('login-view').hidden, true);
  assert.equal(page.nodes.get('used-time').textContent, '30:00');
  assert.equal(page.nodes.get('daily-minutes').value, 30);
  assert.equal(page.nodes.get('pin-form').querySelector('fieldset').disabled, false);
  assert.equal(page.nodes.get('login-password').value, '');
});

test('invalid login PINs never reach the server and rejected PINs stay on the login page', async () => {
  const page = browser(url => {
    if (url === '/api/session') return { data: { authenticated: false } };
    if (url === '/api/login') return { status: 401, data: { message: '家长密码不正确' } };
    throw new Error('Unexpected request: ' + url);
  });
  await settle();
  for (const password of ['', '12345', '1234567', '12345a']) {
    page.nodes.get('login-password').value = password;
    await page.nodes.get('login-form').dispatch('submit');
    assert.equal(page.calls.length, 1);
    assert.equal(page.nodes.get('login-notice').textContent, '家长密码须为 6 位数字');
  }
  page.nodes.get('login-password').value = '123456';
  await page.nodes.get('login-form').dispatch('submit');
  await settle();
  assert.deepEqual(page.calls.map(call => call.url), ['/api/session', '/api/login']);
  assert.equal(page.nodes.get('login-view').hidden, false);
  assert.equal(page.nodes.get('panel-view').hidden, true);
  assert.equal(page.nodes.get('login-notice').textContent, '家长密码不正确');
  assert.equal(page.nodes.get('login-submit').disabled, false);
});

test('changing the shared parent PIN returns straight to login without a state request', async () => {
  const page = browser(url => {
    if (url === '/api/session') return { data: { authenticated: true, csrf: 'old-csrf' } };
    if (url === '/api/state') return { data: quotaState() };
    if (url === '/api/action') return { data: { message: 'updated' } };
    if (url === '/api/login') return { data: { authenticated: true, csrf: 'new-csrf' } };
    throw new Error('Unexpected request: ' + url);
  });
  await settle();
  page.nodes.get('tab-account').dispatch('click');
  assert.equal(page.nodes.get('page-account').hidden, false);
  assert.equal(page.nodes.get('page-control').hidden, true);
  const form = fillPinForm(page);
  form.dispatch('submit');
  await settle();

  assert.deepEqual(page.calls.map(call => call.url), ['/api/session', '/api/state', '/api/action']);
  const change = page.calls.find(call => call.url === '/api/action');
  assert.deepEqual(JSON.parse(change.options.body), {
    code: '11', oldPin: '024680', newPin: '246810', confirmPin: '246810'
  });
  assert.equal(change.options.headers['X-CSRF-Token'], 'old-csrf');
  assert.equal(page.nodes.get('login-view').hidden, false);
  assert.equal(page.nodes.get('panel-view').hidden, true);
  assert.equal(page.nodes.get('logout').hidden, true);
  assert.equal(page.nodes.get('login-notice').textContent, '家长密码已更新，请重新登录');
  assert.equal(form.dataset.dirty, undefined);
  for (const id of ['old-pin', 'new-pin', 'confirm-pin']) assert.equal(page.nodes.get(id).value, '');
  page.timers.forEach(tick => tick());
  await settle();
  assert.equal(page.calls.length, 3, 'polling must stop after the password change ends the session');

  page.nodes.get('login-password').value = '246810';
  await page.nodes.get('login-form').dispatch('submit');
  await settle();
  const login = page.calls.find(call => call.url === '/api/login');
  assert.deepEqual(JSON.parse(login.options.body), { password: '246810' });
  assert.equal(login.options.headers['X-CSRF-Token'], undefined, 'old session credentials must be cleared');
  assert.equal(page.nodes.get('panel-view').hidden, false);
  assert.equal(page.nodes.get('login-notice').hidden, true);
});

test('a rejected password change keeps the form and session available for correction', async () => {
  const page = browser(url => {
    if (url === '/api/session') return { data: { authenticated: true, csrf: 'parent-csrf' } };
    if (url === '/api/state') return { data: quotaState() };
    if (url === '/api/action') return { status: 400, data: { message: '当前家长密码不正确' } };
    throw new Error('Unexpected request: ' + url);
  });
  await settle();
  const form = fillPinForm(page);
  form.dispatch('submit');
  await settle();
  assert.deepEqual(page.calls.map(call => call.url), ['/api/session', '/api/state', '/api/action']);
  assert.equal(page.nodes.get('login-view').hidden, true);
  assert.equal(page.nodes.get('panel-view').hidden, false);
  assert.equal(form.querySelector('.form-error').textContent, '当前家长密码不正确');
  assert.equal(form.querySelector('fieldset').disabled, false);
  assert.equal(page.nodes.get('new-pin').value, '246810');
});

test('a stale status request cannot reopen the panel or erase the password-change notice', async () => {
  let finishStaleState;
  let stateReads = 0;
  const page = browser(url => {
    if (url === '/api/session') return { data: { authenticated: true, csrf: 'parent-csrf' } };
    if (url === '/api/state') {
      if (++stateReads === 1) return { data: quotaState() };
      return new Promise(resolve => { finishStaleState = resolve; });
    }
    if (url === '/api/action') return { data: { message: 'updated' } };
    throw new Error('Unexpected request: ' + url);
  });
  await settle();
  page.timers.forEach(tick => tick());
  assert.equal(typeof finishStaleState, 'function');
  fillPinForm(page).dispatch('submit');
  await settle();
  assert.equal(page.nodes.get('login-view').hidden, false, 'password changes must not wait for old GETs');
  finishStaleState({ status: 401, data: { message: 'expired old session' } });
  await settle();
  assert.equal(page.nodes.get('panel-view').hidden, true);
  assert.equal(page.nodes.get('login-notice').textContent, '家长密码已更新，请重新登录');
  assert.equal(stateReads, 2);
});
