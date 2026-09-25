# Script Results tests

Run the model tests and Chromium integration tests from the repository root:

```sh
npm --prefix src/test/webview ci
cd src/test/webview
npx playwright install chromium
npm test
```

Set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` only when using an existing compatible Chromium installation. Release CI installs the pinned Playwright browser and runs these tests before packaging.

The browser tests load the actual webview shell, JavaScript, CSS, HTML parser, and controls. They compare every disclosure cell against the checked-in language-server output and exercise filtering, neutral/detailed presentation, host preference messages, and result/note replacement. They do not replace native JCEF acceptance.

## Regenerate the real fixture

With Python 3, DPM, SDK 3.5.7, and a compatible Java runtime available:

```sh
python3 src/test/webview/capture-disclosure-fixture.py
```

The capture script copies `src/test/resources/webview/disclosure-project` into a temporary directory, executes `Main.disclosureFixture` through `dpm damlc ide`, and writes `src/test/resources/webview/fixtures/disclosure-real.html`. No ledger is contacted. Source paths are normalized; party identifiers and server HTML remain intact.

The fixture covers S/O/W/D, hidden parties, archived contracts, and generated identifiers containing spaces or starting with digits/underscores. W is produced by creating an Asset inside a choice visible to a non-stakeholder; D is produced by fetching a pre-existing Asset inside that choice.
