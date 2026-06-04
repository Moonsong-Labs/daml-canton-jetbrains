// Adapted from https://github.com/digital-asset/daml/blob/main/sdk/compiler/daml-extension/src/webview.js
// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
//
// JCEF host bridges window.jbBridge to the JetBrains plugin. This file replaces
// VSCode's acquireVsCodeApi() with a thin shim that posts to jbBridge.

const vscode = {
  postMessage: function (msg) {
    if (typeof window.jbBridge !== 'undefined' && typeof window.jbBridge.postMessage === 'function') {
      try {
        window.jbBridge.postMessage(JSON.stringify(msg));
        return;
      } catch (e) {
        console.error('jbBridge.postMessage failed', e);
      }
    }
    console.log('msg (no bridge):', msg);
  }
};

const VIEWS = [
  { id: 'overview', label: 'Overview' },
  { id: 'contracts', label: 'Contracts' },
  { id: 'txTree', label: 'Tx Tree' },
  { id: 'disclosure', label: 'Disclosure' },
  { id: 'console', label: 'Console' },
  { id: 'raw', label: 'Raw' }
];

const CONSOLE_SELECTOR = [
  '[data-console]',
  '[data-note]',
  '.note',
  '.script-note',
  '.scriptNote',
  '.debug',
  '.trace',
  '.log',
  '.logs',
  '.console',
  '.message',
  '.output',
  'pre'
].join(',');

const state = {
  selectedView: 'overview',
  showArchived: false,
  showDetailedDisclosure: false,
  search: '',
  originalHtml: '',
  sanitizedHtml: '',
  model: emptyModel(),
  notes: [],
  progressMs: -1,
  selectedContractId: null,
  selectedTransactionId: null,
  parserWarnings: []
};

function emptyModel() {
  return {
    contracts: [],
    templates: [],
    parties: [],
    transactions: [],
    consoleEntries: [],
    rawTransactionHtml: '',
    warnings: []
  };
}

function normalizeView(value) {
  if (value === 'table') return 'contracts';
  if (value === 'transaction') return 'txTree';
  return VIEWS.some(v => v.id === value) ? value : 'overview';
}

function setHtmlContent(html) {
  document.body.classList.remove('empty');
  state.progressMs = -1;
  state.originalHtml = String(html == null ? '' : html);
  const fragment = sanitizeToFragment(state.originalHtml);
  state.sanitizedHtml = fragmentToHtml(fragment.cloneNode(true));
  state.model = buildModelFromFragment(fragment.cloneNode(true));
  state.parserWarnings = state.model.warnings.slice();
  if (!state.selectedContractId && state.model.contracts.length > 0) {
    state.selectedContractId = state.model.contracts[0].id;
  }
  if (!state.selectedTransactionId && state.model.transactions.length > 0) {
    state.selectedTransactionId = state.model.transactions[0].id;
  }
  render();
}

function setProgress(millisecondsPassed) {
  const ms = Number(millisecondsPassed);
  state.progressMs = Number.isFinite(ms) && ms >= 0 ? ms : -1;
  renderToolbarState();
}

function setIdeTheme(theme) {
  const dark = theme === 'ide-dark' || theme === 'dark';
  document.body.classList.toggle('ide-dark', dark);
  document.body.classList.toggle('ide-light', !dark);
}

function addConsoleNote(html) {
  const note = consoleEntryFromHtml(html, 'server-note');
  if (!note) return;
  note.id = 'note-' + (state.notes.length + 1);
  state.notes.push(note);
  document.body.classList.remove('hide_note');
  render();
}

// Strips <script> tags, on* event handlers, and javascript:/data: URIs from server-pushed
// HTML before insertion. Defense in depth alongside the page-level CSP: both must agree
// to allow execution.
function sanitizeToFragment(html) {
  const tmpl = document.createElement('template');
  tmpl.innerHTML = String(html == null ? '' : html);
  const rawSource = tmpl.content.querySelector('body') || tmpl.content;
  const source = document.createDocumentFragment();
  while (rawSource.firstChild) source.appendChild(rawSource.firstChild);
  const walker = document.createTreeWalker(source, NodeFilter.SHOW_ELEMENT);
  const drop = [];
  while (walker.nextNode()) {
    const el = walker.currentNode;
    const tag = el.tagName.toLowerCase();
    if (tag === 'script' || tag === 'iframe' || tag === 'object' || tag === 'embed' || tag === 'meta' || tag === 'link' || tag === 'style') {
      drop.push(el);
      continue;
    }
    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase();
      const value = attr.value;
      if (name.startsWith('on')) {
        el.removeAttribute(attr.name);
        continue;
      }
      if ((name === 'href' || name === 'src' || name === 'xlink:href') && /^(javascript|data|vbscript):/i.test(value.trim())) {
        el.removeAttribute(attr.name);
      }
    }
  }
  drop.forEach(el => el.remove());
  removeEmbeddedToolbar(source);
  pruneEmptyText(source);
  return source;
}

function removeEmbeddedToolbar(root) {
  const embeddedControlSelector = ['#show_archived', '#show_detailed_disclosure', 'button'].join(',');
  for (const el of Array.from(root.querySelectorAll(embeddedControlSelector))) {
    const text = (el.textContent || '').trim();
    const isKnownButton = el.tagName.toLowerCase() === 'button' &&
      /^(show table view|show transaction view|toggle table\/transaction view)$/i.test(text);
    const isKnownInput = el.id === 'show_archived' || el.id === 'show_detailed_disclosure';
    if (!isKnownButton && !isKnownInput) continue;
    const label = el.closest('label');
    if (isKnownInput && !label) {
      removeAdjacentText(el, el.id === 'show_archived' ? /show archived/i : /show detailed disclosure/i);
    }
    (label || el).remove();
  }
  for (const el of Array.from(root.querySelectorAll('br'))) {
    const previous = el.previousSibling && String(el.previousSibling.textContent || '').trim();
    const next = el.nextSibling && String(el.nextSibling.textContent || '').trim();
    if (!previous && !next) el.remove();
  }
}

function removeAdjacentText(element, pattern) {
  for (const siblingKey of ['previousSibling', 'nextSibling']) {
    const sibling = element[siblingKey];
    if (sibling && sibling.nodeType === Node.TEXT_NODE && pattern.test(sibling.textContent || '')) {
      sibling.remove();
    }
  }
}

function pruneEmptyText(root) {
  for (const node of Array.from(root.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE && !node.textContent.trim()) node.remove();
  }
}

function fragmentToHtml(fragment) {
  const div = document.createElement('div');
  div.appendChild(fragment);
  return div.innerHTML;
}

function buildModelFromFragment(fragment) {
  const root = document.createElement('div');
  root.appendChild(fragment);
  const warnings = [];
  const contracts = parseContracts(root);
  const transactions = parseTransactions(root, contracts);
  const consoleEntries = extractConsoleEntries(root);
  const parties = Array.from(new Set(contracts.flatMap(contract => contract.parties.map(p => p.name)))).sort();
  const templates = Array.from(new Set(contracts.map(contract => contract.template))).sort();
  const rawTransaction = transactionSections(root)[0];

  if (contracts.length === 0 && root.textContent.trim()) {
    warnings.push('Explorer could not confidently extract contracts from this result. Raw remains available.');
  }
  if (transactions.length === 0 && root.textContent.trim()) {
    warnings.push('Explorer could not confidently extract a transaction tree. Raw remains available.');
  }

  return {
    contracts,
    templates,
    parties,
    transactions,
    consoleEntries,
    rawTransactionHtml: rawTransaction ? rawTransaction.outerHTML : '',
    warnings
  };
}

function parseContracts(root) {
  const contracts = [];
  const headings = Array.from(root.querySelectorAll('h1, h2, h3'));
  for (const heading of headings) {
    const table = nextTableAfter(heading);
    if (!table || table.closest('.transaction')) continue;
    contracts.push(...contractsFromHeadingAndTable(heading, table, contracts.length + 1));
  }

  if (contracts.length === 0) {
    for (const table of Array.from(root.querySelectorAll('.table table, table'))) {
      if (table.closest('.transaction')) continue;
      contracts.push(...contractsFromHeadingAndTable(null, table, contracts.length + 1));
    }
  }
  return contracts;
}

function nextTableAfter(element) {
  let cursor = element.nextElementSibling;
  while (cursor) {
    if (cursor.matches && cursor.matches('table')) return cursor;
    const nested = cursor.querySelector && cursor.querySelector('table');
    if (nested) return nested;
    if (cursor.matches && cursor.matches('h1,h2,h3')) return null;
    cursor = cursor.nextElementSibling;
  }
  return null;
}

function contractsFromHeadingAndTable(heading, table, startIndex) {
  const headerText = heading ? cleanText(heading.textContent) : 'Contract ' + startIndex;
  const rows = Array.from(table.querySelectorAll('tr'));
  if (rows.length === 0) return [];
  const headerCells = Array.from(rows[0].querySelectorAll('th,td'));
  const headers = headerCells.map(cell => cleanText(cell.textContent));
  const dataRows = rows.slice(1).filter(row => row.querySelectorAll('td,th').length > 0);
  return dataRows.map((dataRow, offset) =>
    contractFromHeadingTableRow(heading, table, headerText, headerCells, headers, dataRow, startIndex + offset)
  ).filter(Boolean);
}

function contractFromHeadingTableRow(heading, table, headerText, headerCells, headers, dataRow, index) {
  const cells = Array.from(dataRow.querySelectorAll('td,th'));
  const values = cells.map(cell => cleanText(cell.textContent));
  const idIndex = headerIndex(headers, 'id');
  const statusIndex = headerIndex(headers, 'status');
  const id = valueAt(values, idIndex) || firstContractId(headerText) || ('contract-' + index);
  const archived = dataRow.classList.contains('archived') ||
    table.classList.contains('archived') ||
    valueAt(values, statusIndex).toLowerCase() === 'archived';
  const templateInfo = parseTemplateTitle(headerText);
  const fields = [];
  const disclosures = [];

  headers.forEach((header, i) => {
    if (i === idIndex || i === statusIndex) return;
    const cell = cells[i];
    const isDisclosure = cell && (cell.classList.contains('disclosure') || cell.classList.contains('disclosed'));
    const headerIsParty = isDisclosureHeader(header, headerCells[i]);
    if (isDisclosure || headerIsParty) {
      // The disclosure cell renders a one-letter glyph plus a tooltip word
      // (e.g. <span>D</span><span class="tooltiptext">Divulged</span>); textContent
      // concatenates them ("DDivulged"). Prefer the tooltip word alone.
      const tip = cell && cell.querySelector ? cell.querySelector('.tooltiptext') : null;
      const detailText = cleanText(tip ? tip.textContent : values[i]);
      disclosures.push({
        party: header || ('party-' + (disclosures.length + 1)),
        visible: isDisclosureVisible(cell, values[i]),
        detail: detailText || (isDisclosureVisible(cell, values[i]) ? 'visible' : '')
      });
    } else if (header) {
      fields.push({ name: header, value: values[i] || '' });
    }
  });

  const parties = partiesForContract(disclosures);
  return {
    id,
    displayId: shortId(id),
    template: templateInfo.template,
    templateShort: templateInfo.shortName,
    packageId: templateInfo.packageId,
    status: archived ? 'archived' : (valueAt(values, statusIndex) || 'active'),
    archived,
    fields,
    parties,
    disclosures,
    transactionId: transactionIdFromContractId(id),
    sourceHref: firstRevealHref(heading) || firstRevealHref(table),
    rawHtml: heading ? heading.outerHTML + table.outerHTML : table.outerHTML
  };
}

