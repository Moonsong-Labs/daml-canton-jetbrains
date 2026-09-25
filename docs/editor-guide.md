# DAML editor

The compiler supplies diagnostics, completion, inferred-type hover and definition navigation. The plugin supplies a shared, cached lexical symbol model for local highlighting, structure, references, refactoring and navigation when the compiler does not implement those features.

## Use the editor

- Function calls and references, including imported functions passed to `map`, use **Settings → Editor → Color Scheme → DAML → Names → Function call**. Light and dark schemes have distinct blue defaults.
- **Go to Declaration** / Cmd+B follows local bindings, imports and selected dependency sources. Ambiguous imports show multiple targets; unknown receivers fall through to compiler navigation.
- **Go to Symbol** searches indexed declarations. **Structure** nests fields, constructors and choices under their owners and merges signatures with definitions.
- **Find Usages** uses indexed code occurrences and resolved symbol identity. Comments and string literals are excluded. Hidden files/directories (including `.daml` at any depth), generated folders (`build`, `out`, `node_modules`) and archive contents are excluded even with **All Places** or an explicit local scope. Rename and usage lenses use the same source boundary.
- **Rename** opens a preview and checks scope collisions and name capture. Functions, values, parameters, types, constructors, templates and choices work locally. Fields and interface methods additionally consult the running compiler for unresolved receivers; incomplete resolution stops the operation before editing. Expand record puns into explicit assignments before renaming a field.
- **Go to Implementation** and gutter icons connect interfaces to templates and methods to their implementations. **Go to Type Declaration** supports simple explicit annotations; inferred types remain compiler-owned.
- **Hover / Quick Documentation** works on declarations and unambiguous local/imported references, even without the server. It includes leading line or Haddock block documentation, field documentation, owner and package provenance. Templates show their input fields and creation result (`ContractId Template`); choices show their inputs, consumption mode and declared return type. Functions and methods show explicit input/output types, including multiline and higher-order signatures; simple parameter names come from the definition. Complex patterns use positional labels. Unknown/inferred types and receiver-dependent references remain compiler-owned; compiler documentation stays available alongside native source documentation.
- Folding, matching brackets and structural selection work while the server is unavailable.
- Enable literal argument labels in **Settings → Editor → Inlay Hints → DAML**. They are off by default and appear only for unambiguous applications with simple literal arguments.
- Enable optional usage lenses with **Tools → Show DAML Usage Counts**. Counts represent resolved code references, not text matches. Receiver-dependent field/method counts are omitted.
- **Tools → DAML Editor Status** displays the current package/SDK, language-server state and negotiated capabilities. A semantic-token legend alone does not count as working semantic tokens. A compiler version is shown only if the server reports it.

## Packages and editing

Navigation uses the current `daml.yaml` source root and the exact DAR paths in `dependencies` / `data-dependencies`. DAR source stays read-only and retains its archive/version identity. Unrelated sibling repositories and generated package-database copies are not substituted by module name. A DAR without source cannot provide native source navigation; compiler-generated dependency definitions may still be available.

`multi-package.yaml`, `daml.yaml`, DAR replacement and package-directory changes trigger a debounced server restart. The current public JetBrains LSP API restarts the DAML provider within the project, so this can restart several package servers. Each compiler is limited to two RTS capabilities instead of claiming every CPU. A project-wide `multi-ide` migration is not required for these editor features.

The local model tolerates incomplete source and uses lexical layout, not a second Daml type checker. It deliberately leaves type-dependent or ambiguous references unresolved. Advanced patterns, re-exports, generated accessors and method implementations still need compiler validation before a broad refactoring; preview the resulting edits and run `dpm build`.

## Local acceptance

Build and run the test suite against WebStorm:

```sh
./gradlew test verifyPluginStructure buildPlugin
```

Install `build/distributions/daml-canton-jetbrains-<version>.zip` through **Plugins → Install Plugin from Disk**. Restart WebStorm if requested, then open a project with a working SDK.

Check Cmd+B and Find Usages for a function, choice and shadowed local; rename a parameter and an imported function; open Structure and Go to Symbol; follow an interface implementation gutter; hover a documented template, choice and imported function and check inputs/returns; inspect dependency provenance; edit an import and confirm results update. Stop the server and confirm highlighting and local navigation still work. Use a dark and a light/custom editor scheme to confirm DAML styles inherit theme attributes.
