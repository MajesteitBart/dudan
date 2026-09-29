# Upgrade an existing installation

[Back to dudan](../README.md) | [Documentation](README.md)

## Upgrading from aight

The project now lives at [MajesteitBart/dudan](https://github.com/MajesteitBart/dudan). Install the new APK over the existing app with the same signing key. The Android application ID remains `nl.bartvandermeeren.aight` (with `.debug` for debug builds), so updates keep settings, permissions and downloaded voice models. The source namespace is `nl.bartvandermeeren.dudan`.

The old Android assistant entry points, activity alias, intent keys and encryption-key alias remain for compatibility. Existing Hermes chat IDs stay valid, including assistant chats. The former default assistant name becomes dudan; custom names stay as entered.

On the Hermes host, follow the [upload service upgrade steps](../tools/hermes-upload/README.md#upgrading-from-aight). For phone control, copy `dudan_phone_bridge.py` to `~/.hermes/`, change the script path in `mcp_servers.phone.args`, then restart Hermes. New setup snippets use `DUDAN_PHONE_*`; the bridge also accepts existing `AIGHT_PHONE_*` variables and reuses the old tool cache. New variables take precedence.

The signing keystore file is now called `signing/dudan-release.jks`. On another build machine, rename the existing file and update only `storeFile` in `signing.properties`. Keep its key alias and passwords unchanged; do not generate a replacement key.