function parseTemplateTitle(text) {
  const cleaned = cleanText(text);
  const at = cleaned.indexOf('@');
  const withoutPackage = at === -1 ? cleaned : cleaned.slice(0, at);
  const packageId = at === -1 ? '' : cleaned.slice(at + 1);
  const template = withoutPackage || cleaned || 'Unknown template';
  const shortName = template.split(':').pop().split('.').pop() || template;
  return { template, shortName, packageId };
}

function partiesForContract(disclosures) {
  const names = new Map();
  for (const disclosure of disclosures) {
    if (!disclosure.visible) continue;
    addParty(names, disclosure.party, roleList(disclosure.detail));
  }
  return Array.from(names.values());
}

function disclosurePartiesForContracts(contracts) {
  return Array.from(new Set((contracts || []).flatMap(contract =>
    (contract.disclosures || [])
      .map(disclosure => cleanPartyValue(disclosure.party))
      .filter(party => party && looksLikePartyValue(party))
  ))).sort();
}

function addParty(map, name, roles) {
  const party = cleanPartyValue(name);
  if (!party || !looksLikePartyValue(party)) return;
  const existing = map.get(party) || { name: party, roles: [] };
  for (const role of roles || []) {
    if (role && !existing.roles.includes(role)) existing.roles.push(role);
  }
  map.set(party, existing);
}

function parseTransactions(root, contracts) {
  const contractsById = new Map(contracts.map(contract => [contract.id, contract]));
  const txMap = transactionMapFromContracts(contracts);

  transactionSections(root).forEach((section, index) => {
    const damlTransactions = transactionsFromDamlTransactionCode(section, contractsById);
    if (damlTransactions.length) {
      for (const tx of damlTransactions) mergeTransactionIntoMap(txMap, Object.assign({}, tx, { rawHtml: section.outerHTML }));
      return;
    }

    const id = transactionIdFromText(section.textContent) || String(index + 1);
    if (!txMap.has(id)) txMap.set(id, { id, label: 'Transaction #' + id, events: [] });
    const tx = txMap.get(id);
    tx.rawHtml = section.outerHTML;
    tx.rawText = cleanText(textWithBreaks(section));

    const parsedEvents = transactionEventsFromElement(section, id, contractsById);
    if (parsedEvents.length) {
      tx.events = mergeTransactionEvents(parsedEvents, tx.events);
    } else if (tx.events.length === 0 && tx.rawText) {
      tx.events.push(rawTransactionEvent(id, tx.rawText));
    }
  });

  return Array.from(txMap.values()).sort((a, b) => transactionSortKey(a.id) - transactionSortKey(b.id));
}

function mergeTransactionIntoMap(map, tx) {
  const existing = map.get(tx.id);
  if (!existing) {
    map.set(tx.id, Object.assign({}, tx, { events: (tx.events || []).slice() }));
    return;
  }
  existing.label = tx.label || existing.label;
  existing.rawHtml = tx.rawHtml || existing.rawHtml;
  existing.rawText = tx.rawText || existing.rawText;
  existing.events = mergeTransactionEvents(tx.events || [], existing.events || []);
}

function transactionSections(root) {
  const selector = [
    '.transaction',
    '.transaction-tree',
    '.tx-tree',
    '[data-transaction]',
    '[data-transaction-tree]',
    '[data-tx-tree]'
  ].join(',');
  const sections = Array.from(root.querySelectorAll(selector));
  return sections.filter(section => !sections.some(other => other !== section && other.contains(section)));
}

function transactionMapFromContracts(contracts) {
  const txMap = new Map();
  for (const contract of contracts) {
    const id = contract.transactionId || 'unknown';
    if (!txMap.has(id)) txMap.set(id, { id, label: id === 'unknown' ? 'Unknown transaction' : 'Transaction #' + id, events: [] });
    txMap.get(id).events.push(transactionEventFromContract(contract));
  }
  return txMap;
}

function transactionEventFromContract(contract) {
  const kind = contract.archived ? 'Archived/Result' : 'Create';
  const parties = contract.parties || [];
  return {
    id: 'contract-' + contract.id,
    kind,
    label: contract.templateShort,
    contractId: contract.id,
    template: contract.template,
    actors: primaryActorsForKind(kind, parties, []),
    parties,
    fields: contract.fields,
    source: 'contract'
  };
}

function transactionEventsFromElement(section, transactionId, contractsById) {
  const candidates = directTransactionEventElements(section);
  const events = candidates
    .map((element, index) => transactionEventFromElement(element, transactionId, index + 1, contractsById))
    .filter(Boolean);
  if (events.length) return events;
  return transactionEventsFromText(textWithBreaks(section), transactionId, contractsById);
}

function directTransactionEventElements(section) {
  const selector = [
    '.event',
    '[data-event]',
    '[data-transaction-event]',
    '[data-node]',
    'details',
    'li'
  ].join(',');
  const candidates = Array.from(section.querySelectorAll(selector))
    .filter(isTransactionEventElement);
  return candidates.filter(element =>
    !candidates.some(other => other !== element && other.contains(element) && isTransactionEventElement(other))
  );
}

function isTransactionEventElement(element) {
  if (!element) return false;
  const text = transactionTextForElement(element);
  return looksLikeTransactionEventText(text);
}

function transactionEventFromElement(element, transactionId, index, contractsById) {
  const text = transactionTextForElement(element);
  const event = inferTransactionEvent(text, transactionId, index, contractsById);
  if (!event) return null;
  const children = Array.from(element.children || [])
    .flatMap(child => {
      if (child.tagName && child.tagName.toLowerCase() === 'summary') return [];
      if (isTransactionEventElement(child)) return [child];
      return directTransactionEventElements(child);
    })
    .map((child, childIndex) => transactionEventFromElement(child, transactionId, String(index) + '-' + (childIndex + 1), contractsById))
    .filter(Boolean);
  if (children.length) event.children = children;
  return event;
}

function transactionTextForElement(element) {
  if (!element) return '';
  if (element.tagName && element.tagName.toLowerCase() === 'details') {
    const summary = element.querySelector(':scope > summary');
    if (summary && looksLikeTransactionEventText(summary.textContent)) return cleanText(summary.textContent);
  }
  const clone = element.cloneNode(true);
  for (const nested of Array.from(clone.querySelectorAll('.event,[data-event],[data-transaction-event],[data-node],details,li'))) {
    if (nested !== clone) nested.remove();
  }
  return cleanText(clone.textContent || element.textContent);
}

function textWithBreaks(node) {
  if (!node) return '';
  if (node.nodeType === Node.TEXT_NODE) return node.textContent || '';
  if (node.nodeType !== Node.ELEMENT_NODE && node.nodeType !== Node.DOCUMENT_FRAGMENT_NODE) return '';
  if (node.nodeType === Node.ELEMENT_NODE && node.tagName && node.tagName.toLowerCase() === 'br') return '\n';
  let text = '';
  for (const child of Array.from(node.childNodes || [])) text += textWithBreaks(child);
  return text;
}

function transactionsFromDamlTransactionCode(section, contractsById) {
  const text = textWithBreaks(section);
  if (!/\bTX\s+\d+\b/.test(text) || !/Transactions:/i.test(text)) return [];
  return damlTransactionsFromText(text, contractsById);
}

