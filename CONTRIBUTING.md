# Contributing

## Development Setup

1. Install JDK 21.
2. Install GoLand 2026.1 or newer for local IDE testing.
3. Install `dpm` or the DAML assistant if you need runtime-backed behavior.
4. Run:

   ```bash
   ./gradlew --no-daemon --no-configuration-cache test
   ./gradlew --no-daemon --no-configuration-cache buildPlugin
   ```

## Pull Requests

- Keep changes scoped to one behavior or workflow.
- Add or update tests for parser, rendering, LSP, runtime, or UI behavior changes.
- For UI changes, include a short manual smoke-test note with the IDE version and plugin ZIP tested.
- Do not commit `build/`, `.gradle/`, `.intellijPlatform/`, `.canton-sandboxes/`, or generated plugin ZIP files.

## Optional Integration Tests

Docker-backed managed Canton sandbox checks are disabled by default. Enable them explicitly:

```bash
./gradlew --no-daemon --no-configuration-cache dockerIntegrationTest -PrunDockerIntegration=true
```
