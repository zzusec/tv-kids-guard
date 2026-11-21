'use strict';

(() => {
  const byId = id => document.getElementById(id);
  const actionButtons = Array.from(document.querySelectorAll('[data-code]'));
  const navigation = Array.from(document.querySelectorAll('[data-page]'));
  const forms = Array.from(document.querySelectorAll('form[data-action]'));
  let csrf = '';
  let authenticated = false;
  let state = null;
  let busy = false;
  let busyFocus = null;
  let loadingState = null;
  let stateGeneration = 0;
  let authGeneration = 0;
  let syncFailureMessage = '';
  let activeForm = null;
  let pendingConfirmation = '';
  let currentPage = 'control';
  let availableActions = [];

  function notify(message, error = false, form = activeForm) {
    const inlineOnly = Boolean(error && form && authenticated);
    for (const id of ['notice', 'login-notice']) {
      const notice = byId(id);
      const visible = id === (authenticated ? 'notice' : 'login-notice') && !inlineOnly;
      notice.textContent = visible ? message || '' : '';
      notice.hidden = !notice.textContent;
      notice.classList.toggle('error', error);
    }
    forms.forEach(item => {
      const inline = item.querySelector('.form-error');
      inline.textContent = error && item === form ? message || '' : '';
      inline.hidden = !inline.textContent;
    });
  }

  function updateAvailability() {
    actionButtons.forEach(button => {
      const allowed = authenticated && (button.dataset.code === '00'
        || availableActions.includes(button.dataset.code));
      button.hidden = !allowed;
      button.disabled = busy || !allowed;
    });
    forms.forEach(form => {
      form.querySelector('fieldset').disabled = busy || !authenticated
        || !availableActions.includes(form.dataset.action);
    });
  }

  function setBusy(value) {
    if (value && !busy) busyFocus = document.activeElement;
    busy = value;
    byId('login-submit').disabled = value;
    byId('login-submit').textContent = value ? '正在登录…' : '登录';
    navigation.forEach(button => { button.disabled = value; });
    document.querySelectorAll('[data-cancel]').forEach(button => { button.disabled = value; });
    updateAvailability();
    if (!value && busyFocus && document.activeElement === document.body
        && busyFocus.isConnected && !busyFocus.disabled && busyFocus.getClientRects().length) {
      busyFocus.focus({ preventScroll: true });
    }
    if (!value) busyFocus = null;
  }

  async function request(path, body) {
    const generation = authGeneration;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
      const headers = { Accept: 'application/json' };
      if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
        if (csrf) headers['X-CSRF-Token'] = csrf;
      }
      const response = await fetch(path, {
        method: body === undefined ? 'GET' : 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal
      });
      let data;
      try { data = await response.json(); }
      catch (_) { throw new Error('电视响应异常，请重试'); }
      if (!response.ok) {
        const failure = new Error(data.message || data.error || '操作失败，请重试');
        failure.status = response.status;
        if (response.status === 401 && authenticated && generation === authGeneration) {
          showLogin();
          failure.expiredAuthGeneration = authGeneration;
        }
        throw failure;
      }
      return data;
    } catch (error) {
      const failure = error.name === 'AbortError' ? new Error('连接电视超时，请重试')
        : error instanceof TypeError ? new Error('无法连接电视，请检查网络') : error;
      failure.authGeneration = generation;
      throw failure;
    } finally {
      clearTimeout(timer);
    }
  }

  function currentFailure(error) {
    return error.authGeneration === undefined || error.authGeneration === authGeneration
      || error.expiredAuthGeneration === authGeneration;
  }

  function resetConfirmations() {
    pendingConfirmation = '';
    document.querySelectorAll('[data-confirm-row]').forEach(row => {
      row.classList.remove('pending');
      const button = row.querySelector('[data-code]');
      button.textContent = button.dataset.label;
      const cancel = row.querySelector('[data-cancel]');
      cancel.tabIndex = -1;
      cancel.setAttribute('aria-hidden', 'true');
    });
  }

  function showPage(page, focus = false) {
    if (!['control', 'viewing', 'account', 'system'].includes(page)) return;
    currentPage = page;
    resetConfirmations();
    document.querySelectorAll('.page').forEach(section => { section.hidden = section.id !== 'page-' + page; });
    document.querySelectorAll('.page-nav [data-page]').forEach(button => {
      if (button.dataset.page === page) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    activeForm = null;
    notify('');
    if (focus) byId('tab-' + page).focus({ preventScroll: true });
  }

  function showLogin() {
    authGeneration += 1;
    authenticated = false;
    csrf = '';
    state = null;
    availableActions = [];
    // A request from the previous login cannot block the new session's first state read.
    loadingState = null;
    activeForm = null;
    byId('shell').classList.add('login-mode');
    byId('panel-view').hidden = true;
    byId('login-view').hidden = false;
    byId('initial-loading').hidden = true;
    byId('version').textContent = '';
    byId('login-form').reset();
    forms.forEach(form => {
      form.reset();
      delete form.dataset.dirty;
    });
    resetConfirmations();
    updateAvailability();
  }

  async function showPanel(session) {
    authGeneration += 1;
    authenticated = true;
    csrf = session.csrf || '';
    byId('shell').classList.remove('login-mode');
    byId('login-view').hidden = true;
    byId('panel-view').hidden = false;
    byId('initial-loading').hidden = true;
    byId('login-form').reset();
    showPage('control');
    updateAvailability();
    await loadState();
  }

  function clock(seconds) {
    seconds = Math.max(0, Math.floor(Number(seconds) || 0));
    return Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
  }

  function render(data) {
    state = data;
    byId('version').textContent = data.version ? 'v' + data.version : '';
    byId('system-version').textContent = data.version ? 'v' + data.version : '';
    const protection = byId('protection-status');
    protection.textContent = !data.enabled ? '防护已关闭' : data.locked ? '电视已锁定' : '防护已开启';
    protection.classList.toggle('enabled', Boolean(data.enabled && !data.locked));
    protection.classList.toggle('locked', Boolean(data.enabled && data.locked));
    byId('system-protection').textContent = data.enabled ? '已开启' : '已关闭';
    byId('admin-status').textContent = data.adminActive ? '已开启'
      : data.adminSupported ? '未开启' : '当前系统不支持开启设备管理员防卸载';
    byId('used-time').textContent = clock(data.usedSeconds);
    byId('daily-limit').textContent = data.dailyMinutes + ' 分钟';
    const remainingSec = Math.max(0, Number(data.remainingSeconds) || 0);
    byId('remaining-time').textContent = clock(remainingSec);
    byId('remaining-time').classList.toggle('exhausted', remainingSec <= 0);
    const totalSec = Math.max(1, (Number(data.dailyMinutes) || 0) * 60 + (Number(data.bonusSeconds) || 0));
    const ratio = Math.max(0, Math.min(100, Math.round(remainingSec / totalSec * 100)));
    byId('quota-bar').style.width = ratio + '%';
    byId('quota-bar').classList.toggle('exhausted', remainingSec <= 0);
    byId('quota-bar').classList.toggle('low', remainingSec > 0 && ratio <= 20);
    byId('bonus-summary').hidden = !(data.bonusSeconds > 0);
    byId('bonus-summary').textContent = '已加时 ' + Math.floor((data.bonusSeconds || 0) / 60) + ' 分钟';
    byId('grace-summary').hidden = !data.grace;
    byId('guard-status').textContent = data.guardRunning ? '运行中' : data.enabled ? '未运行' : '已停止';
    byId('settings-status').textContent = typeof data.settingsProtection === 'string'
      ? data.settingsProtection : data.settingsProtection ? '已连接' : '未连接';
    byId('update-message').hidden = !data.updateMessage;
    byId('update-message').textContent = data.updateMessage || '';
    byId('warning').hidden = !data.warning;
    byId('warning').textContent = data.warning || '';
    if (!byId('daily-form').dataset.dirty) byId('daily-minutes').value = data.dailyMinutes;
    byId('bonus-minutes').max = String(Math.max(0, 60 - Math.floor((data.bonusSeconds || 0) / 60)));
    availableActions = Array.isArray(data.availableActions) ? data.availableActions : [
      '00', '11', '12', '13',
      ...(data.enabled ? ['33', '44', '77'] : ['99']),
      ...(data.enabled && (data.bonusSeconds || 0) < 3600 ? ['22'] : []),
      ...(data.enabled && data.adminSupported && !data.adminActive ? ['55'] : [])
    ];
    if (pendingConfirmation && !availableActions.includes(pendingConfirmation)) resetConfirmations();
    updateAvailability();
    byId('sync-status').hidden = true;
    byId('sync-status').textContent = '';
  }

  async function loadState(force = false) {
    if (!authenticated) return false;
    if (loadingState) {
      if (!force) return false;
      await loadingState;
      return loadState(true);
    }
    const generation = authGeneration;
    const stateAtRequest = stateGeneration;
    const pending = (async () => {
      try {
        const data = await request('/api/state');
        if (authenticated && generation === authGeneration && stateAtRequest === stateGeneration) {
          render(data);
          if (syncFailureMessage && byId('notice').textContent === syncFailureMessage) notify('');
          syncFailureMessage = '';
          return true;
        }
      } catch (error) {
        if ((generation === authGeneration && stateAtRequest === stateGeneration)
            || error.expiredAuthGeneration === authGeneration) {
          byId('sync-status').textContent = '状态同步失败';
          byId('sync-status').hidden = false;
          notify(error.message, true);
          syncFailureMessage = error.message;
        }
      } finally {
        if (loadingState === pending) loadingState = null;
      }
      return false;
    })();
    loadingState = pending;
    return pending;
  }

  function actionStatus(code) {
    switch (code) {
      case '12': return '每日时长已更新';
      case '13': return '检查中';
      case '22': return '已加时';
      case '33': return state.locked ? '已锁定' : '处理中';
      case '44': return state.enabled ? '处理中' : '防护已关闭';
      case '55': return '请在电视确认';
      case '99': return state.enabled ? '防护已开启' : '处理中';
      case '77': return '已清零';
      default: return '已完成';
    }
  }

  async function perform(code, details = {}, form = null) {
    if (busy || !authenticated) return;
    const generation = authGeneration;
    activeForm = form;
    notify('');
    setBusy(true);
    try {
      if (code === '00') {
        await request('/api/logout', {});
        if (generation !== authGeneration) return;
        showLogin();
        notify('已退出登录');
        return;
      }
      // Mutations invalidate any older GET; changing the shared PIN also ends this session.
      stateGeneration += 1;
      await request('/api/action', { code, ...details });
      if (generation !== authGeneration) return;
      resetConfirmations();
      if (code === '11') {
        showLogin();
        notify('家长密码已更新，请重新登录');
        return;
      }
      if (form) {
        form.reset();
        delete form.dataset.dirty;
      }
      const refreshed = await loadState(true);
      if (refreshed && generation === authGeneration) notify(actionStatus(code));
    } catch (error) {
      if (currentFailure(error)) notify(error.message, true, form);
    } finally {
      setBusy(false);
    }
  }

  byId('login-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (busy) return;
    const body = { password: byId('login-password').value };
    if (!/^\d{6}$/.test(body.password)) {
      notify('家长密码须为 6 位数字', true);
      return;
    }
    setBusy(true);
    try {
      const session = await request('/api/login', body);
      if (!session.authenticated) notify(session.message || '家长密码不正确', true);
      else { notify(''); await showPanel(session); }
    } catch (error) { if (currentFailure(error)) notify(error.message, true); }
    finally { setBusy(false); }
  });

  navigation.forEach(button => button.addEventListener('click', () => {
    if (!authenticated || busy) return;
    showPage(button.dataset.page);
  }));

  actionButtons.forEach(button => button.addEventListener('click', () => {
    const code = button.dataset.code;
    if (button.disabled || button.hidden || busy || (!state && code !== '00')) return;
    activeForm = null;
    if (['44', '55'].includes(code) && pendingConfirmation !== code) {
      resetConfirmations();
      notify('');
      pendingConfirmation = code;
      const row = button.closest('[data-confirm-row]');
      row.classList.add('pending');
      button.textContent = code === '44' ? '确认关闭' : '确认开启';
      const cancel = row.querySelector('[data-cancel]');
      cancel.tabIndex = 0;
      cancel.removeAttribute('aria-hidden');
      return;
    }
    perform(code);
  }));
  document.querySelectorAll('[data-cancel]').forEach(button => button.addEventListener('click', () => {
    const code = button.dataset.cancel;
    resetConfirmations();
    document.querySelector('[data-code="' + code + '"]').focus({ preventScroll: true });
  }));

  forms.forEach(form => {
    form.addEventListener('input', () => { form.dataset.dirty = 'true'; });
    form.addEventListener('submit', event => {
      event.preventDefault();
      if (busy || !state) return;
      activeForm = form;
      const code = form.dataset.action;
      const details = Object.fromEntries(new FormData(form));
      if (code === '11') {
        if (![details.oldPin, details.newPin, details.confirmPin].every(pin => /^\d{6}$/.test(pin))) {
          notify('家长密码须为 6 位数字', true); return;
        }
        if (details.newPin !== details.confirmPin) { notify('两次输入的新密码不一致', true); return; }
        if (details.newPin === '000000') { notify('新密码不能使用出厂密码', true); return; }
      } else {
        details.minutes = Number(details.minutes);
      }
      perform(code, details, form);
    });
  });

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && pendingConfirmation && !busy) resetConfirmations();
  });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && authenticated && !busy) loadState();
  });
  setInterval(() => {
    if (authenticated && !busy && !document.hidden) loadState();
  }, 5000);
  (async () => {
    try {
      const session = await request('/api/session');
      if (session.authenticated) await showPanel(session);
      else showLogin();
    } catch (error) {
      showLogin();
      notify(error.message, true);
    }
  })();
})();