function damlTransactionsFromText(text, contractsById) {
  const contractMap = contractsById || new Map();
  const transactions = [];
  let currentTx = null;
  let pendingNodeId = '';
  const stack = [];

  for (const rawLine of String(text || '').replace(/\r/g, '\n').split('\n')) {
    const line = rawLine.replace(/\u00a0/g, ' ');
    const trimmed = cleanText(line);
    if (!trimmed || /^Transactions:?$/i.test(trimmed)) continue;

    const txMatch = trimmed.match(/^TX\s+(\d+)\b(.*)$/i);
    if (txMatch) {
      currentTx = {
        id: txMatch[1],
        label: 'Transaction #' + txMatch[1],
        events: [],
        rawText: trimmed
      };
      transactions.push(currentTx);
      pendingNodeId = '';
      stack.length = 0;
      continue;
    }
    if (!currentTx) continue;

    const idOnly = trimmed.match(/^#\d+:\d+$/);
    if (idOnly) {
      pendingNodeId = idOnly[0];
      continue;
    }

    if (!looksLikeTransactionEventText(trimmed)) continue;
    const indent = leadingWhitespace(line);
    const event = inferDamlTransactionEvent(trimmed, currentTx.id, currentTx.events.length + 1, pendingNodeId, contractMap);
    if (!event) continue;

    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    const parent = stack.length ? stack[stack.length - 1].event : null;
    if (parent) {
      parent.children = parent.children || [];
      parent.children.push(event);
    } else {
      currentTx.events.push(event);
    }
    stack.push({ indent, event });
    pendingNodeId = '';
  }

  return transactions.filter(tx => tx.events.length > 0);
}

function inferDamlTransactionEvent(text, transactionId, index, nodeId, contractsById) {
  const eventText = cleanText(text);
  const kind = inferTransactionKind(eventText);
  const targetContractId = kind === 'Create'
    ? (nodeId || contractIdFromText(eventText))
    : (contractIdAfterAction(eventText) || contractIdFromText(eventText) || nodeId);
  const contract = targetContractId ? contractsById.get(targetContractId) : null;
  const template = contract ? contract.template : templateFromTransactionText(eventText, kind);
  const label = transactionEventLabel(kind, eventText, contract, targetContractId);
  return {
    id: 'tx-' + transactionId + '-' + index,
    kind,
    label,
    contractId: targetContractId || '',
    eventId: nodeId || '',
    template: template || '',
    actors: partiesFromTransactionText(eventText),
    parties: contract ? contract.parties : partiesFromTransactionText(eventText),
    fields: contract ? contract.fields : fieldsFromTransactionText(eventText),
    rawText: eventText,
    source: 'transaction'
  };
}

function transactionEventsFromText(text, transactionId, contractsById) {
  return transactionEventLines(text)
    .map((line, index) => inferTransactionEvent(line, transactionId, index + 1, contractsById || new Map()))
    .filter(Boolean);
}

function transactionEventLines(text) {
  const raw = String(text || '').replace(/\r/g, '\n');
  const lines = raw.split(/\n+/).map(cleanText).filter(Boolean);
  const meaningful = lines.filter(looksLikeTransactionEventText);
  if (meaningful.length > 0) return meaningful;
  return cleanText(raw)
    .split(/(?=\b(?:create|created|exercise|exercised|archive|archived|fetch|fetched|lookup|rollback)\b)/i)
    .map(cleanText)
    .filter(looksLikeTransactionEventText);
}

function inferTransactionEvent(text, transactionId, index, contractsById) {
  const eventText = cleanText(text);
  if (!looksLikeTransactionEventText(eventText)) return null;
  const contractId = contractIdFromText(eventText);
  const contractMap = contractsById || new Map();
  const contract = contractId ? contractMap.get(contractId) : null;
  const kind = inferTransactionKind(eventText);
  const template = contract ? contract.template : templateFromTransactionText(eventText, kind);
  const label = transactionEventLabel(kind, eventText, contract, contractId);
  return {
    id: 'tx-' + transactionId + '-' + index,
    kind,
    label,
    contractId: contractId || '',
    template: template || '',
    actors: partiesFromTransactionText(eventText),
    parties: contract ? contract.parties : [],
    fields: contract ? contract.fields : [],
    rawText: eventText,
    source: 'transaction'
  };
}

function looksLikeTransactionEventText(text) {
  const value = cleanText(text).toLowerCase();
  if (!value || value.length > 1600) return false;
  return /\b(create|creates|created|exercise|exercises|exercised|archive|archives|archived|fetch|fetches|fetched|lookup|lookups|rollback)\b/.test(value) ||
    /#\d+:\d+/.test(value) && /\b(contract|node|event|choice)\b/.test(value);
}

function inferTransactionKind(text) {
  const value = String(text || '').toLowerCase();
  if (/\b(archive|archives|archived)\b/.test(value)) return 'Archive';
  if (/\b(exercise|exercises|exercised)\b/.test(value)) return 'Exercise';
  if (/\b(fetch|fetches|fetched)\b/.test(value)) return 'Fetch';
  if (/\b(lookup|lookups)\b/.test(value)) return 'Lookup';
  if (/\brollback\b/.test(value)) return 'Rollback';
  if (/\b(create|creates|created)\b/.test(value)) return 'Create';
  return 'Event';
}

function contractIdFromText(text) {
  const match = String(text || '').match(/#\d+:\d+/);
  return match ? match[0] : '';
}

function transactionIdFromText(text) {
  const explicit = String(text || '').match(/\btransaction\s*#?\s*(\d+)\b/i);
  if (explicit) return explicit[1];
  const contractId = contractIdFromText(text);
  return contractId ? transactionIdFromContractId(contractId) : '';
}

function templateFromTransactionText(text, kind) {
  const value = cleanText(text);
  const withoutContract = value.replace(/#\d+:\d+.*/, '').trim();
  const create = withoutContract.match(/\b(?:create|creates|created)\s+(.+)$/i);
  if (create) return cleanTransactionLabel(create[1]);
  const exercise = withoutContract.match(/\b(?:exercise|exercises|exercised)\s+(.+?)(?:\s+on\b|$)/i);
  if (exercise && !/^archive$/i.test(exercise[1])) return cleanTransactionLabel(exercise[1]);
  const fetch = value.match(/\b(?:fetch|fetches|fetched)\s+#\d+:\d+\s+\(([^)]+)\)/i);
  if (fetch) return cleanTransactionLabel(fetch[1]);
  const onTemplate = value.match(/\bon\s+#\d+:\d+\s+\(([^)]+)\)/i) || withoutContract.match(/\bon\s+(.+)$/i);
  if (onTemplate) return cleanTransactionLabel(onTemplate[1]);
  return kind === 'Archive' ? 'Archive' : '';
}

function transactionLabelFromText(text, kind, contractId) {
  const template = templateFromTransactionText(text, kind);
  if (template && template !== 'Archive') return template.split(':').pop().split('.').pop();
  if (kind === 'Archive' && contractId) return 'Archive ' + contractId;
  return shortValue(cleanText(text));
}

function transactionEventLabel(kind, text, contract, contractId) {
  if (kind === 'Exercise') return transactionLabelFromText(text, kind, contractId);
  if (kind === 'Create' && contract) return contract.templateShort;
  if ((kind === 'Fetch' || kind === 'Archive') && contract) return contract.templateShort;
  return transactionLabelFromText(text, kind, contractId);
}

function cleanTransactionLabel(text) {
  return cleanText(text)
    .replace(/\b(children|child events?)\b.*$/i, '')
    .replace(/\bwith\b.*$/i, '')
    .trim();
}

function contractIdAfterAction(text) {
  const value = String(text || '');
  const on = value.match(/\b(?:on|fetches?|fetched|archives?|archived)\s+(#\d+:\d+)/i);
  if (on) return on[1];
  return contractIdFromText(value);
}

function partiesFromTransactionText(text) {
  const parties = [];
  const seen = new Set();
  for (const match of String(text || '').matchAll(/'([^']+)'/g)) {
    const name = cleanPartyValue(match[1]);
    if (!looksLikePartyValue(name) || seen.has(name)) continue;
    seen.add(name);
    parties.push({ name, roles: ['controller'] });
  }
  return parties;
}

function fieldsFromTransactionText(text) {
  const withIndex = String(text || '').toLowerCase().indexOf(' with ');
  if (withIndex < 0) return [];
  return String(text).slice(withIndex + 6)
    .split(';')
    .map(part => part.trim())
    .map(part => {
      const match = part.match(/^([A-Za-z_][A-Za-z0-9_']*)\s*=\s*(.+)$/);
      return match ? { name: match[1], value: cleanText(match[2]) } : null;
    })
    .filter(Boolean);
}

function leadingWhitespace(text) {
  const match = String(text || '').match(/^\s*/);
  return match ? match[0].length : 0;
}

function rawTransactionEvent(transactionId, text) {
  return {
    id: 'tx-' + transactionId + '-raw',
    kind: 'Raw',
    label: shortValue(text),
    rawText: text,
    source: 'raw'
  };
}

function mergeTransactionEvents(primary, fallback) {
  const merged = [];
  const seen = new Set();
  for (const event of (primary || []).concat(fallback || [])) {
    if (!event) continue;
    const key = transactionEventKey(event);
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(event);
  }
  return merged;
}

function transactionEventKey(event) {
  const kind = String(event.kind || '').toLowerCase();
  if (event.contractId) return kind + '|contract|' + event.contractId;
  if (event.label) return kind + '|label|' + String(event.label).toLowerCase();
  return kind + '|raw|' + String(event.rawText || '').toLowerCase().slice(0, 120);
}

function render() {
  renderTabs();
  renderToolbarState();
  renderCurrentView();
}

function renderTabs() {
  const tabs = document.getElementById('view_tabs');
  tabs.replaceChildren();
  const consoleCount = allConsoleEntries().length;
  for (const view of VIEWS) {
    const button = el('button', {
      className: 'tab' + (state.selectedView === view.id ? ' selected' : ''),
      type: 'button',
      onclick: () => selectView(view.id)
    }, [
      document.createTextNode(view.label),
      view.id === 'console' && consoleCount > 0 ? el('span', { className: 'tab-count' }, String(consoleCount)) : null
    ]);
    tabs.appendChild(button);
  }
}

function renderToolbarState() {
  const archived = document.getElementById('show_archived');
  const disclosure = document.getElementById('show_detailed_disclosure');
  const search = document.getElementById('search_input');
  const progress = document.getElementById('progress_status');
  if (archived) archived.checked = state.showArchived;
  if (disclosure) disclosure.checked = state.showDetailedDisclosure;
  if (search && search.value !== state.search) search.value = state.search;
  if (search) search.placeholder = searchPlaceholder();
  if (progress) {
    progress.hidden = state.progressMs < 0;
    progress.textContent = state.progressMs >= 0 ? 'Running ' + formatDuration(state.progressMs) : '';
  }
  document.body.classList.toggle('hide_archived', !state.showArchived);
  document.body.classList.toggle('hidden_disclosure', !state.showDetailedDisclosure);
}

function renderCurrentView() {
  const root = document.getElementById('view_root');
  root.replaceChildren();
  if (!state.originalHtml && state.notes.length === 0) {
    document.body.classList.add('empty');
    return;
  }
  document.body.classList.remove('empty');
  if (state.parserWarnings.length && state.selectedView !== 'raw') root.appendChild(warningStrip());
  switch (state.selectedView) {
    case 'contracts': root.appendChild(renderContractsView()); break;
    case 'txTree': root.appendChild(renderTxTreeView()); break;
    case 'disclosure': root.appendChild(renderDisclosureView()); break;
    case 'console': root.appendChild(renderConsoleView()); break;
    case 'raw': root.appendChild(renderRawView()); break;
    case 'overview':
    default: root.appendChild(renderOverviewView()); break;
  }
}

function renderOverviewView() {
  const contracts = state.model.contracts;
  const shownContracts = filteredContracts();
  const transactions = transactionsForDisplay();
  const consoleEntries = allConsoleEntries();
  const active = contracts.filter(c => !c.archived).length;
  const archived = contracts.filter(c => c.archived).length;
  const parties = state.model.parties;
  const view = el('section', { className: 'overview-grid' });
  view.appendChild(el('div', { className: 'summary-row' }, [
    summaryCard('Contracts', String(contracts.length), active + ' active / ' + archived + ' archived', 'contracts'),
    summaryCard('Templates', String(state.model.templates.length), state.model.templates.slice(0, 2).join(', ') || 'No templates parsed', 'contracts'),
    summaryCard('Transactions', String(transactions.length), 'Open causal tree', 'txTree'),
    summaryCard('Console', String(consoleEntries.length), consoleEntries.length ? 'Script output available' : 'No script output', 'console')
  ]));
  view.appendChild(el('div', { className: 'overview-main' }, [
    panel('Run Summary', renderRunSummary(contracts, transactions)),
    panel('Parties & Roles', renderPartyOverview(contracts, parties)),
    panel('Transaction Flow', renderTxPreview(transactions)),
    panel('Recent Changes', renderRecentEvents(shownContracts.length ? shownContracts : contracts)),
    panel('Raw Fidelity', el('div', { className: 'fidelity' }, [
      el('p', {}, 'Raw sanitized Script Results are preserved for verification.'),
      actionButton('Open Raw', () => selectView('raw'))
    ]))
  ]));
  return view;
}

function renderRunSummary(contracts, transactions) {
  if (!contracts.length && !transactions.length) return emptyState('No parsed contracts or transactions. Open Raw for the original result.');
  const templates = Array.from(new Set(contracts.map(c => c.templateShort))).sort();
  return el('div', { className: 'overview-list' }, [
    metricRow('Active contracts', String(contracts.filter(c => !c.archived).length)),
    metricRow('Archived contracts', String(contracts.filter(c => c.archived).length)),
    metricRow('Transaction groups', String(transactions.length)),
    metricRow('Templates', templates.slice(0, 6).join(', ') || 'Unavailable')
  ]);
}

function renderPartyOverview(contracts, parties) {
  if (!parties.length) return emptyState('No DPM disclosure parties.');
  return el('div', { className: 'party-role-list compact' }, parties.map(name => {
    const roles = rolesForPartyAcrossContracts(contracts, name);
    return el('div', { className: 'party-role-row' }, [
      partyChip({ name, roles }, null, false),
      el('div', { className: 'role-stack' }, partyRoleLabels(roles).map(label =>
        el('span', { className: 'party-role-badge ' + roleCssClass(label), title: roleDescription(label) }, label)
      )),
      el('div', { className: 'muted party-role-help' }, roleSummary(partyRoleLabels(roles)))
    ]);
  }));
}

function renderTxPreview(transactions) {
  if (!transactions.length) return emptyState('No transaction tree found in DPM output. Open Raw for the original result.');
  return el('div', { className: 'tx-preview-list' }, transactions.slice(0, 5).map(tx =>
    el('button', {
      className: 'tx-preview-row',
      type: 'button',
      onclick: () => { state.selectedTransactionId = tx.id; selectView('txTree'); }
    }, [
      eventBadge('Tx'),
      el('span', {}, tx.label),
      el('span', { className: 'muted' }, flattenEvents(tx.events).length + ' events')
    ])
  ));
}

function renderContractsView() {
  const contracts = filteredContracts();
  const selected = contracts.find(c => c.id === state.selectedContractId) || contracts[0] || null;
  if (selected) state.selectedContractId = selected.id;
  const groups = groupBy(contracts, c => c.template);
  return el('section', { className: 'split-view contracts-view' }, [
    el('aside', { className: 'master-pane' }, [
      el('div', { className: 'pane-title' }, [
        el('strong', {}, 'Contracts'),
        el('span', { className: 'muted' }, contracts.length + ' shown')
      ]),
      ...Array.from(groups.entries()).map(([template, items]) => contractGroup(template, items))
    ]),
    el('section', { className: 'detail-pane' }, selected ? contractDetail(selected) : emptyDetail('No contract selected.'))
  ]);
}

function contractGroup(template, contracts) {
  return el('section', { className: 'contract-group' }, [
    el('div', { className: 'group-header' }, [
      el('span', {}, template),
      el('span', { className: 'muted' }, '(' + contracts.length + ')')
    ]),
    ...contracts.map(contractCard)
  ]);
}

function contractCard(contract) {
  const selected = contract.id === state.selectedContractId;
  const select = () => { state.selectedContractId = contract.id; render(); };
  return el('article', {
    className: 'contract-card' + (selected ? ' selected' : '') + (contract.archived ? ' archived' : ''),
    role: 'button',
    tabIndex: '0',
    onclick: select,
    onkeydown: event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        select();
      }
    }
  }, [
    el('div', { className: 'contract-card-head' }, [
      statusBadge(contract.status),
      el('span', { className: 'contract-id mono', title: contract.id }, contract.displayId),
      copyButton(contract.id, 'Copy contract id')
    ]),
    el('div', { className: 'chip-row' }, contract.parties.slice(0, 4).map(p => partyChip(p))),
    el('dl', { className: 'field-preview' }, contract.fields.slice(0, 4).flatMap(field => [
      el('dt', {}, field.name),
      el('dd', { className: 'mono', title: field.value }, shortValue(field.value))
    ]))
  ]);
}

function contractDetail(contract) {
  return el('article', { className: 'inspector' }, [
    el('header', { className: 'inspector-header' }, [
      el('div', {}, [
        el('h2', {}, contract.templateShort),
        el('div', { className: 'muted' }, contract.template)
      ]),
      copyButton(contract.id, 'Copy contract id')
    ]),
    detailRows([
      ['Template', contract.template],
      ['Contract ID', contract.id],
      ['Status', contract.status],
      ['Created in', contract.transactionId ? 'Transaction #' + contract.transactionId : 'Unknown'],
      ['Package', contract.packageId || 'Unavailable']
    ]),
    sectionBlock('Fields', fieldTable(contract.fields)),
    sectionBlock('Parties', partyRoleList(contract.parties)),
    sectionBlock('Disclosure', disclosureMini(contract)),
    sectionBlock('References', el('div', { className: 'button-row' }, [
      contract.sourceHref ? actionButton('Open Source', () => revealLocation(contract.sourceHref)) : null,
      actionButton('Open Raw', () => selectView('raw'))
    ])),
    sectionBlock('Transaction', actionButton(contract.transactionId ? 'View Transaction #' + contract.transactionId : 'View Tx Tree', () => {
      state.selectedTransactionId = contract.transactionId || state.selectedTransactionId;
      selectView('txTree');
    }))
  ]);
}

function renderTxTreeView() {
  const transactions = filteredTransactions();
  const selected = transactions.find(tx => tx.id === state.selectedTransactionId) || transactions[0] || null;
  if (selected) state.selectedTransactionId = selected.id;
  return el('section', { className: 'split-view tx-view' }, [
    el('aside', { className: 'master-pane' }, [
      el('div', { className: 'pane-title' }, [
        el('strong', {}, 'Transactions'),
        el('span', { className: 'muted' }, transactions.length + ' shown')
      ]),
      ...transactions.map(tx => txButton(tx))
    ]),
    el('section', { className: 'detail-pane' }, selected ? txDetail(selected) : emptyDetail('No transaction data parsed. Open Raw for the original result.'))
  ]);
}

function txButton(tx) {
  const eventCount = flattenEvents(tx.events).length;
  return el('button', {
    className: 'tx-row' + (tx.id === state.selectedTransactionId ? ' selected' : ''),
    type: 'button',
    onclick: () => { state.selectedTransactionId = tx.id; render(); }
  }, [
    el('span', { className: 'tx-title' }, tx.label),
    el('span', { className: 'muted' }, eventCount + ' events')
  ]);
}

function txDetail(tx) {
  const events = tx.events.length ? tx.events : (tx.rawText ? [rawTransactionEvent(tx.id, tx.rawText)] : []);
  const eventCount = flattenEvents(events).length;
  return el('article', { className: 'inspector' }, [
    el('header', { className: 'inspector-header' }, [
      el('div', {}, [
        el('h2', {}, tx.label),
        el('div', { className: 'muted' }, eventCount + ' parsed events')
      ]),
      el('div', { className: 'button-row' }, [
        actionButton('Expand all', () => document.querySelectorAll('details.tx-node').forEach(d => d.open = true)),
        actionButton('Collapse all', () => document.querySelectorAll('details.tx-node').forEach(d => d.open = false))
      ])
    ]),
    txSummary(tx),
    events.length ? el('div', { className: 'tx-tree' }, events.map((event, index) => eventNode(event, index + 1))) : emptyState('No transaction events were parsed. Open Raw for the original result.')
  ]);
}

function txSummary(tx) {
  const sourceEvents = tx.events.length ? tx.events : (tx.rawText ? [rawTransactionEvent(tx.id, tx.rawText)] : []);
  const events = flattenEvents(sourceEvents);
  const parties = transactionParties(events);
  const primaryEvent = primaryTransactionEvent(events);
  return el('section', { className: 'tx-summary-stack' }, [
    txActionCard(primaryEvent, parties)
  ]);
}

function primaryTransactionEvent(events) {
  return (events || []).find(event => event && String(event.kind || '') !== 'Raw') || (events || [])[0] || null;
}

function transactionParties(events) {
  return uniqueParties((events || []).flatMap(event => (event.parties || []).concat(event.actors || [])));
}

function txActionCard(event, parties) {
  if (!event) return emptyState('No transaction action was parsed.');
  const actor = eventPrimaryActors(event)[0] || null;
  const visibleParties = (parties || []).slice(0, 2);
  return el('section', { className: 'tx-action-card' }, [
    el('div', { className: 'tx-action-copy' }, [
      el('div', { className: 'muted tx-action-label' }, 'Transaction action'),
      el('div', { className: 'tx-action-sentence' }, [
        eventBadge(event.kind),
        actor ? compactPartyChip(actor) : null,
        transactionVerb(event.kind) ? txWord(transactionVerb(event.kind), 'tx-keyword tx-keyword-' + eventKindCss(event.kind)) : null,
        txTemplateChip(event.template || event.label || event.kind),
        event.contractId ? txContractButton(event.contractId, shortId(event.contractId)) : null,
        el('span', { className: 'muted' }, eventActionNote(event))
      ])
    ]),
    visibleParties.length ? el('div', { className: 'tx-primary-parties' }, [
      el('span', { className: 'muted tx-primary-parties-label' }, 'Primary parties'),
      visibleParties.map(party => compactPartyChip(party)),
      parties.length > visibleParties.length ? el('span', { className: 'muted' }, '+' + (parties.length - visibleParties.length)) : null
    ]) : null
  ]);
}

function eventNode(event, index) {
  return eventNodeAt(event, String(index));
}

function eventNodeAt(event, indexLabel) {
  const children = event.children || [];
  return el('details', { className: 'tx-node tx-node-' + eventKindCss(event.kind), open: true }, [
    el('summary', {}, [
      el('button', {
        className: 'tx-toggle',
        type: 'button',
        title: 'Expand or collapse transaction event',
        onclick: toggleEventNode
      }),
      el('span', { className: 'tx-step' }, indexLabel),
      eventBadge(event.kind),
      el('span', { className: 'tx-event-main' }, [
        el('span', { className: 'tx-event-title' }, eventRowTitle(event)),
        eventRowActor(event)
      ]),
      event.contractId ? txContractButton(event.contractId, shortId(event.contractId)) : null
    ]),
    el('div', { className: 'tx-node-body' }, [
      eventDetailSummary(event),
      event.fields && event.fields.length ? compactFieldGrid(event.fields.slice(0, 6)) : null,
      event.rawText && !event.fields.length ? el('div', { className: 'mono muted wrap' }, event.rawText) : null,
      children.length ? el('div', { className: 'tx-children' }, children.map((child, index) => eventNodeAt(child, indexLabel + '.' + (index + 1)))) : null
    ])
  ]);
}

function toggleEventNode(event) {
  event.preventDefault();
  event.stopPropagation();
  const node = event.currentTarget.closest('details');
  if (node) node.open = !node.open;
}

function eventHeadline(event) {
  const kind = String(event.kind || 'Event');
  const actors = eventPrimaryActors(event).map(party => party.name || party).filter(Boolean);
  const parts = [];
  if (actors.length) {
    actors.slice(0, 2).forEach((party, index) => {
      if (index > 0) parts.push(txWord('and', 'tx-keyword'));
      parts.push(txParty(party));
    });
    if (actors.length > 2) parts.push(txWord('+' + (actors.length - 2), 'tx-muted-token'));
  }

  const verb = transactionVerb(kind);
  if (verb) parts.push(txWord(verb, 'tx-keyword tx-keyword-' + eventKindCss(kind)));

  if (kind === 'Exercise') {
    parts.push(txWord(event.label || 'choice', 'tx-choice'));
    if (event.contractId) parts.push(txWord('on', 'tx-keyword'));
    if (event.template) parts.push(txWord(templateShortName(event.template), 'tx-template'));
  } else if (kind === 'Create') {
    parts.push(txWord(templateShortName(event.template || event.label), 'tx-template'));
  } else if (kind === 'Fetch' || kind === 'Archive' || kind === 'Archived/Result') {
    if (event.template || event.label) parts.push(txWord(templateShortName(event.template || event.label), 'tx-template'));
  } else {
    parts.push(txWord(event.label || kind, 'tx-template'));
  }

  return el('span', { className: 'tx-event-title' }, parts.length ? parts : [document.createTextNode(event.label || kind)]);
}

function eventSummaryRoles(event) {
  const groups = [];
  const kind = String(event.kind || '');
  if (kind === 'Create') {
    groups.push(eventRoleGroup('signatory', partiesWithRole(event.parties, 'signatory')));
    groups.push(eventRoleGroup('observer', partiesWithRole(event.parties, 'observer')));
  } else if (kind === 'Exercise') {
    const controllers = normalizePartyObjects(event.actors, 'controller')
      .concat(partiesWithRole(event.parties, 'controller'));
    groups.push(eventRoleGroup('controller', uniqueParties(controllers)));
  } else {
    groups.push(eventRoleGroup('witness', partiesWithRole(event.parties, 'witness')));
  }
  const visibleGroups = groups.filter(Boolean);
  if (!visibleGroups.length) return null;
  return el('span', { className: 'tx-event-meta' }, visibleGroups);
}

function eventRoleGroup(role, parties) {
  const unique = uniqueParties(parties || []);
  if (!unique.length) return null;
  const shown = unique.slice(0, 3);
  return el('span', { className: 'tx-role-group tx-role-group-' + roleCssClass(role) }, [
    el('span', { className: 'tx-role-label ' + roleCssClass(role) }, role),
    shown.map(party => partyChip(Object.assign({}, party, { roles: [role] }), null, false)),
    unique.length > shown.length ? el('span', { className: 'tx-muted-token' }, '+' + (unique.length - shown.length)) : null
  ]);
}

function eventDetailSummary(event) {
  const rows = decodedEventRows(event);
  const roleRows = eventRoleRows(event);
  const partySummary = eventPartiesForSummary(event);
  if (!rows.length && !roleRows.length && !partySummary.length) return null;
  return el('div', { className: 'tx-detail-grid' }, [
    el('section', { className: 'tx-detail-card tx-decoded-event' }, [
      el('h3', {}, 'Decoded event'),
      rows.length ? el('dl', { className: 'tx-decoded-list' }, rows.flatMap(row => [
        el('dt', {}, row.label),
        el('dd', {}, row.value)
      ])) : emptyState('Unavailable')
    ]),
    state.showDetailedDisclosure
      ? el('section', { className: 'tx-detail-card tx-party-role-card' }, [
        el('h3', {}, 'Parties by role'),
        roleRows.length ? partyRoleTable(roleRows) : emptyState('No role-bearing parties parsed.')
      ])
      : el('section', { className: 'tx-detail-card tx-party-summary-card' }, [
        el('h3', {}, 'Parties'),
        partySummary.length ? eventPartySummary(partySummary) : emptyState('No parties parsed.')
      ])
  ]);
}

function addDetailRow(rows, label, value) {
  if (!value) return;
  rows.push({ label, value });
}

function addPartyDetailRow(rows, label, parties) {
  const unique = uniqueParties(parties || []);
  if (!unique.length) return;
  rows.push({
    label,
    value: el('span', { className: 'chip-row compact' }, unique.map(party => partyChip(party)))
  });
}

function decodedEventRows(event) {
  const rows = [];
  const actors = eventPrimaryActors(event);
  addDetailRow(rows, 'Actor', actors.length ? el('span', { className: 'chip-row compact' }, actors.map(party => partyChip(party, null, false))) : null);
  addDetailRow(rows, 'Action', transactionVerb(event.kind) || event.kind || null);
  if (event.kind === 'Exercise' && event.label) addDetailRow(rows, 'Choice', txWord(event.label, 'tx-choice'));
  addDetailRow(rows, 'Template', event.template || event.label ? txTemplateChip(event.template || event.label) : null);
  addDetailRow(rows, 'Contract ID', event.contractId ? txContractButton(event.contractId, event.contractId, true) : null);
  return rows;
}

function eventRoleRows(event) {
  const kind = String(event && event.kind || '');
  if (kind === 'Create') {
    return roleRowsForRoles(event && event.parties, ['signatory', 'observer']);
  }
  if (kind === 'Exercise') {
    return roleRowsForRoles(
      uniqueParties(normalizePartyObjects(event && event.actors, 'controller').concat(partiesWithRole(event && event.parties, 'controller'))),
      ['controller']
    );
  }
  return roleRowsForRoles(event && event.parties, ['witness', 'disclosed', 'signatory', 'observer', 'controller']);
}

function roleRowsForRoles(parties, roles) {
  return roles
    .map(role => ({ role, parties: partiesWithRole(parties, role) }))
    .filter(row => row.parties.length);
}

function partyRoleTable(rows) {
  return el('div', { className: 'tx-party-role-table' }, [
    el('div', { className: 'tx-party-role-head' }, [
      el('span', {}, 'Role'),
      el('span', {}, 'Party')
    ]),
    rows.map(row => row.parties.map(party =>
      el('div', { className: 'tx-party-role-row' }, [
        txRoleBadge(row.role),
        partyChip(Object.assign({}, party, { roles: [row.role] }), null, false)
      ])
    )),
    el('div', { className: 'tx-party-role-count' }, [
      el('span', { className: 'muted' }, 'Party count'),
      el('strong', {}, String(uniqueParties(rows.flatMap(row => row.parties)).length))
    ])
  ]);
}

function eventPartiesForSummary(event) {
  return uniqueParties(
    normalizePartyObjects(event && event.actors, 'controller')
      .concat(normalizePartyObjects(event && event.parties))
  );
}

function eventPartySummary(parties) {
  return el('div', { className: 'tx-party-summary' }, uniqueParties(parties || []).map(party => compactPartyChip(party)));
}

function txRoleBadge(role) {
  return el('span', { className: 'tx-role-label ' + roleCssClass(role), title: roleDescription(role) }, role);
}

function eventRowTitle(event) {
  const kind = String(event.kind || '');
  if (kind === 'Exercise') {
    const title = event.label || 'choice';
    return event.template ? [txWord(title, 'tx-choice'), txWord('on', 'tx-muted-token'), txTemplateChip(event.template)] : [txWord(title, 'tx-choice')];
  }
  return txTemplateChip(event.template || event.label || kind || 'Event');
}

function eventRowActor(event) {
  const actor = eventPrimaryActors(event)[0] || null;
  if (!actor) return null;
  return el('span', { className: 'tx-event-actor' }, [
    el('span', { className: 'muted' }, eventRowActorVerb(event.kind)),
    compactPartyChip(actor)
  ]);
}

function eventRowActorVerb(kind) {
  const value = String(kind || '').toLowerCase();
  if (value.includes('create')) return 'created by';
  if (value.includes('exercise')) return 'exercised by';
  if (value.includes('fetch')) return 'fetched by';
  if (value.includes('archive')) return 'archived by';
  if (value.includes('lookup')) return 'looked up by';
  return 'by';
}

function eventActionNote(event) {
  const value = String(event && event.kind || '').toLowerCase();
  if (value.includes('create')) return 'created contract';
  if (value.includes('exercise')) return 'choice exercise';
  if (value.includes('fetch')) return 'contract fetch';
  if (value.includes('archive')) return 'archived contract';
  if (value.includes('lookup')) return 'contract lookup';
  return 'decoded event';
}

function txTemplateChip(value) {
  const text = String(value || 'Unavailable');
  return el('span', { className: 'tx-template', title: text }, templateShortName(text));
}

function compactPartyChip(party) {
  const label = typeof party === 'string' ? party : (party && party.name) || '';
  return el('span', {
    className: 'tx-party-chip party-' + stableColorIndex(label),
    title: label
  }, shortPartyName(label || 'unknown'));
}

function txContractButton(contractId, label, fullWidth) {
  return el('button', {
    className: 'tx-contract-link mono' + (fullWidth ? ' tx-contract-link-full' : ''),
    type: 'button',
    title: 'Open contract ' + contractId,
    onclick: ev => {
      ev.preventDefault();
      ev.stopPropagation();
      state.selectedContractId = contractId;
      selectView('contracts');
    }
  }, label || shortId(contractId));
}

function eventPrimaryActors(event) {
  return primaryActorsForKind(event && event.kind, event && event.parties, event && event.actors);
}

function primaryActorsForKind(kind, parties, fallbackActors) {
  const fallback = normalizePartyObjects(fallbackActors, 'controller');
  const kindText = String(kind || '');
  if (kindText === 'Create') {
    const signatories = partiesWithRole(parties, 'signatory');
    return signatories.length ? signatories : fallback;
  }
  if (kindText === 'Exercise') {
    const controllers = fallback.concat(partiesWithRole(parties, 'controller'));
    return uniqueParties(controllers);
  }
  return fallback.length ? fallback : partiesWithRole(parties, 'signatory');
}

function partiesWithRole(parties, role) {
  const wanted = cleanRole(role);
  return uniqueParties((parties || []).filter(party => partyRoleLabels(party && party.roles).includes(wanted)));
}

function normalizePartyObjects(parties, role) {
  return (parties || [])
    .map(party => typeof party === 'string' ? { name: cleanPartyValue(party), roles: role ? [role] : [] } : party)
    .filter(party => party && party.name && looksLikePartyValue(party.name));
}

function uniqueParties(parties) {
  const map = new Map();
  for (const party of normalizePartyObjects(parties)) {
    const name = cleanPartyValue(party.name);
    if (!name) continue;
    const existing = map.get(name) || { name, roles: [] };
    for (const role of partyRoleLabels(party.roles)) {
      if (!existing.roles.includes(role)) existing.roles.push(role);
    }
    map.set(name, existing);
  }
  return Array.from(map.values());
}

function txParty(name) {
  return el('span', { className: 'tx-party party-' + stableColorIndex(name), title: 'Party ' + name }, name);
}

function txWord(text, className) {
  return el('span', { className }, text);
}

function transactionVerb(kind) {
  const value = String(kind || '').toLowerCase();
  if (value.includes('create')) return 'creates';
  if (value.includes('exercise')) return 'exercises';
  if (value.includes('fetch')) return 'fetches';
  if (value.includes('archive')) return 'archives';
  if (value.includes('lookup')) return 'looks up';
  if (value.includes('rollback')) return 'rolls back';
  return '';
}

function eventKindCss(kind) {
  return String(kind || '').toLowerCase().replace(/[^a-z]+/g, '-');
}

function templateShortName(value) {
  const text = String(value || '').replace(/@[A-Za-z0-9]+$/, '');
  const afterColon = text.split(':').pop();
  return afterColon.split('.').pop() || afterColon || text;
}

function renderDisclosureView() {
  const contracts = filteredContracts();
  const parties = disclosurePartiesForContracts(contracts);
  if (contracts.length === 0 || parties.length === 0) return emptyDetail('No DPM disclosure table data was found. Open Raw for the original result.');
  const divulged = parties.filter(party => contracts.some(contract => disclosureState(contract, party).kind === 'divulged'));
  return el('section', { className: 'disclosure-view' }, [
    el('div', { className: 'pane-title' }, [
      el('strong', {}, 'Disclosure Matrix'),
      el('span', { className: 'muted' }, parties.length + ' parties / ' + contracts.length + ' contracts')
    ]),
    divulged.length ? el('div', { className: 'divulgence-strip' }, [
      el('strong', {}, 'Divulged in transaction'),
      ...divulged.map(party => partyChip({ name: party, roles: ['divulged'] }))
    ]) : null,
    el('div', { className: 'matrix-scroll' }, [
      el('table', { className: 'disclosure-matrix' }, [
        el('thead', {}, [
          el('tr', {}, [
            el('th', {}, 'Contract'),
            ...parties.map(p => el('th', {}, partyChip(p)))
          ])
        ]),
        el('tbody', {}, contracts.map(contract => el('tr', {}, [
          el('td', {}, [
            el('button', {
              className: 'link-button',
              type: 'button',
              onclick: () => { state.selectedContractId = contract.id; selectView('contracts'); }
            }, contract.templateShort),
            el('div', { className: 'muted mono' }, contract.displayId)
          ]),
          ...parties.map(party => disclosureCell(contract, party))
        ])))
      ])
    ])
  ]);
}

function renderConsoleView() {
  const allNotes = allConsoleEntries();
  const notes = allNotes.filter(note => matchesSearch(note.text));
  return el('section', { className: 'console-view' }, [
    el('div', { className: 'pane-title' }, [
      el('strong', {}, 'Console'),
      el('span', { className: 'muted' }, notes.length + ' entries'),
      actionButton('Copy all', () => copyText(allNotes.map(n => n.text).join('\n\n')))
    ]),
    notes.length ? el('div', { className: 'log-list' }, notes.map(logEntry)) : emptyState('No script debug output or notes were found.')
  ]);
}

function renderRawView() {
  return el('section', { className: 'raw-view' }, [
    el('div', { className: 'pane-title' }, [
      el('strong', {}, 'Raw Sanitized Script Results'),
      el('span', { className: 'muted' }, byteSize(state.sanitizedHtml) + ' sanitized'),
      actionButton('Copy HTML', () => copyText(state.sanitizedHtml))
    ]),
    rawRendered(state.sanitizedHtml),
    el('details', { className: 'raw-source' }, [
      el('summary', {}, 'Sanitized HTML source'),
      el('pre', { className: 'raw-code' }, state.sanitizedHtml || 'No HTML received.')
    ])
  ]);
}

function warningStrip() {
  return el('div', { className: 'warning-strip' }, [
    el('strong', {}, 'Parsed view may be incomplete.'),
    document.createTextNode(' Raw remains available. '),
    actionButton('Open Raw', () => selectView('raw'))
  ]);
}

function summaryCard(label, value, description, targetView) {
  return el('button', { className: 'summary-card', type: 'button', onclick: () => selectView(targetView) }, [
    el('span', { className: 'summary-label' }, label),
    el('strong', {}, value),
    el('span', { className: 'muted' }, description)
  ]);
}

function renderRecentEvents(contracts) {
  if (!contracts.length) return emptyState('No parsed changes.');
  return el('div', { className: 'event-list' }, contracts.slice(0, 6).map(contract =>
    el('button', {
      className: 'event-row',
      type: 'button',
      onclick: () => { state.selectedContractId = contract.id; selectView('contracts'); }
    }, [
      eventBadge(contract.archived ? 'Archived' : 'Create'),
      el('span', {}, contract.templateShort),
      el('span', { className: 'muted mono' }, contract.displayId)
    ])
  ));
}

function panel(title, body) {
  return el('section', { className: 'panel' }, [el('h3', {}, title), body]);
}

function metricRow(label, value) {
  return el('div', { className: 'metric-row' }, [
    el('span', { className: 'muted' }, label),
    el('strong', {}, value)
  ]);
}

function sectionBlock(title, body) {
  return el('section', { className: 'section-block' }, [el('h3', {}, title), body || emptyState('Unavailable')]);
}

function detailRows(rows) {
  return el('dl', { className: 'detail-rows' }, rows.flatMap(([label, value]) => [
    el('dt', {}, label),
    el('dd', { className: String(value).length > 32 ? 'mono wrap' : '' }, String(value || 'Unavailable'))
  ]));
}

function fieldTable(fields) {
  if (!fields || !fields.length) return emptyState('No fields found in DPM output.');
  return el('table', { className: 'field-table' }, [
    el('tbody', {}, fields.map(field => el('tr', {}, [
      el('th', {}, field.name),
      el('td', { className: 'mono wrap' }, [
        referenceValue(field.value),
        copyButton(field.value, 'Copy value')
      ])
    ])))
  ]);
}

function compactFieldGrid(fields) {
  if (!fields || !fields.length) return emptyState('No fields found in DPM output.');
  return el('dl', { className: 'compact-field-grid' }, fields.flatMap(field => [
    el('dt', {}, field.name),
    el('dd', { className: 'mono wrap' }, shortValue(field.value))
  ]));
}

function referenceValue(value) {
  const text = String(value || '');
  const id = text.match(/#\d+:\d+/);
  if (!id) return document.createTextNode(text);
  return el('button', {
    className: 'link-button mono',
    type: 'button',
    onclick: () => {
      const target = state.model.contracts.find(contract => contract.id === id[0]);
      if (target) {
        state.selectedContractId = target.id;
        selectView('contracts');
      }
    }
  }, text);
}

function disclosureMini(contract) {
  if (!contract.disclosures.length) return emptyState('No disclosure columns found in DPM output.');
  return el('div', { className: 'chip-row' }, contract.disclosures.map(disclosure =>
    el('span', { className: 'role-chip' + (disclosure.visible ? ' visible' : '') }, [
      partyChip({ name: disclosure.party, roles: roleList(disclosure.detail) }),
      el('span', {}, disclosure.visible ? (state.showDetailedDisclosure ? disclosure.detail || 'visible' : 'visible') : 'hidden')
    ])
  ));
}

function disclosureCell(contract, party) {
  const stateForParty = disclosureState(contract, party);
  return el('td', { className: 'matrix-' + stateForParty.kind }, stateForParty.kind !== 'hidden'
    ? el('span', { className: 'role-badge ' + roleCssClass(stateForParty.kind), title: stateForParty.title }, stateForParty.label)
    : el('span', { className: 'muted' }, '-'));
}

function rawRendered(html) {
  const box = el('div', { className: 'raw-rendered' });
  const fragment = sanitizeToFragment(html);
  box.appendChild(fragment);
  return box;
}

function logEntry(note) {
  return el('article', { className: 'log-entry ' + note.severity }, [
    el('div', { className: 'log-meta' }, [
      el('span', { className: 'severity' }, note.severity),
      el('span', { className: 'muted' }, note.timestamp || note.sourceLabel || 'script result'),
      copyButton(note.text, 'Copy log line')
    ]),
    el('div', { className: 'log-body mono' }, note.html ? htmlFragment(note.html) : document.createTextNode(note.text))
  ]);
}

function htmlFragment(html) {
  const fragment = document.createDocumentFragment();
  fragment.appendChild(sanitizeToFragment(html));
  return fragment;
}

function extractConsoleEntries(root) {
  const entries = extractTransactionTraceEntries(root);
  for (const element of Array.from(root.querySelectorAll(CONSOLE_SELECTOR))) {
    if (element.closest('.transaction,.transaction-tree,.tx-tree,[data-transaction],[data-transaction-tree],[data-tx-tree]')) continue;
    const text = cleanConsoleText(element.textContent);
    const entry = makeConsoleEntry(text, element.outerHTML || element.innerHTML, sourceForConsoleElement(element), 'script result');
    if (entry) entries.push(entry);
  }

  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    const parent = node.parentElement;
    if (!parent || parent.closest(CONSOLE_SELECTOR + ',table,h1,h2,h3,.transaction,button,label')) continue;
    for (const line of String(node.textContent || '').split(/\r?\n/)) {
      const text = cleanConsoleText(line);
      if (!looksLikeConsoleLine(text)) continue;
      const entry = makeConsoleEntry(text, '', 'script-result', 'script result');
      if (entry) entries.push(entry);
    }
  }
  return mergeConsoleEntries(entries);
}

function extractTransactionTraceEntries(root) {
  const entries = [];
  for (const section of transactionSections(root)) {
    for (const line of consoleLinesFromTransactionTraceText(textWithBreaks(section))) {
      const entry = makeConsoleEntry(line, '', 'trace', 'script trace');
      if (entry) entries.push(entry);
    }
  }
  return entries;
}

function consoleLinesFromTransactionTraceText(text) {
  const entries = [];
  let inTrace = false;
  for (const rawLine of String(text || '').replace(/\r/g, '\n').split('\n')) {
    const line = cleanConsoleText(rawLine);
    if (!line) continue;
    const trace = line.match(/^Trace:\s*(.*)$/i);
    if (trace) {
      inTrace = true;
      const inline = cleanConsoleText(trace[1] || '');
      if (inline) entries.push(unquoteConsoleLine(inline));
      continue;
    }
    if (!inTrace) continue;
    if (/^(Transactions|Active contracts|Return value):/i.test(line)) break;
    const unquoted = unquoteConsoleLine(line);
    if (looksLikeConsoleLine(unquoted) || /^["'].*["']$/.test(line)) entries.push(unquoted);
  }
  return entries;
}

function unquoteConsoleLine(text) {
  return cleanConsoleText(text).replace(/^["'](.+)["']$/, '$1');
}

function sourceForConsoleElement(element) {
  const classes = String(element.className || '').toLowerCase();
  if (classes.includes('debug') || classes.includes('trace')) return 'debug';
  if (classes.includes('note')) return 'note';
  if (classes.includes('log') || classes.includes('console') || classes.includes('output')) return 'log';
  return 'script-result';
}

function consoleEntryFromHtml(html, source) {
  const fragment = sanitizeToFragment(html);
  const text = cleanConsoleText(fragment.textContent);
  const safeHtml = fragmentToHtml(fragment);
  return makeConsoleEntry(text || safeHtml || String(html || ''), safeHtml, source || 'server-note', 'server note');
}

function makeConsoleEntry(text, html, source, sourceLabel) {
  const body = cleanConsoleText(text || html);
  if (!body) return null;
  return {
    id: '',
    html: html || '',
    text: body,
    severity: classifySeverity(body),
    timestamp: source === 'server-note' ? new Date().toLocaleTimeString() : '',
    source: source || 'script-result',
    sourceLabel: sourceLabel || source || 'script result'
  };
}

function allConsoleEntries() {
  return mergeConsoleEntries(state.notes, state.model.consoleEntries);
}

function mergeConsoleEntries() {
  const merged = [];
  const seen = new Set();
  for (const group of Array.from(arguments)) {
    for (const entry of group || []) {
      if (!entry) continue;
      const key = normalizeConsoleText(entry.text || entry.html);
      if (!key || seen.has(key)) continue;
      seen.add(key);
      merged.push(Object.assign({}, entry, { id: entry.id || ('console-' + (merged.length + 1)) }));
    }
  }
  return merged;
}

function cleanConsoleText(text) {
  return cleanText(String(text || '').replace(/\u001b\[[0-9;]*m/g, ''));
}

function normalizeConsoleText(text) {
  return cleanConsoleText(text)
    .replace(/^(trace|debug|info|warn(?:ing)?|error)\s*[:\]-\s]+/i, '')
    .replace(/^["'](.+)["']$/, '$1')
    .toLowerCase();
}

function looksLikeConsoleLine(text) {
  const value = cleanConsoleText(text);
  if (!value || value.length > 1000) return false;
  return /^(trace|debug|info|warn(?:ing)?|error)\b\s*[:\]-]?/i.test(value) ||
    /^\[[^\]]{1,48}\]\s+/.test(value) ||
    /^script service stderr:/i.test(value);
}

function statusBadge(status) {
  const normalized = String(status || 'active').toLowerCase();
  return el('span', { className: 'status-badge ' + (normalized === 'archived' ? 'archived' : 'active') }, normalized || 'active');
}

function eventBadge(kind) {
  const css = String(kind || '').toLowerCase().replace(/[^a-z]+/g, '-');
  return el('span', { className: 'event-badge ' + css }, kind || 'Event');
}

function partyRoleList(parties) {
  if (!parties || !parties.length) return emptyState('No DPM disclosure parties.');
  return el('div', { className: 'party-role-list' }, parties.map(party => {
    const labels = partyRoleLabels(party.roles);
    return el('div', { className: 'party-role-row' }, [
      partyChip(party, null, false),
      el('div', { className: 'role-stack' }, labels.map(label =>
        el('span', { className: 'party-role-badge ' + roleCssClass(label), title: roleDescription(label) }, label)
      )),
      el('div', { className: 'muted party-role-help' }, roleSummary(labels))
    ]);
  }));
}

function rolesForPartyAcrossContracts(contracts, partyName) {
  const roles = [];
  for (const contract of contracts) {
    const disclosure = contract.disclosures.find(item => cleanPartyValue(item.party) === partyName);
    if (disclosure && disclosure.visible) roles.push(...roleList(disclosure.detail || 'visible'));
  }
  return Array.from(new Set(roles));
}

// `showRoles === false` renders a name-only chip; used where an adjacent role-stack
// already lists the roles, so embedding them in the chip too would be redundant.
function partyChip(party, roles, showRoles) {
  const label = typeof party === 'string' ? party : (party && party.name) || '';
  const roleLabels = partyRoleLabels(roles || (party && party.roles));
  const title = roleLabels.length ? label + ' - ' + roleLabels.map(roleDescription).join('; ') : label;
  const children = [el('span', { className: 'party-name' }, label || 'unknown')];
  if (showRoles !== false) {
    for (const role of roleLabels.slice(0, 2)) {
      children.push(el('span', { className: 'party-chip-role ' + roleCssClass(role) }, role));
    }
  }
  return el('span', { className: 'party-chip party-' + stableColorIndex(label), title }, children);
}

function copyButton(value, title) {
  return el('button', {
    className: 'copy-button',
    type: 'button',
    title: title || 'Copy',
    onclick: event => { event.stopPropagation(); copyText(String(value || '')); }
  }, 'Copy');
}

function actionButton(label, action) {
  return el('button', { className: 'action-button', type: 'button', onclick: action }, label);
}

function emptyState(text) {
  return el('div', { className: 'empty-state' }, text);
}

function emptyDetail(text) {
  return el('section', { className: 'empty-detail' }, [emptyState(text), actionButton('Open Raw', () => selectView('raw'))]);
}

function filteredContracts() {
  const query = state.search.toLowerCase();
  return state.model.contracts.filter(contract => {
    if (!state.showArchived && contract.archived) return false;
    if (!query) return true;
    return [
      contract.id, contract.template, contract.templateShort, contract.status,
      ...contract.fields.flatMap(f => [f.name, f.value]),
      ...contract.parties.map(p => p.name)
    ].join(' ').toLowerCase().includes(query);
  });
}

function filteredTransactions() {
  const query = state.search.toLowerCase();
  const contractsById = new Map(state.model.contracts.map(contract => [contract.id, contract]));
  return transactionsForDisplay().map(tx => {
    const events = filterTransactionEventsForDisplay(tx.events, {
      contractsById,
      query,
      showArchived: state.showArchived
    });
    return Object.assign({}, tx, { events });
  }).filter(tx => {
    if (!tx.events.length && !tx.rawHtml) return false;
    if (!query) return true;
    return [tx.id, tx.label, ...tx.events.map(e => [e.kind, e.label, e.template, e.contractId].join(' ')), tx.rawHtml || '']
      .join(' ').toLowerCase().includes(query);
  });
}

function filterTransactionEventsForDisplay(events, options) {
  const config = options || {};
  const query = String(config.query || '').toLowerCase();
  return (events || []).map(event => {
    if (!event) return null;
    if (!config.showArchived && isArchivedTransactionEvent(event, config.contractsById)) return null;
    const children = filterTransactionEventsForDisplay(event.children || [], config);
    const visibleEvent = Object.assign({}, event, { children });
    if (!query || transactionEventMatchesQuery(visibleEvent, query)) return visibleEvent;
    return null;
  }).filter(Boolean);
}

function transactionEventMatchesQuery(event, query) {
  const text = flattenEvents([event]).flatMap(item => [
    item.kind,
    item.label,
    item.template,
    item.contractId,
    item.rawText,
    ...(item.actors || []).map(party => party.name || party),
    ...(item.parties || []).map(party => party.name || party),
    ...(item.fields || []).flatMap(field => [field.name, field.value])
  ]).join(' ').toLowerCase();
  return text.includes(query);
}

function isArchivedTransactionEvent(event, contractsById) {
  const kind = String(event && event.kind || '').toLowerCase();
  if (kind.includes('archive')) return true;
  if (event && event.archived) return true;
  if (!event || event.source !== 'contract' || !event.contractId || !contractsById) return false;
  const contract = contractsById instanceof Map
    ? contractsById.get(event.contractId)
    : contractsById[event.contractId];
  return Boolean(contract && contract.archived);
}

function transactionsForDisplay() {
  return mergeTransactionGroups(state.model.transactions, synthesizeTransactionsFromContracts(state.model.contracts));
}

function synthesizeTransactionsFromContracts(contracts) {
  const map = transactionMapFromContracts(contracts);
  return Array.from(map.values()).sort((a, b) => transactionSortKey(a.id) - transactionSortKey(b.id));
}

function mergeTransactionGroups(primary, fallback) {
  const map = new Map();
  for (const tx of (fallback || []).concat(primary || [])) {
    if (!tx) continue;
    const existing = map.get(tx.id);
    if (!existing) {
      map.set(tx.id, Object.assign({}, tx, { events: (tx.events || []).slice() }));
      continue;
    }
    existing.label = tx.label || existing.label;
    existing.rawHtml = tx.rawHtml || existing.rawHtml;
    existing.rawText = tx.rawText || existing.rawText;
    existing.events = mergeTransactionEvents(tx.events || [], existing.events || []);
  }
  return Array.from(map.values()).sort((a, b) => transactionSortKey(a.id) - transactionSortKey(b.id));
}

function flattenEvents(events) {
  const flattened = [];
  for (const event of events || []) {
    if (!event) continue;
    flattened.push(event);
    flattened.push(...flattenEvents(event.children || []));
  }
  return flattened;
}

function matchesSearch(text) {
  return !state.search || String(text || '').toLowerCase().includes(state.search.toLowerCase());
}

function selectView(view) {
  state.selectedView = normalizeView(view);
  vscode.postMessage({ command: 'set_selected_view', value: state.selectedView });
  render();
}

function revealLocation(href) {
  if (!href) return;
  vscode.postMessage({ command: 'reveal_location', value: href });
}

function toggleArchived() {
  state.showArchived = Boolean(document.getElementById('show_archived').checked);
  document.body.classList.toggle('hide_archived', !state.showArchived);
  vscode.postMessage({ command: 'set_show_archived', value: state.showArchived });
  render();
}

function toggleDetailedDisclosure() {
  state.showDetailedDisclosure = Boolean(document.getElementById('show_detailed_disclosure').checked);
  document.body.classList.toggle('hidden_disclosure', !state.showDetailedDisclosure);
  vscode.postMessage({ command: 'set_show_detailed_disclosure', value: state.showDetailedDisclosure });
  render();
}

function updateSearch() {
  state.search = document.getElementById('search_input').value || '';
  renderCurrentView();
}

function searchPlaceholder() {
  switch (state.selectedView) {
    case 'contracts': return 'Search templates, fields, parties, contract ids...';
    case 'txTree': return 'Search transaction ids, events, templates, parties...';
    case 'disclosure': return 'Search contracts, parties, roles...';
    case 'console': return 'Search console output...';
    case 'raw': return 'Search raw result text...';
    default: return 'Search results...';
  }
}

function groupBy(items, keyFn) {
  const map = new Map();
  for (const item of items) {
    const key = keyFn(item);
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(item);
  }
  return map;
}

function headerIndex(headers, name) {
  return headers.findIndex(header => header.toLowerCase() === name);
}

function valueAt(values, index) {
  return index >= 0 ? (values[index] || '') : '';
}

function cleanText(text) {
  return String(text || '').replace(/\s+/g, ' ').trim();
}

function textOf(node) {
  return cleanText(node && node.textContent);
}

function shortId(id) {
  const value = String(id || '');
  if (value.length <= 18) return value;
  return value.slice(0, 10) + '...' + value.slice(-8);
}

function shortPartyName(value) {
  const text = String(value || '');
  if (text.length <= 30) return text;
  return text.slice(0, 20) + '...' + text.slice(-8);
}

function shortValue(value) {
  const text = String(value || '');
  return text.length > 42 ? text.slice(0, 34) + '...' + text.slice(-6) : text;
}

function firstContractId(text) {
  const match = String(text || '').match(/#\d+:\d+/);
  return match ? match[0] : '';
}

function transactionIdFromContractId(id) {
  const match = String(id || '').match(/^#?(\d+):/);
  return match ? match[1] : '';
}

function transactionSortKey(id) {
  const n = Number(String(id).replace(/[^\d]/g, ''));
  return Number.isFinite(n) ? n : Number.MAX_SAFE_INTEGER;
}

function firstRevealHref(root) {
  const link = root && root.querySelector && root.querySelector('a[href^="command:daml.revealLocation"]');
  return link ? link.getAttribute('href') : '';
}

function isDisclosureHeader(header, headerCell) {
  const text = String(header || '').toLowerCase();
  return Boolean(text) && !['id', 'status'].includes(text) &&
    Boolean(headerCell && (
      headerCell.classList.contains('observer') ||
      headerCell.querySelector('.observer,.tooltip,.tooltiptext')
    ));
}

function isDisclosureVisible(cell, value) {
  if (!cell) return false;
  const text = String(value || '').trim().toLowerCase();
  return cell.classList.contains('disclosed') ||
    text === 'x' ||
    text === 'visible' ||
    text.includes('observer') ||
    text.includes('signatory') ||
    text.includes('witness') ||
    text.includes('disclosed') ||
    text.includes('divulged');
}

function cleanPartyValue(value) {
  const text = String(value || '').trim();
  const singleQuoted = text.match(/^'([^']+)'$/);
  return singleQuoted ? singleQuoted[1] : text;
}

function looksLikePartyValue(value) {
  const text = cleanPartyValue(value);
  if (!text || text.startsWith('#')) return false;
  if (/^".*"$/.test(text)) return false;
  if (/^-?\d+(\.\d+)?$/.test(text)) return false;
  return /^[A-Za-z][A-Za-z0-9_.:-]*$/.test(text);
}

function roleList(text) {
  const value = String(text || '').toLowerCase();
  const roles = [];
  if (value.includes('controller')) roles.push('controller');
  if (value.includes('signatory')) roles.push('signatory');
  if (value.includes('observer')) roles.push('observer');
  if (value.includes('witness')) roles.push('witness');
  if (value.includes('divulged')) roles.push('divulged');
  if (value === 'visible') roles.push('visible');
  if (!roles.length && value) roles.push(cleanRole(value));
  return roles;
}

function partyRoleLabels(roles) {
  const list = Array.isArray(roles) ? roles : [];
  const normalized = Array.from(new Set(list.map(cleanRole).filter(Boolean)));
  const labels = [];
  for (const role of normalized) {
    if (role === 'signatory' || role === 'observer') {
      pushUnique(labels, role);
    } else if (role === 'visible') {
      pushUnique(labels, 'disclosed');
    } else if (role === 'witness') {
      pushUnique(labels, 'witness');
    } else if (role === 'controller') {
      pushUnique(labels, 'controller');
    } else {
      pushUnique(labels, role);
    }
  }
  return labels;
}

function cleanRole(role) {
  return String(role || '').trim().toLowerCase();
}

function pushUnique(items, value) {
  if (value && !items.includes(value)) items.push(value);
}

function roleCssClass(role) {
  return 'role-' + String(role || '').replace(/[^a-z0-9]+/g, '-');
}

function roleDescription(role) {
  switch (role) {
    case 'signatory': return 'Signatory on the contract';
    case 'observer': return 'Observer on the contract';
    case 'controller': return 'Controller of a visible action or choice';
    case 'witness': return 'Witness or disclosed visibility in the transaction';
    case 'disclosed': return 'Visible in the disclosure table';
    default: return role;
  }
}

function roleSummary(labels) {
  if (labels.includes('signatory')) return 'DPM reports this party as a signatory.';
  if (labels.includes('observer')) return 'DPM reports this party as an observer.';
  if (labels.includes('controller')) return 'Can act as a controller for a visible action.';
  if (labels.includes('witness') || labels.includes('disclosed')) return 'Visible in DPM output without signatory or observer role.';
  return 'Role extracted from DPM disclosure output.';
}

function disclosureState(contract, party) {
  const disclosure = contract.disclosures.find(d => cleanPartyValue(d.party) === party);
  const roles = disclosure && disclosure.visible ? roleList(disclosure.detail || 'visible') : [];
  return classifyDisclosureState(roles, Boolean(disclosure && disclosure.visible));
}

function classifyDisclosureState(roles, disclosureVisible) {
  const labels = partyRoleLabels(roles);
  if (labels.includes('signatory')) {
    return { kind: 'signatory', label: 'signatory', title: 'DPM reports this party as a signatory on this contract.' };
  }
  if (labels.includes('observer')) {
    return { kind: 'observer', label: 'observer', title: 'DPM reports this party as an observer on this contract.' };
  }
  if (labels.includes('divulged')) {
    return { kind: 'divulged', label: 'divulged', title: 'DPM reports this contract as divulged to this party.' };
  }
  if (labels.includes('controller')) {
    return { kind: 'controller', label: 'controller', title: 'Party can act as a controller for a visible action.' };
  }
  if (labels.includes('witness')) {
    return { kind: 'witness', label: 'witness', title: 'DPM reports this party as a witness for this contract.' };
  }
  if (labels.includes('disclosed') || disclosureVisible) {
    return { kind: 'disclosed', label: 'disclosed', title: 'DPM reports this party can see this contract, without marking it as divulgence.' };
  }
  return { kind: 'hidden', label: '-', title: 'No visibility reported by DPM.' };
}

function classifySeverity(text) {
  const value = String(text || '').toLowerCase();
  if (value.includes('error') || value.includes('failed') || value.includes('exception')) return 'error';
  if (value.includes('warn')) return 'warning';
  if (value.includes('debug') || value.includes('trace')) return 'debug';
  return 'info';
}

function stableColorIndex(text) {
  let hash = 0;
  for (const ch of String(text || '')) hash = ((hash * 31) + ch.charCodeAt(0)) & 0xffff;
  return Math.abs(hash % 6);
}

function byteSize(text) {
  return new Blob([String(text || '')]).size + ' bytes';
}

function formatDuration(milliseconds) {
  const seconds = Math.floor(milliseconds / 1000);
  if (seconds < 60) return seconds + 's';
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return minutes + 'm ' + String(remainder).padStart(2, '0') + 's';
}

function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).catch(() => fallbackCopy(text));
  } else {
    fallbackCopy(text);
  }
}

function fallbackCopy(text) {
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', 'readonly');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  try { document.execCommand('copy'); } catch (_) {}
  area.remove();
}

function el(tag, attrs, children) {
  const node = document.createElement(tag);
  const attributes = attrs || {};
  Object.keys(attributes).forEach(key => {
    const value = attributes[key];
    if (value == null) return;
    if (key === 'className') node.className = value;
    else if (key === 'onclick') node.addEventListener('click', value);
    else if (key === 'onkeydown') node.addEventListener('keydown', value);
    else if (key === 'open') node.open = Boolean(value);
    else if (key === 'tabIndex') node.tabIndex = Number(value);
    else if (key === 'type') node.type = value;
    else node.setAttribute(key, value);
  });
  appendChildren(node, children);
  return node;
}

function appendChildren(node, children) {
  const list = Array.isArray(children) ? children : [children];
  for (const child of list) {
    if (child == null || child === false) continue;
    if (Array.isArray(child)) appendChildren(node, child);
    else if (child instanceof Node) node.appendChild(child);
    else node.appendChild(document.createTextNode(String(child)));
  }
}

if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  window.addEventListener('message', function (event) {
    const message = event.data || {};

    function showOrHideClassWithName(show, showClass, hideClass, checkBoxId) {
      document.body.classList.remove(show ? hideClass : showClass);
      document.body.classList.add(show ? showClass : hideClass);
      const checkbox = document.getElementById(checkBoxId);
      if (checkbox) checkbox.checked = show;
    }

    switch (message.command) {
      case 'set_html':
        setHtmlContent(message.value);
        break;
      case 'add_note':
        addConsoleNote(message.value);
        break;
      case 'set_progress':
        setProgress(message.value);
        break;
      case 'set_view': {
        const value = message.value || {};
        state.selectedView = normalizeView(value.selected);
        state.showArchived = Boolean(value.showArchived);
        state.showDetailedDisclosure = Boolean(value.showDetailedDisclosure);
        setIdeTheme(value.theme);
        showOrHideClassWithName(state.showArchived, 'show_archived', 'hide_archived', 'show_archived');
        showOrHideClassWithName(state.showDetailedDisclosure, 'show_disclosure', 'hidden_disclosure', 'show_detailed_disclosure');
        render();
        break;
      }
    }
  });

  document.addEventListener('click', function (event) {
    const target = event.target && event.target.closest ? event.target.closest('a[href]') : null;
    if (!target) return;
    const href = target.getAttribute('href') || '';
    if (!href.startsWith('command:daml.revealLocation')) return;
    event.preventDefault();
    revealLocation(href);
  });

  document.addEventListener('keydown', function (event) {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'f') {
      const input = document.getElementById('search_input');
      if (input) {
        event.preventDefault();
        input.focus();
        input.select();
      }
    }
    if (!event.metaKey && !event.ctrlKey && event.altKey && /^[1-6]$/.test(event.key)) {
      selectView(VIEWS[Number(event.key) - 1].id);
    }
  });

  function initializeExplorer() {
    document.getElementById('show_archived').addEventListener('change', toggleArchived);
    document.getElementById('show_detailed_disclosure').addEventListener('change', toggleDetailedDisclosure);
    document.getElementById('search_input').addEventListener('input', updateSearch);
    render();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeExplorer);
  } else {
    initializeExplorer();
  }
}

if (typeof module !== 'undefined') {
  module.exports = {
    normalizeView,
    shortId,
    classifySeverity,
    parseTemplateTitle,
    transactionIdFromContractId,
    roleList,
    formatDuration,
    isDisclosureHeader,
    contractsFromHeadingAndTable,
    partiesForContract,
    disclosurePartiesForContracts,
    looksLikePartyValue,
    partyRoleLabels,
    roleDescription,
    classifyDisclosureState,
    synthesizeTransactionsFromContracts,
    transactionEventFromContract,
    transactionEventsFromText,
    damlTransactionsFromText,
    inferTransactionEvent,
    partiesWithRole,
    eventPrimaryActors,
    eventRoleRows,
    mergeTransactionGroups,
    flattenEvents,
    filterTransactionEventsForDisplay,
    isArchivedTransactionEvent,
    shortPartyName,
    consoleLinesFromTransactionTraceText,
    normalizeConsoleText,
    looksLikeConsoleLine,
    mergeConsoleEntries
  };
}
