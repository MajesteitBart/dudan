# Hermes patches

`skills-api.patch` changes Hermes' API server, `gateway/platforms/api_server.py`, and adds tests for it to `tests/gateway/test_api_server.py`. It was made against Hermes commit `fdec926`.

The patch does two things:

- It fixes `GET /v1/skills`. The handler calls `_find_all_skills(skip_disabled=False, include_editorial=True)`, but `_find_all_skills()` has no `include_editorial` parameter, so every request fails with HTTP 500. The patch drops that argument. Upstream [PR #108968](https://github.com/NousResearch/hermes-agent/pull/108968) makes the same change for [issue #108967](https://github.com/NousResearch/hermes-agent/issues/108967).
- It adds `GET /v1/skills/{name}`, which returns one skill's SKILL.md: the whole file as `content`, the markdown after the frontmatter as `body`, and the skill's name, description, category, path under the skills folder and linked files. It finds the skill the way the agent's `skill_view` tool does, but only reads: it installs nothing and doesn't count a view. `GET /v1/capabilities` lists it under `endpoints` as `skill`. dudan looks for that entry before it shows a skill's SKILL.md.

Without the patch, dudan's Skills screen explains the 500, and `$` tags in the prompt bar find no skills. With only the one-line fix, both work, and tapping a skill shows its description but not its SKILL.md.

## Apply it

On the Hermes host, in Hermes' checkout:

```sh
cd ~/.hermes/hermes-agent
git apply --check /path/to/skills-api.patch
git apply /path/to/skills-api.patch
hermes gateway restart
```

Then `GET /v1/skills` lists your skills, and the `endpoints` in `GET /v1/capabilities` include `skill`:

```sh
curl -s -H "Authorization: Bearer $API_SERVER_KEY" http://localhost:8642/v1/capabilities
```

After a `hermes update`, check that the change is still there with `git -C ~/.hermes/hermes-agent diff --stat`.

## When it doesn't apply

`git apply` refuses the patch when the lines around a change differ from commit `fdec926`. That happens once upstream merges PR #108968, since the fix is then already in, and it can happen after other changes to `api_server.py`. Try a three-way merge, which uses the Hermes history in your checkout to see what changed:

```sh
git apply --3way /path/to/skills-api.patch
```

A change that is already in Hermes merges without a conflict.
