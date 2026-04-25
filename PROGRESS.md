# pi-nostr — Progress

## What it is
Android app + Node.js bridge that lets you chat with the [pi coding agent](https://pi.dev) from your phone over Tailscale. No Nostr, no relays, no crypto — just a WebSocket over a direct P2P tunnel.

## Architecture
```
Phone app ──Tailscale──► Bridge (Node.js) ──► pi SDK ──► pi sessions
```

## Completed

### Bridge (`pi-tailscale-server`)
- [x] WebSocket server wrapping pi SDK (`createAgentSession`)
- [x] Per-thread sessions (keyed by thread ID, not connection)
- [x] Streaming frames: thinking, text_delta, tool_call, turn_complete
- [x] Directory listing (`list_dir` WebSocket command)
- [x] `/dir <path>` — set working directory
- [x] `/model <name>` — switch model (matched live from pi ModelRegistry)
- [x] `/models` — list available models
- [x] `/reset` — reset session
- [x] Thread ID tagged on every frame for proper routing

### App (`pi-tailscale-android`)
- [x] Direct WebSocket client (OkHttp)
- [x] Chat UI with input bar, send button
- [x] Streaming text (updates in-place)
- [x] Expandable thinking viewer (collapsible, auto-scroll)
- [x] Tool call cards (consolidated by toolCallId, running/complete/error)
- [x] Thread tabs with spinner (processing) and green dot (unread)
- [x] Thread switching preserves per-thread message history
- [x] Directory picker with text input + filter, folder browsing
- [x] Settings overlay dialog (not full-screen)
- [x] Markdown rendering via Markwon (tables, code, lists, links, strikethrough)
- [x] Text selectable for copy
- [x] Clean JSON frame format (thread, id, etc.)

## Known Bugs

### Markdown text invisible on some devices
Color conversion between Compose Color and Android's `setTextColor(int)` might produce wrong values. Falls back gracefully (just invisible text). Workaround: use a hardcoded color or fix the ULong→Int conversion.

### Background thread tool_call consolidation
`currentToolCallMsgs` only tracks the active thread. Background thread tool_call frames are stored in the thread's message list but use a simpler matching strategy (by `toolCallId`). Works but doesn't get the same "update in-place" treatment as the active thread.

### Thread directory display
The active thread's directory path is only visible in Settings dialog. Could show as a tooltip on the thread tab (long-press).

### No session persistence
Threads are lost on app restart (in-memory only). Bridge sessions survive as long as the bridge process runs.

## Next Steps / Wants

### High Priority
- [ ] **Fix invisible text** — Color conversion in MarkdownText.kt
- [ ] **File diff viewer** — Capture `edit`/`write` tool results and render diffs inline
- [ ] **Session persistence** — Store thread data (messages, directory, model) in room or JSON
- [ ] **Multi-model per thread** — `/model` command shows checkmark on current; fuzzy search is basic

### Medium
- [ ] **Better thread switcher** — Show directory path on long-press or as subtitle
- [ ] **Parallel agent support** — Run multiple threads simultaneously (already works per-thread)
- [ ] **Model selector UI** — Dropdown instead of typing model names
- [ ] **File explorer improvements** — Show file sizes, dates; filter by file type
- [ ] **Auto-scroll improvements** — Don't auto-scroll if user has scrolled up manually

### Low / Polish
- [ ] **Theme support** — Light mode, accent colors
- [ ] **Notification when thread completes** — Green dot works, but push notification would be nice
- [ ] **Rate limiting** — Prevent accidental double-sends
- [ ] **Error state display** — Show reconnection status, relay errors gracefully
- [ ] **App icon** — Custom launcher icon
- [ ] **Release build** — ProGuard rules, APK signing
