---
name: dudan-hermes
description: Set up Hermes; edit, build, install and connect dudan.
---

# dudan and Hermes

## When to use

Use this skill to install or configure a Hermes agent for dudan, work on the Android app or its Hermes integrations, build/install an APK, or diagnose chat, uploads and phone control.

## Procedure

1. Identify the requested outcome and the machines involved: the dudan checkout/build machine, Hermes host and Android device can be different machines. Reuse an existing installation. Discover its service account, active profile/data directory, launcher and service manager before changing configuration.
2. Locate the dudan checkout by its `app/build.gradle.kts`, `docs/setup.md` and `tools/hermes-upload/`. If absent and source/tools are needed, clone `https://github.com/MajesteitBart/dudan.git` into an appropriate workspace. Resolve repository paths from that checkout, and this skill's references from the installed skill directory. Installing this skill does not install Hermes or dudan.
3. Read the reference needed for the task:
   - [Hermes installation and source edits](references/hermes.md).
   - [Connect chat, uploads and phone control](references/connect.md).
   - [Edit, build, install and upgrade dudan](references/develop.md).
4. Read applicable repository instructions and current documentation before editing. This skill gives a baseline; confirm commands with the installed CLI's `--help` and current source when versions differ. Preserve unrelated config entries, credentials, chats, uploads and downloaded models.
5. Verify the requested behavior from the device's network path. Report the APK location or installed package, tested endpoints/features, running services and any manual phone settings still needed. Never report a connection as tested based only on a running process.

## Connection map

| Capability | Endpoint | Authentication |
| --- | --- | --- |
| Chat and agent turns | Hermes host, `8642` | Hermes `API_SERVER_KEY` |
| Non-image attachments | Upload service on Hermes host, `8645` | Same `API_SERVER_KEY` |
| Agent controls phone | Phone, `8643/mcp`, through local stdio bridge | Separate `DUDAN_PHONE_TOKEN` |

Keep these services inside the tailnet. The Hermes key permits agent turns with terminal access. Store secrets in the intended environment/configuration or secret store; omit them from committed files, logs and completion messages. Phone-control setup copied from the app contains a secret.

## Pitfalls

- Hermes source and Hermes user data are different directories. An installed skill is not a dudan source checkout.
- The app's source namespace is `nl.bartvandermeeren.dudan`; its installed identity remains `nl.bartvandermeeren.aight` (debug adds `.debug`). Keep that identity and the existing signing certificate for updates.
- The shell's `hermes` can belong to a different installation/profile than the gateway. Install MCP dependencies and edit configuration for the running gateway.
- Uploads and phone control are optional. Complete the capabilities requested without treating their absence as a chat failure.
- Use the environment's persistent-server manager for servers that should outlive the task. On Bart's Windows computer, follow repository instructions for the Task Scheduler helper; inspect fixed bind addresses in the mock before starting it.

## Verification

For setup, send a real text turn from dudan and confirm the reply. If requested, upload a small disposable file and have Hermes read it, and test `phone_status` through Hermes. For code changes, run the relevant tests and build described in the development reference. State which checks were actually run.

## Install this skill

Copy this entire directory into the agent's configured skills directory, retaining `references/`, `scripts/` and `agents/`. Agents that support `SKILL.md` can load it directly; other agents can read it as instructions without native skill discovery.

From a dudan checkout, a dependency-free Python 3 installer is available:

```sh
python skills/dudan-hermes/scripts/install.py --skills-dir /path/to/agent/skills
```

The installer creates `dudan-hermes` inside that directory and refuses to overwrite an existing skill. Common targets are the active Hermes data directory's `skills/`, Codex's configured skills directory, or a repository's `.agents/skills/`. Discover custom homes/profiles first. Open a new agent session after installing; in Hermes invoke `/dudan-hermes`, or in Codex invoke `$dudan-hermes`.
