const assert = require('assert');
const webview = require('../../main/resources/webview/webview.js');

assert.strictEqual(webview.normalizeView('table'), 'contracts');
assert.strictEqual(webview.normalizeView('transaction'), 'txTree');
assert.strictEqual(webview.normalizeView('console'), 'console');
assert.strictEqual(webview.normalizeView('unexpected'), 'overview');

const template = webview.parseTemplateTitle(
  'SimpleVault.Impl.SimplePricer:SimplePricer@334001223b63671e1795e08c5e0c8b9e'
);
assert.strictEqual(template.template, 'SimpleVault.Impl.SimplePricer:SimplePricer');
assert.strictEqual(template.shortName, 'SimplePricer');
assert.strictEqual(template.packageId, '334001223b63671e1795e08c5e0c8b9e');

assert.strictEqual(webview.transactionIdFromContractId('#12:0'), '12');
assert.strictEqual(webview.shortId('#12345678901234567890:0'), '#123456789...567890:0');
assert.strictEqual(webview.shortPartyName('Alice'), 'Alice');
assert.strictEqual(webview.shortPartyName('deposit-operator-accept-d4d95138'), 'deposit-operator-acc...d4d95138');
assert.deepStrictEqual(webview.roleList('controller signatory observer witness'), [
  'controller',
  'signatory',
  'observer',
  'witness'
]);
// Disclosure cells concatenate a one-letter glyph with the tooltip word ("D" + "Divulged");
// roleList must still resolve that to the 'divulged' role rather than a junk 'ddivulged' label.
assert.deepStrictEqual(webview.roleList('DDivulged'), ['divulged']);
assert.deepStrictEqual(webview.roleList('Divulged'), ['divulged']);
assert.strictEqual(webview.classifySeverity('script failed with exception'), 'error');
assert.strictEqual(webview.classifySeverity('warn: missing party'), 'warning');
assert.strictEqual(webview.classifySeverity('trace details'), 'debug');
assert.strictEqual(webview.classifySeverity('script completed'), 'info');
assert.strictEqual(webview.formatDuration(3400), '3s');
assert.strictEqual(webview.formatDuration(61000), '1m 01s');

