# Developer tooling

## Context7 MCP

The guidelines require every dependency version and API shape to be resolved
through Context7 before it is added, rather than recalled. `.mcp.json` at the
repository root configures it, so it is available to anyone who opens either
repository in Claude Code.

```json
{
  "mcpServers": {
    "context7": {
      "command": "npx",
      "args": ["-y", "@upstash/context7-mcp@4.0.3"]
    }
  }
}
```

**stdio rather than the hosted HTTP endpoint.** The HTTP transport requires an
`Authorization: Bearer <key>` header. The API key is optional for Context7, but
there is no way to express "omit this header when the variable is unset" in
`.mcp.json`, and an empty bearer token is not documented behaviour. The stdio
transport simply takes no `--api-key` argument, which is unambiguously valid.

**Version pinned.** `@upstash/context7-mcp` without a version floats to
whatever is newest at the moment each developer first runs it, which is how two
people end up with different tool schemas and no way to tell.

Licence: MIT — on the allowed list, and it is developer tooling rather than a
shipped dependency, so it does not appear in `THIRD-PARTY-NOTICES.txt`.

### Adding an API key

Optional, and only for higher rate limits and private repositories. Configure
it per-developer rather than in this file — a key committed here is a published
key:

```
claude mcp add --scope user context7 -- npx -y @upstash/context7-mcp@4.0.3 --api-key YOUR_KEY
```

A user-scoped server takes precedence over this one, so nothing needs changing
here.

### The tool names changed in v4

The guidelines refer to `resolve-library-id` → `get-library-docs`. As of 4.0.3
the second tool is **`query-docs`**, and `resolve-library-id` requires **both**
`query` and `libraryName`, not `libraryName` alone. Read the schema from
`tools/list` rather than working from the names in prose.

### It does not work from the remote sandbox

`mcp.context7.com` and `context7.com` are both refused by the agent proxy
(`CONNECT tunnel failed, 403`), the same block that stops `dl.google.com` and
prevents the Android AGP build from resolving.

The server itself starts and registers its tools — npm is reachable, so `npx`
fetches the package without trouble — but a tool call returns:

```
Error searching libraries: TypeError: fetch failed
```

returned as a successful result containing an error string rather than as a
protocol error. So the failure is visible and harmless, not silent.

Two consequences worth knowing:

- **It works locally.** Anywhere with ordinary network access, this
  configuration is all that is needed.
- **`endoflife.date` is blocked too**, and that one has no substitute. Maven
  Central, npm and PyPI are all reachable and answer "what is the current
  version" directly — better than an index of it — but nothing reachable
  answers "is this component EOL, or within six months of it". Until the
  network policy allows it, that check has to be done by a human.

To lift both, allow `context7.com` and `endoflife.date` in the environment's
network policy — see
<https://code.claude.com/docs/en/claude-code-on-the-web>.
