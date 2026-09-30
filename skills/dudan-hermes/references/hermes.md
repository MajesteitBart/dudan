# Install and maintain Hermes

Check the [official installation guide](https://hermes-agent.nousresearch.com/docs/getting-started/installation/) before a fresh install. Run the installer as the intended Hermes service user.

Linux, macOS or WSL2:

```sh
curl -fsSL https://hermes-agent.nousresearch.com/install.sh -o /tmp/hermes-install.sh
bash /tmp/hermes-install.sh
```

Native Windows, PowerShell:

```powershell
$hermesInstaller = Join-Path $env:TEMP 'hermes-install.ps1'
Invoke-WebRequest https://hermes-agent.nousresearch.com/install.ps1 -OutFile $hermesInstaller
& $hermesInstaller
```

Inspect downloaded scripts before execution. The official installers manage the runtime; avoid guessing a system Python version or installing dependencies into an arbitrary venv. Reload the shell, run `hermes setup` or `hermes model` to configure the user's provider, then `hermes doctor` and a small chat. Provider credentials differ from the API server key that dudan uses. Let the user complete interactive authentication when necessary.

Use `hermes gateway --help` to choose the supported persistent-service install/start flow on the host. `restart` assumes a gateway already exists. Verify the gateway uses the intended launcher, service user and configuration. Linux with systemd is the documented path for dudan's upload unit; Windows/macOS hosts need equivalent service management.

## Existing installations

Resolve the active data root (`HERMES_HOME`, profile selection or platform default). POSIX commonly uses `~/.hermes`; native Windows commonly uses `%LOCALAPPDATA%\hermes`. The dudan upload unit assumes `~/.hermes`, so adapt its environment file, script path and writable directory if the gateway uses another home. Likewise, make the phone bridge path match that home.

Back up the affected configuration with private permissions before editing; don't place backups with secrets in the repo. Merge `.env` keys and YAML entries without replacing the full files or creating duplicate keys. Use the installation's supported updater; package-managed installations retain their update owner. Confirm `hermes doctor` and chat after an update, then retest dudan. Preserve an existing `API_SERVER_KEY` unless rotation was requested.

## Editing Hermes itself

For source work, follow the checkout's instructions and [PM developer workflow](https://hermes-agent.nousresearch.com/docs/reference/package-management). Activate the checkout with `source ./activate` on POSIX or `. .\activate.ps1` on PowerShell. Dependency changes use `hermes pm lock`; commit `pyproject.toml` and `uv.lock` together, then reactivate. Keep experiments on a disposable Hermes home rather than live user data.

The [contributing guide](https://hermes-agent.nousresearch.com/docs/developer-guide/contributing) documents `scripts/run_tests.sh`, including focused paths. On Windows, use Bash for that runner. Run checks for the modified workspace when building Hermes interfaces. Do not patch a bundled application's payload or redirect its live service to an experimental checkout implicitly.

For ordinary dudan integration changes, edit this repo's client/bridge/upload code and use the installed Hermes API. A Hermes source build is only needed when Hermes itself must change.