assert.strictEqual(webview.isDisclosureHeader('depositConfigCid'), false);
assert.strictEqual(webview.isDisclosureHeader('operator'), false);
assert.strictEqual(webview.isDisclosureHeader('operator-9b3970be'), false);
assert.strictEqual(webview.looksLikePartyValue('#3:0'), false);
assert.strictEqual(webview.looksLikePartyValue('"v1"'), false);
assert.deepStrictEqual(webview.partyRoleLabels(['signatory']), ['signatory']);
assert.deepStrictEqual(webview.partyRoleLabels(['observer']), ['observer']);
assert.deepStrictEqual(webview.partyRoleLabels(['visible']), ['disclosed']);
// Regression: partyChip used as a bare .map callback receives the array index as `roles`;
// partyRoleLabels must tolerate a non-array argument instead of throwing and blanking the Tx Tree.
assert.deepStrictEqual(webview.partyRoleLabels(1), []);
assert.deepStrictEqual(webview.partyRoleLabels(undefined), []);
assert.strictEqual(webview.classifyDisclosureState(['signatory'], true).kind, 'signatory');
assert.strictEqual(webview.classifyDisclosureState(['observer'], true).kind, 'observer');
assert.strictEqual(webview.classifyDisclosureState(['visible'], true).kind, 'disclosed');
assert.strictEqual(webview.classifyDisclosureState(['divulged'], true).kind, 'divulged');
assert.strictEqual(webview.classifyDisclosureState(['witness'], true).kind, 'witness');
assert.strictEqual(webview.classifyDisclosureState(['operator'], false).kind, 'hidden');
assert.strictEqual(webview.classifyDisclosureState([], false).kind, 'hidden');
assert.deepStrictEqual(webview.partiesForContract([
  { party: 'operator-9b3970be', visible: true, detail: 'Signatory' },
  { party: 'public-40b5f04b', visible: true, detail: 'Divulged' },
  { party: 'vaultIssuer-d4d95138', visible: false, detail: '' }
]), [
  { name: 'operator-9b3970be', roles: ['signatory'] },
  { name: 'public-40b5f04b', roles: ['divulged'] }
]);
assert.deepStrictEqual(webview.partiesWithRole([
  { name: 'deposit-compliance-40bcf2e7', roles: ['signatory'] },
  { name: 'deposit-lender-49310e6c', roles: ['observer'] },
  { name: 'deposit-operator-901d787a', roles: ['observer'] }
], 'signatory').map(party => party.name), ['deposit-compliance-40bcf2e7']);
assert.deepStrictEqual(webview.eventRoleRows({
  kind: 'Create',
  parties: [
    { name: 'deposit-operator-accept-d4d95138', roles: ['signatory'] },
    { name: 'deposit-compliance-accept-9b3970be', roles: ['observer'] }
  ]
}).map(row => [row.role, row.parties.map(party => party.name)]), [
  ['signatory', ['deposit-operator-accept-d4d95138']],
  ['observer', ['deposit-compliance-accept-9b3970be']]
]);
assert.deepStrictEqual(webview.eventRoleRows({
  kind: 'Exercise',
  actors: [{ name: 'deposit-operator-accept-d4d95138', roles: ['controller'] }],
  parties: [{ name: 'deposit-compliance-accept-9b3970be', roles: ['observer'] }]
}).map(row => [row.role, row.parties.map(party => party.name)]), [
  ['controller', ['deposit-operator-accept-d4d95138']]
]);
assert.deepStrictEqual(webview.eventRoleRows({
  kind: 'Fetch',
  parties: [
    { name: 'deposit-auditor-31cc7e22', roles: ['witness'] },
    { name: 'deposit-operator-accept-d4d95138', roles: ['signatory'] }
  ]
}).map(row => [row.role, row.parties.map(party => party.name)]), [
  ['witness', ['deposit-auditor-31cc7e22']],
  ['signatory', ['deposit-operator-accept-d4d95138']]
]);
const archivedContractsById = new Map([
  ['#2:0', { id: '#2:0', archived: true }],
  ['#3:0', { id: '#3:0', archived: false }]
]);
assert.strictEqual(webview.isArchivedTransactionEvent({ kind: 'Archive', contractId: '#1:0' }, archivedContractsById), true);
assert.strictEqual(webview.isArchivedTransactionEvent({ kind: 'Archived/Result', contractId: '#2:0' }, archivedContractsById), true);
assert.strictEqual(webview.isArchivedTransactionEvent({ kind: 'Create', source: 'contract', contractId: '#2:0' }, archivedContractsById), true);
assert.strictEqual(webview.isArchivedTransactionEvent({ kind: 'Exercise', contractId: '#2:0' }, archivedContractsById), false);
const archivedFilterEvents = [
  {
    kind: 'Exercise',
    label: 'AcceptDeposit',
    contractId: '#3:0',
    children: [
      { kind: 'Archive', label: 'Archive #1:0', contractId: '#1:0' },
      { kind: 'Create', label: 'FollowUp', contractId: '#3:0', parties: [{ name: 'deposit-lender-49310e6c', roles: ['observer'] }] }
    ]
  },
  { kind: 'Archived/Result', label: 'OldContract', source: 'contract', contractId: '#2:0' },
  { kind: 'Create', label: 'VisibleContract', contractId: '#3:0' }
];
const withoutArchivedEvents = webview.filterTransactionEventsForDisplay(archivedFilterEvents, {
  contractsById: archivedContractsById,
  query: '',
  showArchived: false
});
assert.strictEqual(withoutArchivedEvents.length, 2);
assert.deepStrictEqual(withoutArchivedEvents[0].children.map(event => event.kind), ['Create']);
const withArchivedEvents = webview.filterTransactionEventsForDisplay(archivedFilterEvents, {
  contractsById: archivedContractsById,
  query: '',
  showArchived: true
});
assert.strictEqual(withArchivedEvents.length, 3);
assert.deepStrictEqual(withArchivedEvents[0].children.map(event => event.kind), ['Archive', 'Create']);
assert.strictEqual(webview.filterTransactionEventsForDisplay(archivedFilterEvents, {
  contractsById: archivedContractsById,
  query: 'deposit-lender-49310e6c',
  showArchived: false
}).length, 1);
const createFromContract = webview.transactionEventFromContract({
  id: '#1:1',
  archived: false,
  templateShort: 'KYCAttestation',
  template: 'Vault.Component.IKYCPolicy:KYCAttestation',
  parties: [
    { name: 'deposit-compliance-40bcf2e7', roles: ['signatory'] },
    { name: 'deposit-lender-49310e6c', roles: ['observer'] }
  ],
  fields: []
});
assert.deepStrictEqual(createFromContract.actors.map(party => party.name), ['deposit-compliance-40bcf2e7']);
assert.deepStrictEqual(webview.eventPrimaryActors({
  kind: 'Exercise',
  actors: [{ name: 'deposit-compliance-40bcf2e7', roles: ['controller'] }],
  parties: [{ name: 'deposit-lender-49310e6c', roles: ['observer'] }]
}).map(party => party.name), ['deposit-compliance-40bcf2e7']);
assert.deepStrictEqual(webview.disclosurePartiesForContracts([
  {
    fields: [{ name: 'public', value: "'public-40b5f04b'" }],
    disclosures: [{ party: 'operator-9b3970be', visible: true, detail: 'Signatory' }]
  },
  {
    fields: [{ name: 'vaultIssuer', value: "'vaultIssuer-d4d95138'" }],
    disclosures: [
      { party: 'valuationAgent-4f1df03a', visible: true, detail: 'Witness' },
      { party: 'vaultIssuer-d4d95138', visible: false, detail: '' }
    ]
  }
]), ['operator-9b3970be', 'valuationAgent-4f1df03a', 'vaultIssuer-d4d95138']);

