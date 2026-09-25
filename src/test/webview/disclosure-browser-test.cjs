const assert = require('node:assert/strict');
const { test, before, after, beforeEach, afterEach } = require('node:test');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const RESOURCE_DIR = path.resolve(__dirname, '../../main/resources/webview');
const FIXTURE = readFileSync(path.resolve(__dirname, '../resources/webview/fixtures/disclosure-real.html'), 'utf8');
const ROLE = Object.freeze({ SIGNATORY: 'signatory', OBSERVER: 'observer', WITNESS: 'witness', DIVULGED: 'divulged' });
const SELECTOR = Object.freeze({
  MATRIX: '.disclosure-matrix', ROWS: '.disclosure-matrix tbody tr',
  ARCHIVED: '#show_archived', DETAILS: '#show_detailed_disclosure', SEARCH: '#search_input',
  SUMMARY: '.divulgence-strip', HEADERS: '.disclosure-matrix thead .party-name',
});
const VIEW_MESSAGE = 'set_view';
const DETAIL_MESSAGE = 'set_show_detailed_disclosure';
const DISCLOSURE_VIEW = 'disclosure';
const SUMMARY_TITLE = 'Parties marked as divulged (D)';
let browser;
let page;
let runtimeErrors;

function shell() {
  return readFileSync(path.join(RESOURCE_DIR, 'webview.html'), 'utf8')
    .replace('$webviewSrc', 'data:application/javascript;base64,' + readFileSync(path.join(RESOURCE_DIR, 'webview.js')).toString('base64'))
    .replace('$webviewCss', 'data:text/css;base64,' + readFileSync(path.join(RESOURCE_DIR, 'webview.css')).toString('base64'))
    .replace('$webviewTheme', 'ide-light');
}

async function mount(preferences = {}) {
  await page.goto('about:blank');
  await page.setContent(shell());
  await page.waitForFunction(() => typeof setHtmlContent === 'function');
  await page.evaluate(({ html, command, selected, preferences }) => {
    window.hostMessages = [];
    window.jbBridge = { postMessage: raw => window.hostMessages.push(JSON.parse(raw)) };
    setHtmlContent(html);
    window.dispatchEvent(new MessageEvent('message', { data: {
      command, value: { selected, showArchived: true, showDetailedDisclosure: true, ...preferences },
    } }));
  }, { html: FIXTURE, command: VIEW_MESSAGE, selected: DISCLOSURE_VIEW, preferences });
}

before(async () => {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
});
after(async () => { await browser?.close(); });
beforeEach(async () => {
  runtimeErrors = [];
  page = await browser.newPage();
  page.on('pageerror', error => runtimeErrors.push(error.message));
  await mount();
});
afterEach(async () => { await page?.close(); assert.deepEqual(runtimeErrors, []); });

test('real server columns and every S/O/W/D or hidden cell survive the complete rendering path', async () => {
  const result = await page.evaluate(({ fixture, selectors }) => {
    const raw = new DOMParser().parseFromString(fixture, 'text/html');
    const failures = [];
    const sourceParties = new Set();
    const roles = new Set();
    let count = 0;
    for (const table of raw.querySelectorAll('table')) {
      const headers = [...table.querySelectorAll('tr:first-child th')];
      for (const row of table.querySelectorAll('tr')) {
        const cells = [...row.querySelectorAll('td')];
        if (!cells.length) continue;
        const id = cells[0].textContent.trim();
        const rendered = [...document.querySelectorAll(selectors.ROWS)].find(r => r.querySelector('.mono')?.textContent === id);
        const names = [...document.querySelectorAll(selectors.HEADERS)].map(h => h.textContent);
        cells.forEach((cell, index) => {
          if (!cell.classList.contains('disclosure')) return;
          count++;
          const party = headers[index].textContent.trim();
          sourceParties.add(party);
          const expected = cell.querySelector('.tooltiptext')?.textContent.toLowerCase() || '-';
          roles.add(expected);
          const column = names.indexOf(party);
          const actual = column >= 0 && rendered ? rendered.children[column + 1].textContent : null;
          if (actual !== expected) failures.push({ id, party, expected, actual });
        });
      }
    }
    return { count, failures, roles: [...roles].sort(),
      expectedNames: [...sourceParties].sort(),
      actualNames: [...document.querySelectorAll(selectors.HEADERS)].map(h => h.textContent).sort(),
      modelNames: state.model.parties.slice().sort() };
  }, { fixture: FIXTURE, selectors: SELECTOR });
  assert.ok(result.count > 0);
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.actualNames, result.expectedNames);
  assert.deepEqual(result.modelNames, result.expectedNames);
  assert.deepEqual(result.roles, ['-', ...Object.values(ROLE)].sort());
  for (const name of ['Alice Smith', '123', '_Trader']) {
    assert.ok(result.actualNames.some(party => party.startsWith(name + '-')), name);
  }
});

