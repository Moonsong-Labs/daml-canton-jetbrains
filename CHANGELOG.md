# Changelog

## 0.4.0

- Refined the Script Results Tx Tree view with clearer transaction action, decoded event, and party-role sections.
- Kept long party identifiers compact in event rows while preserving full values in detail panels.
- Made Tx Tree `Archived` and `Detailed disclosure` controls affect the rendered transaction tree.

## 0.3.0

- Added the Managed Canton Sandboxes Network and Explorer views for local multi-participant Canton profiles.
- Added editable topology diagrams, local Canton generation/launching, endpoint consoles, and ledger activity exploration.
- Improved DAML navigation, choice usages, local binding resolution, and native highlighting.
- Reduced generated sandbox output to the files required for reproducible local runs.

## 0.2.0

- Redesigned DAML Script Results as an explorer with Overview, Contracts, Tx Tree, Disclosure, Console, and Raw views.
- Improved DAML highlighting with configurable color settings, richer lexer tokens, and local DAML-aware annotations.
- Added better local SDK/DPM configuration, terminal PATH integration, and clearer runtime validation.
- Improved DAML navigation for module imports and explicit imported symbols.

## 0.1.0

- Initial release with file type registration, LSP wiring around `damlc ide`, Script Results, snippets, `daml.yaml` schema, and settings.