function fakeClassList(classes) {
  return { contains: name => classes.includes(name) };
}

function fakeCell(text, classes, tooltip) {
  return {
    textContent: text,
    classList: fakeClassList(classes || []),
    querySelector: selector => selector === '.tooltiptext' && tooltip ? { textContent: tooltip } : null
  };
}

function fakeRow(cells, classes) {
  return {
    classList: fakeClassList(classes || []),
    querySelectorAll: () => cells
  };
}

const multiRowContracts = webview.contractsFromHeadingAndTable(
  { textContent: 'Vault.Holding:Holding@pkg', outerHTML: '<h1>Vault.Holding:Holding@pkg</h1>', querySelector: () => null },
  {
    classList: fakeClassList([]),
    outerHTML: '<table></table>',
    querySelector: () => null,
    querySelectorAll: () => [
      fakeRow([
        fakeCell('id'), fakeCell('status'), fakeCell('issuer'),
        fakeCell('operator-9b3970be', ['observer']),
        fakeCell('public-40b5f04b', ['observer'])
      ]),
      fakeRow([
        fakeCell('#11:0'), fakeCell('active'), fakeCell("'operator-9b3970be'"),
        fakeCell('SSignatory', ['disclosure', 'disclosed'], 'Signatory'),
        fakeCell('DDivulged', ['disclosure', 'disclosed'], 'Divulged')
      ], ['active']),
      fakeRow([
        fakeCell('#12:0'), fakeCell('active'), fakeCell("'operator-9b3970be'"),
        fakeCell('SSignatory', ['disclosure', 'disclosed'], 'Signatory'),
        fakeCell('-', ['disclosure'])
      ], ['active'])
    ]
  },
  1
);
assert.strictEqual(multiRowContracts.length, 2);
assert.deepStrictEqual(multiRowContracts.map(contract => contract.id), ['#11:0', '#12:0']);
assert.strictEqual(multiRowContracts[0].disclosures.find(disclosure => disclosure.party === 'public-40b5f04b').detail, 'Divulged');
assert.strictEqual(multiRowContracts[1].disclosures.find(disclosure => disclosure.party === 'public-40b5f04b').visible, false);
assert.strictEqual(webview.synthesizeTransactionsFromContracts([
  { id: '#1:0', transactionId: '1', archived: false, templateShort: 'A', template: 'M:A', parties: [], fields: [] },
  { id: '#2:0', transactionId: '2', archived: true, templateShort: 'B', template: 'M:B', parties: [], fields: [] }
]).length, 2);
const txEvents = webview.transactionEventsFromText(`
Transaction #1
create SimplePricer #1:0
exercise Archive on #1:0
fetch SimpleProcessor #2:0
`, '1', new Map([
  ['#1:0', { id: '#1:0', templateShort: 'SimplePricer', template: 'M:SimplePricer', parties: [], fields: [{ name: 'operator', value: "'alice'" }] }]
]));
assert.strictEqual(txEvents.length, 3);
assert.strictEqual(txEvents[0].kind, 'Create');
assert.strictEqual(txEvents[0].label, 'SimplePricer');
assert.strictEqual(txEvents[0].fields.length, 1);
assert.strictEqual(txEvents[1].kind, 'Archive');
assert.strictEqual(txEvents[1].contractId, '#1:0');
assert.strictEqual(txEvents[2].kind, 'Fetch');
const mergedTransactions = webview.mergeTransactionGroups(
  [{ id: '1', label: 'Transaction #1', events: [{ kind: 'Create', label: 'A', contractId: '#1:0' }], rawHtml: '<div></div>' }],
  [{ id: '1', label: 'Transaction #1', events: [{ kind: 'Create', label: 'A', contractId: '#1:0' }, { kind: 'Create', label: 'B', contractId: '#2:0' }] }]
);
assert.strictEqual(mergedTransactions.length, 1);
assert.strictEqual(mergedTransactions[0].events.length, 2);
assert.strictEqual(webview.flattenEvents([{ kind: 'Create', children: [{ kind: 'Exercise' }] }]).length, 2);
const damlTxs = webview.damlTransactionsFromText(`
Transactions:
  TX 6 1970-01-01T00:00:00Z (Tests.VaultTest:40:15)
  #6:0
  └─> 'operator-9b3970be' and 'vaultIssuer-d4d95138' exercises CreateVault on #5:0 (Vault.Factory:VaultFactory@pkg)
      with
        operator = 'operator-9b3970be'; public = 'public-1df42503'
      children:
      #6:1
      └─> 'operator-9b3970be' fetches #4:0 (Vault.Vault:VaultConfig@pkg)

      #6:2
      └─> 'operator-9b3970be' and 'vaultIssuer-d4d95138' create Vault.Vault:Vault@pkg
          with
            vaultIssuer = 'vaultIssuer-d4d95138'; operator = 'operator-9b3970be'

  TX 8 1970-01-01T00:00:00Z (Tests.VaultTest:52:17)
  #8:0
  └─> 'operator-9b3970be' exercises AcceptDeposit on #7:2 (Vault.Deposit.Workflow:DepositRequest@pkg)
`, new Map([
  ['#5:0', { id: '#5:0', templateShort: 'VaultFactory', template: 'Vault.Factory:VaultFactory', parties: [], fields: [] }],
  ['#4:0', { id: '#4:0', templateShort: 'VaultConfig', template: 'Vault.Vault:VaultConfig', parties: [], fields: [] }],
  ['#6:2', { id: '#6:2', templateShort: 'Vault', template: 'Vault.Vault:Vault', parties: [], fields: [{ name: 'operator', value: "'operator-9b3970be'" }] }],
  ['#7:2', { id: '#7:2', templateShort: 'DepositRequest', template: 'Vault.Deposit.Workflow:DepositRequest', parties: [], fields: [] }]
]));
assert.strictEqual(damlTxs.length, 2);
assert.strictEqual(damlTxs[0].id, '6');
assert.strictEqual(damlTxs[0].events.length, 1);
assert.strictEqual(damlTxs[0].events[0].kind, 'Exercise');
assert.strictEqual(damlTxs[0].events[0].label, 'CreateVault');
assert.strictEqual(damlTxs[0].events[0].contractId, '#5:0');
assert.deepStrictEqual(damlTxs[0].events[0].actors.map(party => party.name), [
  'operator-9b3970be',
  'vaultIssuer-d4d95138'
]);
assert.strictEqual(damlTxs[0].events[0].children.length, 2);
assert.strictEqual(damlTxs[0].events[0].children[0].kind, 'Fetch');
assert.strictEqual(damlTxs[0].events[0].children[1].kind, 'Create');
assert.strictEqual(damlTxs[0].events[0].children[1].contractId, '#6:2');
assert.strictEqual(damlTxs[1].id, '8');
assert.strictEqual(damlTxs[1].events[0].label, 'AcceptDeposit');
assert.deepStrictEqual(webview.consoleLinesFromTransactionTraceText(`
Transactions:
  TX 8 1970-01-01T00:00:00Z
Return value: {}
Trace:
  "[Accept] start"
  "[Validator] amount=100.0"
  "[Test] OK (flow + privacy)"
`), [
  '[Accept] start',
  '[Validator] amount=100.0',
  '[Test] OK (flow + privacy)'
]);
assert.strictEqual(webview.looksLikeConsoleLine('TRACE: [Test] OK (flow + privacy)'), true);
assert.strictEqual(webview.looksLikeConsoleLine('[Test] OK (flow + privacy)'), true);
assert.strictEqual(webview.looksLikeConsoleLine('SimpleVault.Impl.SimplePricer:SimplePricer'), false);
assert.strictEqual(webview.normalizeConsoleText('TRACE: "[Test] OK (flow + privacy)"'), '[test] ok (flow + privacy)');
assert.strictEqual(webview.mergeConsoleEntries(
  [{ text: 'TRACE: [Test] OK (flow + privacy)', severity: 'debug' }],
  [{ text: '[Test] OK (flow + privacy)', severity: 'debug' }]
).length, 1);