test('role search matches visible roles and archived filtering still applies', async () => {
  for (const role of Object.values(ROLE)) {
    await page.locator(SELECTOR.SEARCH).fill(role);
    const rows = page.locator(SELECTOR.ROWS);
    assert.ok(await rows.count() > 0, role);
    assert.ok(await page.locator('.matrix-' + role).count() > 0, role);
  }
  await page.locator(SELECTOR.ARCHIVED).uncheck();
  assert.equal(await page.locator(SELECTOR.ROWS).count(), 0, 'The only D contract has been archived');
  assert.equal(await page.locator(SELECTOR.SUMMARY).count(), 0);
});

test('neutral mode changes labels and colors, preserving cells, roles and preference messages', async () => {
  const beforeText = await page.locator(SELECTOR.HEADERS).allTextContents();
  const visibleCount = await page.locator(SELECTOR.MATRIX + ' .role-badge').count();
  const hiddenCount = await page.locator('.matrix-hidden').count();
  await page.locator(SELECTOR.DETAILS).uncheck();
  assert.equal(await page.locator('.matrix-visible').count(), visibleCount);
  assert.equal(await page.locator('.matrix-hidden').count(), hiddenCount);
  assert.deepEqual(await page.locator(SELECTOR.HEADERS).allTextContents(), beforeText);
  const colors = await page.locator('.matrix-visible').evaluateAll(cells => [...new Set(cells.map(c => getComputedStyle(c).backgroundColor))]);
  assert.equal(colors.length, 1);
  for (const text of await page.locator('.matrix-visible').allTextContents()) assert.equal(text, '✓');
  assert.equal(await page.locator('.matrix-signatory').count(), 0);
  const preference = await page.evaluate(command => window.hostMessages.findLast(m => m.command === command), DETAIL_MESSAGE);
  assert.equal(preference.value, false);
  await mount({ showDetailedDisclosure: preference.value });
  assert.equal(await page.locator('.matrix-visible').count(), visibleCount);
  await page.locator(SELECTOR.SEARCH).fill(ROLE.SIGNATORY);
  assert.ok(await page.locator(SELECTOR.ROWS).count() > 0);
  await page.locator(SELECTOR.DETAILS).check();
  assert.ok(await page.locator('.matrix-signatory').count() > 0);
});

test('D summary describes only filtered contracts and keeps W and explicit disclosure distinct', async () => {
  assert.match(await page.locator(SELECTOR.SUMMARY).innerText(), new RegExp(SUMMARY_TITLE.replace(/[()]/g, '\\$&')));
  const explanation = await page.locator('.disclosure-help').innerText();
  assert.match(explanation, /currently filtered contracts/);
  assert.match(explanation, /immediate divulgence/);
  assert.match(explanation, /explicit disclosure/);
  await page.locator(SELECTOR.SEARCH).fill(ROLE.WITNESS);
  assert.ok(await page.locator(SELECTOR.ROWS).count() > 0);
  assert.equal(await page.locator(SELECTOR.SUMMARY).count(), 0);
  await page.locator(SELECTOR.SEARCH).fill(ROLE.DIVULGED);
  assert.ok(await page.locator(SELECTOR.SUMMARY).count() > 0);
});

test('party search preserves opaque names and does not invent visibility from a hidden column', async () => {
  for (const name of ['Alice Smith', '123', '_Trader', 'Nobody']) {
    await page.locator(SELECTOR.SEARCH).fill(name);
    assert.ok(await page.locator(SELECTOR.ROWS).count() > 0, name);
    assert.ok((await page.locator(SELECTOR.HEADERS).allTextContents()).some(p => p.startsWith(name + '-')));
  }
  assert.ok(await page.locator('.matrix-hidden').count() > 0);
});

test('new results clear stale notes and server messages are distinguishable from waiting', async () => {
  await page.evaluate(() => { setHtmlContent(''); addConsoleNote('The script is no longer available.'); });
  assert.match(await page.locator('#progress_status').innerText(), /Server message/);
  await page.evaluate(html => setHtmlContent(html), FIXTURE);
  assert.equal(await page.evaluate(() => state.notes.length), 0);
  assert.equal(await page.locator('#progress_status').innerText(), 'Result available');
});

test('opening another script resets transaction expansion and selection state', async () => {
  await page.evaluate(() => selectView('txTree'));
  await page.locator('.tx-event-summary').first().click();
  await page.waitForFunction(() => state.expandedEvents.size > 0);
  await page.getByRole('button', { name: 'Transactions only', exact: true }).click();
  await page.waitForFunction(() => state.collapsedTransactions.size > 0);
  await page.evaluate(() => setHtmlContent(''));
  assert.deepEqual(await page.evaluate(() => ({
    expanded: state.expandedEvents.size,
    collapsed: state.collapsedTransactions.size,
    branches: state.collapsedBranches.size,
    contract: state.selectedContractId,
    transaction: state.selectedTransactionId,
  })), { expanded: 0, collapsed: 0, branches: 0, contract: null, transaction: null });
  await page.evaluate(html => setHtmlContent(html), FIXTURE);
  assert.equal(await page.locator('.tx-event-details[open]').count(), 0);
  assert.equal(await page.locator('.tx-outline-group:not([open])').count(), 0);
});
