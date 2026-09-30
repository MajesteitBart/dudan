# Agent skills

[`dudan-hermes`](dudan-hermes/SKILL.md) covers Hermes installation and configuration, dudan development and APK installation, chat connectivity, file uploads and phone control. It uses the portable `SKILL.md` format and includes its own references. No agent-specific tools are required to read it.

## Let your agent set it up

### 1. If you don't have a Hermes agent yet

Ask a coding agent such as Codex to install Hermes on a PC that stays on or a virtual machine. You can also [deploy a hosted agent with Hermes Cloud](https://portal.nousresearch.com/cloud).

For a PC or virtual machine, copy this prompt into your coding agent:

> Install and configure Hermes Agent on this machine using the [official installation guide](https://hermes-agent.nousresearch.com/docs/getting-started/installation/). Set it up to keep running when I close this session and to start again after a reboot. Help me connect my chosen model provider, and verify that Hermes can receive a message and reply. Complete the setup you can handle yourself, and guide me through any sign-in or choices that need my input.

Once you can chat with Hermes, send it the prompt below.

### 2. Ask Hermes to connect to your phone

Copy this prompt into a conversation with your Hermes agent:

> I want to chat with you on my Android phone using Dudan, send you files, and let you use Dudan's phone-control tools. Download and install the complete [dudan-hermes skill](https://github.com/MajesteitBart/dudan/tree/main/skills/dudan-hermes), including its references and scripts, into this Hermes profile's skills directory. Read the skill and follow it to prepare this host for Dudan. Configure and verify the API server, file-upload service and phone-control bridge, using Tailscale for private connectivity. Keep my existing settings and credentials, and keep secrets out of chat. Do everything you can on the host, then walk me through installing Dudan and the remaining phone setup one step at a time. Finish by helping me test a chat reply, a file upload and phone control. If this host cannot support a required step, explain what is missing and help me resolve it.

The agent downloads and installs the skill for you. You only need the manual instructions below if you prefer to install it yourself.

## Alternative: manual install

Clone or download this repository, then copy `skills/dudan-hermes/` into your agent's configured skills directory. Alternatively, from the repository root use the Python 3 installer with an explicit destination:

```sh
# Hermes on POSIX, default data home
python skills/dudan-hermes/scripts/install.py --skills-dir ~/.hermes/skills

# Any agent with a configured skills directory
python skills/dudan-hermes/scripts/install.py --skills-dir /path/to/agent/skills
```

PowerShell examples:

```powershell
# Hermes native Windows, default data home
python skills/dudan-hermes/scripts/install.py --skills-dir "$env:LOCALAPPDATA\hermes\skills"

# Codex project-local discovery
python skills/dudan-hermes/scripts/install.py --skills-dir .agents/skills
```

Use the active profile's directory when `HERMES_HOME` or another profile changes the default. For Codex global installation, use `$CODEX_HOME/skills` when configured, otherwise `~/.codex/skills`. Other agents should use their documented skill location. The installer copies the whole skill, requires no Python packages and refuses to overwrite an existing installation. To update, review the changed files and replace the installed directory deliberately.

Start a new session and invoke `/dudan-hermes` in Hermes or `$dudan-hermes` in Codex. An agent without skill discovery can read `SKILL.md` directly and follow its linked references. Skill installation only copies instructions; installing Hermes, deploying services and installing an APK are separate tasks.

Example request: "Use dudan-hermes to connect my existing Hermes host to dudan, including uploads and phone control."
