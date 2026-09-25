# Local simulator and script inspection

Canton **3.4.11** is the acceptance baseline. The Network profile settings show the version actually resolved at startup. Other runtime versions are not covered by this acceptance run.

## Create and run a network

1. Open **Managed Canton Sandboxes → Network → More → New profile**. Use **Profile settings** to name it and review runtime details.
2. Add **Participant** and **Synchronizer** nodes in the topology sidebar or More menu. New nodes receive independent IDs and unused ports. Ordinary edits preserve existing port assignments.
3. Open **Packages & Parties**, assign a compiled DAR to the participants that need it, and allocate party hints on connected participant/synchronizer pairs.
4. Choose **Validate profile** to inspect problems, then **Start**. Startup shows validation and readiness progress; **Cancel start** cancels readiness and stops the owned process.
5. Select a node to inspect its configuration and use **Endpoint tools**. Requests are enabled only for that profile's running network. Party selection uses full IDs discovered from the participant, with locally hosted parties selected by default.

One network runs per project in one JVM, with in-memory storage. Stopping and restarting resets ledger data. A stop failure retains ownership and blocks a replacement process until termination is confirmed.

## Edit a running network

The default canvas displays the launched topology. Choose **Edit draft** to prepare configuration changes. The pending-change indicator distinguishes these edits from the running network. Changes take effect through **Restart with changes**, which explains the memory-ledger reset before proceeding.

Moving graph nodes is a presentation change and does not require restart. Configured connections and the inspector's observed synchronizer connections describe different things: the former is the launch configuration; the latter comes from the participant's live API.

**Preview rebase…** in profile settings shows every proposed endpoint change before applying it to the draft. Changing the base alone does not silently renumber existing nodes.

Deleting a running profile is blocked. Deleting an imported profile dismisses its discovery entry and preserves generated files. **Generate files** writes configuration without changing runtime status. **Clean owned runtime logs** only removes log directories claimed by the generated-file manifest; unmarked legacy directories remain untouched.

## Explore the ledger

Select a participant and Refresh in **Explorer**. **Active Contracts** is a current ACS snapshot. **History** is separately paginated: its coverage label shows the participant-local offset reached, and **Load More** continues from there. Refresh continues from the saved history position. An archive beyond the current history page cannot make a contract reappear in Active.

Large ACS snapshots use the finite JSON API WebSocket stream when Canton's bounded HTTP response is too large. A partial or failed stream is never shown as a complete ACS. History errors leave a successfully fetched ACS visible. Assignment, unassignment, and in-flight reassignment rows retain their source and target synchronizer IDs.

Offsets belong to the selected participant; they are not a network-wide sequence. A restarted runtime clears offset state. Search and filters affect displayed rows, while party selection determines the query's visibility. Select a row for details; raw responses and the optional timeline are diagnostic views.

Endpoint request drafts are remembered by profile, participant, and preset. Logs and health updates do not rewrite a draft. Switching runtime invalidates previous offsets and callbacks. Each new command submission gets a fresh command ID.

## IDE Script Results and CLI Run Script

**IDE Script Results** uses the existing Daml language-server execution model. Place the caret in a script and use the gutter action or Tools menu. If no script is selected, choose from the discovered declarations. It does not automatically submit to the managed network.

The result view provides Overview, Contracts, Transactions, Disclosure, and Console. Transaction events start compact; expand an event for payload, roles, disclosure, and complete identifiers. Raw source markup is under **Advanced**. **Open source** returns to the script declaration.

**CLI Run Script** is a separate run configuration. Choose a workspace, compiled DAR, and qualified `Module:script`; discover scripts from a selected source file. Connection arguments and the command preview are under **Advanced arguments and command preview**. CLI output appears in the IDE Run console.

Canton **Script** mode invokes `run <script>`. Canton **Configuration** mode passes `--config <file>`; bootstrap arguments remain separate.

## Portable profiles

Generated `profile.json` files use schema version 2. Workspace, generated-output, and DAR paths are relative to the profile file location. Keep those relative relationships when moving an export. Existing project-relative settings and legacy nested-workspace exports are resolved on import without rewriting the source file during discovery. Unknown future schema versions are rejected.

## Validation commands

Use JDK 21 for the Gradle build and the pinned Canton/DPM runtime for acceptance:

```bash
./gradlew test
node src/test/webview/webview-model-test.js
./gradlew verifyPluginProjectConfiguration verifyPluginStructure buildPlugin
```

Build the checked-in real DAR fixture from `src/test/resources/sandbox/acceptance` with `dpm build`, then return to the repository root and run:

```bash
CANTON_JAR=/path/to/3.4.11/canton.jar ./gradlew cantonIntegrationTest
```

The acceptance task fails if the pinned runtime or DAR is missing. It does not silently skip. Reports are written to `build/reports/canton-acceptance` and `build/reports/tests/cantonIntegrationTest`. The older Docker smoke test remains optional and is separate from this required native Canton fixture.
