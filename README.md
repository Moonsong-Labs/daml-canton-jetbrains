# DAML JetBrains Plugin

A JetBrains plugin for writing, running, and debugging DAML applications and local Canton networks.

## What it does

- DAML syntax highlighting, diagnostics, completion, hover, navigation, symbols, and rename.
- Script execution with contracts, transaction trees, disclosure, console, and raw results.
- Run configurations for DAML build, test, script, and start commands.
- Schema completion for `daml.yaml` and `multi-package.yaml`.
- Canton config and script support with managed local network profiles.
- Ledger activity explorer and participant endpoint console.

## Quick start

Requires GoLand 2026.1.2+, JDK 21, and `dpm` or the DAML assistant. Install Canton to use local network tools.

1. Build the plugin:

   ```bash
   ./gradlew buildPlugin
   ```

2. Open **Settings / Preferences -> Plugins -> Gear -> Install Plugin from Disk...**.
3. Select `build/distributions/daml-canton-jetbrains-<version>.zip`.
4. Open a DAML project, then validate or install the SDK under **Settings -> Languages & Frameworks -> DAML**.

## Feature Tour

### Write DAML

Edit `.daml` files with native highlighting and language-server features.

<img src="docs/images/daml-editor.png" alt="A DAML source file open in the JetBrains editor with native syntax highlighting and line numbers" width="900">

### Inspect Script Results

Run a `Script` from the editor and inspect its contracts and transactions. Shown here: the real [`testTransferWithSplit`](https://github.com/Moonsong-Labs/canton-apps/blob/ca0ede4441af0b93c653235c2c035f5bde6a77d8/lunar-dollar/daml/Tests/Tests.daml#L34-L56) flow.

<img src="docs/images/daml-script-results.png" alt="DAML Script Results showing the nested transaction tree for a real Lunar Dollar split transfer" width="900">

### Run local Canton networks

Create multi-participant profiles, assign DARs and parties, connect sync domains, and inspect the topology.

<img src="docs/images/managed-canton-network.png" alt="A sample three-participant Canton network in the Managed Canton Sandboxes topology view" width="900">

### Explore ledger activity

Inspect contracts, transactions, parties, synchronizers, raw JSON, and participant activity.

<img src="docs/images/ledger-explorer.png" alt="The Canton ledger explorer showing sample contract activity, contract details, and a network timeline" width="900">
