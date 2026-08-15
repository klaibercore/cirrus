# Changelog

All notable changes to Cirrus are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Text goes into a shell command through stdin now, rather than being quoted into it.**
  `run_command` takes an `input` argument, and it is the difference between counting the words in a
  paragraph and writing a `printf` puzzle with three ways to fail before the command runs: the
  command line is length-capped, and a `$` or a backtick anywhere in the text is refused by the
  substitution check. `wc -w`, `sort | uniq -c | sort -rn`, `sha256sum` and `tee` now work on text
  out of the conversation with no escaping at all.
- **Scratch files are organised by topic.** Each job — `expenses`, `log-counts` — gets its own
  directory, named by the `topic` argument and reused across the commands of that job. One flat
  scratch directory across a long session became `out.txt`, `out2.txt`, `tmp.txt`, and the model
  started reading the wrong one. Because `..` is refused, a topic isolates as well as organises: a
  command working on one job cannot touch another's files even by accident. `clean_workspace` takes
  a topic too, so finishing a job no longer means clearing everything or clearing nothing.
- **The workspace cleans itself up.** Topics nothing has touched for 45 minutes are swept before the
  next command, and there is a cap on how many can be live at once — the second rule is the one that
  matters, because a session that opens a fresh topic every few minutes stays inside the idle window
  forever. A sweep reports what it took, so a file that is no longer there is a sentence the model
  can act on rather than a puzzle it spends a turn on.
- `join`, `shuf` and `sha512sum` join the allowed programs, and every command's reply now lists the
  files in its topic — the next command is nearly always about one of them, and the alternative was
  a round trip spent on `ls`.

### Changed

- **A turn's tool calls collapse into one panel.** A turn that read three files and searched twice
  put five outlined cards between the question and the answer, each as prominent as the reply
  itself. That is the wrong weight: tool calls are provenance, and provenance belongs behind one
  line you can open. The panel says how many steps ran, which tools they used and how long they
  took, is open while they run and shut once the answer lands, and shows a failure on its own line
  so nothing is hidden that matters. A single call is still a single row — wrapping one call in a
  group header only means reading its name twice.
- **Long command output keeps both ends.** Nearly every text job here finishes with its answer — a
  `wc` after a pipeline, the last hunk of a diff, the tail of a sort — so a cap that kept the first
  8,000 characters threw away the line the command was run for, and the model ran the whole thing
  again with `tail` bolted on. The middle is dropped instead, and the reply says how much.

### Fixed

- **"Jump to latest" landed in the wrong place, and sometimes did nothing at all.** Two faults on
  top of each other. The index it scrolled to counted tool and system messages, which are not rows
  in the transcript, so it aimed short of the end; and `scrollToItem` aligns an item's *top* with
  the viewport, which for an answer longer than the screen is the first line of the reply rather
  than the bottom of the conversation.
- **The transcript stopped following a reply that was still arriving.** Whether to follow was
  decided by asking "are we at the bottom?", and a streaming answer moves the bottom away from the
  reader on every token — so the test said "the reader has scrolled up" several times a second at a
  reader who had not moved, which also made the button flicker in and out. Following is now given up
  only on a scroll backwards and taken up again only on reaching the end — or on sending a message,
  since nobody types one in order to carry on reading something further up. The tail is followed
  through tool calls too, which change a turn's height without changing a character of its text.

## [1.5.1] - 2026-08-15

### Fixed

- **A shell command that would not finish could hold a turn open past its timeout.** Only for
  pipelines, and only on Android and Linux, which is why it survived 1.5.0: `sh -c` runs a lone
  command by becoming it, so killing the process killed the command, but it forks for a pipeline
  and the surviving half keeps the output pipe open. The read then blocked for as long as the
  command cared to run, with the deadline doing nothing — the case the deadline exists for. Cirrus
  now stops waiting rather than trying to end the read, which cannot be done from outside on Linux
  by any means: not interruption, not killing the process, not closing the stream.

## [1.5.0] - 2026-08-15

### Added

- **Spotify.** Search the catalogue, read your playlists, saved music and the artists you actually
  listen to, see what is playing, control playback, and make or edit playlists — five tools rather
  than the fifteen endpoints behind them, because eight near-identical schemas on every turn is a
  page of context for nothing. Sign-in is OAuth with PKCE, so there is no client secret in the app;
  it uses a client ID you create at developer.spotify.com, and the redirect URI to paste back is
  shown in Settings → Music with a copy button.
- **Media controls that work without Premium.** Spotify's API refuses playback control on free
  accounts, which left "pause the music" — the most obvious thing anyone would ask a phone
  assistant — failing for a lot of people. `media_control` drives Android's own media buttons
  instead: no account, no subscription, no network, and it works for any player, not just Spotify.
  When Spotify refuses a playback command, the error now points at it.
- **Where you are.** `get_location` answers the questions that depend on it — the weather, what is
  nearby, how far to somewhere. Coarse accuracy only, by design rather than as a fallback: every use
  it has is answered by the neighbourhood, and the difference between that and the doorstep is the
  difference between a useful tool and a tracking device. Off by default, foreground only, never
  from a scheduled agent.
- **A settings catalogue the model can read.** A model asked for a tool that was switched off used
  to be told "unknown tool" — and, unable to tell "this app cannot do that" from "not until somebody
  flips a switch", it guessed the first one and told you your app lacked a feature it shipped with.
  Refusals now name the exact switch and where it lives, and `describe_settings` lets the model
  check before promising anything.
- **Spotify in the MCP catalogue**, alongside GitHub, Sentry, Linear and the rest.
- **A shell, for the everyday mechanical jobs.** Cirrus can now run commands on the phone: counting
  and sorting text, checksums and encodings, working with the scratch files it wrote a moment ago.
  What it may run is decided before anything runs, from a list you can read in one sitting — only
  named programs, no absolute paths, no `..`, no `$(…)`, no background jobs, and nothing that runs
  another program on the command's behalf. The working directory is a scratch folder inside Cirrus's
  own cache, and it is the entire reachable world.
- **The clock, the calendar and this phone.** A model has no clock, so left alone it answers "how
  long until Friday?" from the year it was trained in — wrong in the way that looks most convincing.
  It can now ask for the exact date and time in your own timezone, for a month laid out as a grid,
  and for a summary of the device: Android version, CPU, memory, storage, battery, network, and
  which shell programs this particular phone actually has.
- **Apps, if you want it.** Off by default: list what is installed, open something, or open a store
  page for something that is not. It cannot install anything — Android's own installer asks, every
  time — and it says so where the model can read it.
- **Standing rules, not just tool descriptions.** With the shell switched on, every turn carries two
  sentences the model cannot lose track of: stay non-destructive, and clean up before finishing.
  It is told to empty its workspace when a task is done, and Cirrus empties it at every start
  regardless, so nothing survives a session that nobody asked to keep.
- **Openers written by your own model.** The four suggestions on an empty chat, and the ready-made
  agents, are now generated by the model you have configured — told exactly which tools this install
  has, so nothing is offered that would fail on the first tap. The written-in-advance sets remain
  the floor: they are what shows while the request is in flight, and what stays if it fails. There
  is a "Suggest something else" for when none of the four appeal.

### Changed

- **One switch for write actions, covering everything.** "Allow write actions" used to be a GitHub
  setting. It now governs anything that changes something outside Cirrus and cannot be undone from
  inside it — GitHub, Spotify playlists, and MCP tools. A gate per integration meant the third
  integration shipped without one, which is exactly what had happened. If you had allowed GitHub
  writes, that carries over; you will not be asked again.
- **MCP tools are assumed to write unless they say otherwise.** The protocol has optional
  annotations for this and most servers omit them, so Cirrus now treats an unannotated tool as one
  that changes things. Every other tool in the app can be understood by reading its source; an MCP
  tool is a function on somebody else's server, described by that server. **This will withhold tools
  from servers you already have attached** until you allow write actions — which is the point, since
  until now nothing asked at all.
- **Playback control is not a write.** Pausing is undone by playing, so it stays available with the
  write switch off. The test is reversibility, not severity.
- **Memory, notifications and the nightly tidy-up have switches at last.** All three were settings
  with no interface anywhere — you could not turn memory off. They are in Settings → Tools.
- **The empty chat says hello.** A cloud and "Good afternoon", side by side, in place of a large
  mark above a question the four rows underneath were already answering.
- **Bold and italic are told apart.** At reading size on a bright screen, 400 against 700 was a
  difference you had to go looking for, and a slanted run and a heavy run are both merely "darker"
  in peripheral vision. Strong text is heavier and tracks tighter; emphasis is a real italic, opened
  up. Headings are bold at every level — the bottom three are set at or below body size, where
  weight was the only thing distinguishing them.
- **The drawer is the conversation list again.** Agents and Memory moved out of it and back to
  Settings. Three rows of somewhere-else under the list made the thing the drawer is *for* look like
  one option among several.

## [1.4.0] - 2026-08-14

### Added

- **A setup that proves itself.** Cirrus can do nothing until it can reach a model, and neither way
  of arranging that — a key from ollama.com, or a machine on your network running Ollama — is
  guessable from a blank chat screen. A short wizard now asks which one you have, links out to
  create the key, and ends on a request that demonstrably worked rather than on a form that was
  filled in. It finishes by offering an agent to start with. It can be skipped from any step,
  skipping counts as done, and **Settings → Run setup again** reopens it — which also makes it the
  answer to "it stopped working", since the connection test is the same one.
- **Run history for every agent.** The card shows the last run, which cannot tell "it worked this
  morning" apart from "it has failed every morning this week". Every attempt is now recorded with
  its duration, tool calls, token count and whether you started it by hand, and the second case
  looks nothing like the first.
- **Agents start from a worked example.** Six of them — a morning briefing, a topic watch, a
  repository triage, a weekly review, tomorrow's plan, one idea a day — each opening the ordinary
  editor with its fields filled in, to be edited before it is saved. The blank editor is why most
  people never made a second agent, and often not a first.
- **When it next runs, in words.** A card says "Next tomorrow at 07:30" rather than leaving you to
  work it out from "07:30 · weekdays", and shows a live indicator while a run is in flight.
- **Agents and Memory are in the drawer.** Both were two taps deep inside Settings, which reads as
  configuration rather than as somewhere with content in it.

### Changed

- **An agent's answers stay out of your conversations.** A daily agent contributed a thread a day to
  the same list as the conversations you actually had, so after a fortnight the drawer was mostly
  machine and your own threads had been pushed off the end. Runs now live on the agent that wrote
  them. They are still ordinary threads — open, scroll, branch, export — and replying to one moves
  it into your conversation list, because a thread you have joined in on is a conversation rather
  than an artefact. A banner on a run says so, with a **Keep** button for when you want it without
  answering. Threads written before this update are moved across automatically.
- **Agents tidy up after themselves.** Each keeps a set number of runs — ten by default, adjustable
  in the editor — and older threads are deleted after each run. Anything you replied to or kept is
  never touched.
- **Deleting an agent says what it will take with it**, and asks first. It used to delete
  immediately and leave its threads behind, invisible everywhere in the app.
- **Suggested openers match what is switched on.** The three fixed examples on an empty chat have
  been replaced by four drawn from what this install can actually do — no offer to read your
  repositories unless a GitHub token is configured, since a suggestion that fails is worse than no
  suggestion. They can be turned off in **Settings → Chats**.

### Fixed

- **Two agents at the same time swapped notifications.** The tool that puts something on the shade
  carries the conversation its notification should open, and it is a single shared object, so two
  runs overlapping — which "08:00 on weekdays" makes likely — handed each other's threads to each
  other's notifications. Only one agent runs at a time now, which also stops two generations
  competing for the same phone.
- **A stalled run left an agent stuck forever.** A stream that stops delivering does not fail, it
  just goes quiet, so the run blocked until the system killed it at its own deadline — leaving the
  agent marked as running and, worse, skipping the booking that would have scheduled tomorrow. Runs
  now stop themselves first, and anything killed by a reboot or by the phone reclaiming memory is
  closed out at the next start instead of showing a spinner for something that ended days ago.
- **A cancelled run was recorded as a finished one**, so an agent could appear to have answered
  when it had been stopped mid-sentence.
- **One dropped connection no longer costs you the day.** Every failure was final, so a network
  blip at 07:30 meant no briefing. Transport failures are retried twice, half a minute apart; a
  rejected key deliberately is not, since re-running the generation only rediscovers that the key
  is still wrong.
- **An agent switched off while the app was closed ran anyway.** Re-booking on startup never
  cancelled what should no longer fire, so a switch marked off did the thing anyway.
- **Scheduled prompts are no longer remembered as facts about you.** The nightly memory pass read
  agent threads along with everything else, so a daily agent's own instructions were harvested as
  something durable about you every single night.
- **Saving a key and then testing it no longer races itself.** The connection test could run against
  the previous key, reporting a failure for a key that was fine.

## [1.3.1] - 2026-08-13

### Fixed

- **A GitHub tool could run when it was never offered.** The registry decides what to show the model
  and, separately, what may actually run when the model names something anyway — from an earlier
  turn of the same thread, or a guess. The second check asked about the conversation's tools switch
  but not about GitHub's own two gates, so a model naming `github_list_repos` reached GitHub with
  your token attached even with the feature switched off, or with no token configured at all.
  Writes were already refused by the client itself; reads were not.
- **A brief restatement could delete a detailed memory.** Duplicate detection scores overlap as a
  share of the shorter memory's terms, so "prefers Kotlin" always matches "prefers Kotlin over Java
  for Android work" — correctly, they are one memory. But the incoming wording then overwrote the
  stored one in place, silently dropping the qualifier with no way back. A fold now keeps the
  stored wording unless the new one actually adds something.
- **Ripples were square on rounded controls.** Several bordered cards and pill buttons attached
  their click handling outside the surface that clips to the shape, so pressing one painted a
  rectangle across its corners.

## [1.3.0] - 2026-08-13

### Changed

- **Cirrus looks like what it talks to.** The interface has been rebuilt around ollama.com's visual
  language: a monochrome page, hairline rules instead of shadows, a full pill on anything you can
  press, and headings set in a rounded display face over the platform's own text face. The warm
  clay palette and its six competing corner radii are gone; there is one neutral ramp and two radii.
- **Colour now means something.** It appears in exactly three places: the capability tags under a
  model name — cyan for vision, blue for tools, indigo for thinking, as on ollama.com itself — plus
  links and search hits. Everything else, including every control and every selected row, is a step
  on the grey ramp. A tinted panel no longer competes with the syntax highlighting inside it.
- **Cards show their edges.** Reasoning traces, tool calls, model cards, settings groups and the
  composer are bordered rather than filled, which is what lets a dense list read as one object
  instead of a stack of grey slabs.
- **A new launcher icon.** The cloud is now ink on a pale plate rather than pale on a dark one, and
  it has been redrawn wider, flatter and smaller so it is properly centred with room to breathe
  inside the launcher's mask. The notification icon shares its geometry exactly; the two had
  quietly drifted into different shapes.

### Removed

- **Dynamic colour.** Material You repaints the app from your wallpaper, which is directly at odds
  with a design built on having no colour in it — a lilac-tinted monochrome interface is neither.
  Light and dark remain, since that is a question about the room you are in rather than about the
  design.

## [1.2.0] - 2026-08-13

### Added

- **Maths is typeset, not approximated.** Formulas were flattened to Unicode, so `\frac{a}{b}`
  arrived as `a/b`, a summation lost its limits and a matrix was hopeless. There is now a real
  layout engine: fractions stack over a rule, scripts sit where scripts belong, delimiters and
  radicals stretch to fit what is inside them, and matrices, `cases` and aligned environments come
  out as matrices, cases and aligned environments. Spacing follows TeX's rules, so `a + b` is
  looser than `ab` and `a = b` looser still — which is most of what separates maths from a row of
  symbols. Display equations get their own line and scroll sideways when they are wider than the
  screen; long-press copies the LaTeX.
- **Answers can be selected.** Long-press any reply to select and copy part of it. Formulas copy
  as readable text rather than as a gap, so a selected paragraph gives you `x²`, not a hole.
- **Read aloud.** A speak button under finished replies. What gets spoken is not the raw markdown:
  code blocks are announced rather than dictated, links read as "link", tables as heading-and-value
  pairs, and maths as words — "the sum from i equals 1 to n, of x sub i".
- **ElevenLabs voices, optionally.** Bring an API key and replies are read in a far better voice,
  with the voice and model chosen in settings. Without a key it uses Android's own engine; there is
  no state in which the button does nothing.
- **Find in conversation.** Search the open thread from the overflow menu, with every match
  highlighted and arrows to step between them.
- **Memory across conversations.** Cirrus can write down something worth keeping and look it up
  later, through tools it drives itself — deliberately on demand, so what it keeps is a short list
  of durable facts rather than a summary of everything you have ever said. **Settings → Memory**
  shows every line, and lets you edit, pin, retire or delete any of it. Pinned memories are sent
  with every message; the rest have to be recalled.
- **Overnight consolidation.** Once a night, on a charger, Cirrus reads the threads since the last
  pass, writes down anything durable that was said in passing, merges memories that say the same
  thing and retires what has been overtaken. Nothing is deleted — retired memories can be restored.
- **Scheduled agents.** A prompt that runs on its own clock: a morning briefing, a Friday summary,
  a nightly check on something. Each run writes into an ordinary conversation you can open, scroll,
  branch from and reply to, and can notify you when it lands. **Settings → Agents**.
- **A notification tool.** The model can put something on your notification shade when you asked to
  be told about it — and a scheduled agent can reach you with what it found at 3am.

### Changed

- **Settings is a hub rather than a scroll.** Thirty controls in one column, ordered by when each
  was written, became eight groups plus Memory and Agents. Nothing was removed.
- **The launcher icon is just the cloud.**

### Fixed

- **Memory and notifications are no longer behind the tools switch.** That switch governs what
  reaches off the phone — search, GitHub, MCP — and gating local, instant, free tools behind it
  meant memory silently doing nothing in most conversations.
- **The tools switch now actually stops a tool.** Only the schemas were gated, so a model that
  named `web_search` anyway — from an earlier turn, or by guessing — would still have reached the
  network with external tools switched off. Resolving a tool now applies the same gate that
  decides which ones are offered.

## [1.1.0] - 2026-08-11

### Added

- **MCP servers.** Attach a Model Context Protocol server and its tools are offered to the model
  alongside Cirrus's own, under the same per-conversation tools switch. Servers live in
  **Settings → Tools → MCP servers**; each one can be switched off without being removed, and its
  token is stored with the same Keystore-backed encryption as the Ollama and GitHub keys.
- **Discovery before you commit.** Adding a server connects to it, asks what tools it offers, and
  lists them — with the transport it negotiated — before anything is saved. A URL that parses
  proves nothing, and a misconfigured server otherwise fails much later, mid-answer, as a tool
  call the model cannot explain. Editing the URL or token after testing marks the result stale
  rather than showing a number that no longer applies.
- **A short list of known servers** (GitHub, Sentry, Linear, Hugging Face, DeepWiki) that prefill
  the form. They are starting points, not endorsements, and each still has to be reached before
  it can be saved.
- **Jump to latest.** Scrolling up during an answer now offers a way back, labelled "New response"
  while a turn is still streaming.

### Fixed

- **Scrolling up mid-answer no longer fights you.** The transcript followed the tail on every
  token, so trying to re-read something while a response streamed dragged you straight back to
  the bottom. It now follows only when you are already at the bottom — and does so without
  restarting an animation per token, which was also the source of some of the jitter.
- **Your own messages can be acted on.** Copy, edit and resend, branch and delete were all
  implemented and reachable for assistant turns only; the actions sheet had an edit-and-resend
  branch that nothing could open. Long-press any message you sent.
- **Touch targets meet the 48dp minimum.** Nine controls — the composer's icon row, the send
  button, the per-message actions, the code-block buttons, the conversation overflow menu and the
  error banner's dismiss — had hit areas smaller than their icons suggested.
- **TalkBack announces answers.** A completed response is now a polite live region, so it is read
  out when it lands instead of arriving in silence.

### Security

- **An MCP server can no longer receive your GitHub token.** The MCP transports shared the GitHub
  HTTP client, whose interceptor replaces `Authorization` on every request it handles — so a
  server's own token would have been overwritten with the user's GitHub PAT and sent to a
  third-party host. MCP now has its own client that attaches no credential of its own. Nothing
  was exposed in a released build: no code path reached the MCP client until this release.

## [1.0.2] - 2026-08-11

### Fixed

- **Replies no longer die when Cirrus leaves the screen.** A turn ran in the chat screen's own
  scope, so it was cancelled the moment that screen went away — switching threads killed it
  outright, and backgrounding the app left it to be frozen by Android within seconds of the
  process being cached, which stalls the socket until the connection dies. Turns now belong to
  the application, and a foreground service keeps the process awake and unfrozen for as long as
  one is running. Lock the phone mid-answer and the answer is finished when you come back.
- **A cut-off reply is no longer presented as a finished one.** A stream that ended without its
  terminal chunk was treated as a completed answer, which is what made an interrupted reply look
  like the model deciding to stop — reliably at a sentence end, because that is where tokens
  land. It is now reported as the interruption it is, with the partial text kept, and a round
  that dies before producing anything is quietly retried.
- **Spending the tool budget no longer abandons the task.** When a turn used up its tool rounds,
  the model's pending calls were dropped and the turn ended right there — often mid-plan, and
  sometimes with no text at all. Cirrus now asks once more with the tools withheld, so the turn
  ends on an answer that says what was found and what is still outstanding.

### Added

- **A notification while a reply is streaming**, showing that Cirrus is working and offering a
  Stop that works from anywhere. Permission is asked for at your first generation; refusing it
  costs the notification, not the reply.
- **A per-thread error banner that waits for you.** A failure on a thread you are not looking at
  is shown when you return to it, rather than being lost.

## [1.0.1] - 2026-08-10

### Fixed

- **Threads no longer stay called "New chat".** Auto-titling asked for a title in a way that
  quietly produced nothing on the models most people run it against. Ollama enables thinking by
  default on any model that supports it, so a reasoning model spent the whole 24-token title
  budget on its reasoning and returned an empty answer; one that emits raw `<think>` tags inline
  titled the thread `<think>` instead. Thinking is now switched off explicitly where the model
  has the capability (and the field is omitted entirely where it does not), the budget is wide
  enough to survive a model that reasons anyway, and any reasoning left in the reply is stripped
  before the title is taken.
- **Titling no longer dies when you leave the thread.** It ran inside the chat screen's
  generation job, so switching conversations or backing out in the second after an answer landed
  cancelled the request and left the thread unnamed for good. It now runs on the application
  scope, and stopping a generation still names the thread it produced.
- **A thread always ends up named.** If the host is unreachable or the model returns nothing
  usable, the thread takes its name from its opening message instead, and the next turn still
  tries for a real summary.

## [1.0.0] - 2026-08-09

First release. An Android Ollama client for developers who want their local models to actually
do things.

### Added

- **Capability-aware model picker.** Cards carry parameter count, quantization, on-disk size and
  context window, with capability chips read from `/api/show` rather than guessed from the
  model's name. Filter by vision, reasoning, tools, cloud or local.
- **Token streaming** straight from `/api/chat` over NDJSON. Stopping a generation cancels the
  HTTP call, so the server stops generating too.
- **Reasoning traces.** `thinking` deltas stream into a collapsible section, with an effort
  control for models that support one.
- **Tool calling** in bounded multi-round loops: web search, page fetch, and twelve GitHub tools.
- **GitHub integration.** Read code in public and private repositories, search, browse trees,
  read issues and pull request diffs. Opening issues, commenting, posting reviews and committing
  files sit behind a separate switch that is off by default — and with it off, those tools are
  never offered to the model at all.
- **Markdown built to survive streaming**, with a hand-written lexer for syntax highlighting and
  a practical subset of LaTeX maths rendered as Unicode.
- **Voice dictation**, preferring Android's on-device recogniser.
- **Full sampling control** — temperature, top-p, top-k, min-p, penalties, seed, `num_ctx`,
  `num_predict`, stop sequences, JSON schema output, `keep_alive` — each independently
  overridable and each explained where it sits.
- **Conversation management**: fork from any message, edit and resend, regenerate, export to
  Markdown, and threads that name themselves from their content.
- **Secrets encrypted at rest** with an AES-GCM key generated in and never released from the
  Android Keystore. No analytics, no crash reporter, no telemetry.
- **An MCP client** speaking both HTTP transports — the current streamable-HTTP one and the older
  two-channel SSE one, with the transport auto-detected when a server answers the streamable-HTTP
  handshake with an `endpoint` event. Not yet reachable from the UI; see the README.
- **Signed release builds.** Pushing a `vX.Y.Z` tag builds, signs and verifies an APK and
  publishes it to GitHub Releases with generated notes and a `.sha256`, so Obtainium can track
  it. See [docs/RELEASING.md](docs/RELEASING.md).
- **An F-Droid build recipe**, staged in `klaibercore/fdroid-cirrus-metadata`, pending submission
  to fdroiddata now that there is a tag to build from.

### Known gaps

- MCP has no configuration UI. The client is complete and tested, but nothing persists a server
  or bridges its tools into the registry.
- LaTeX is mapped to Unicode, not typeset. There is no layout, so fractions render as `a/b`.

[Unreleased]: https://github.com/klaibercore/cirrus/compare/v1.5.1...HEAD
[1.5.1]: https://github.com/klaibercore/cirrus/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/klaibercore/cirrus/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/klaibercore/cirrus/compare/v1.3.1...v1.4.0
[1.3.1]: https://github.com/klaibercore/cirrus/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/klaibercore/cirrus/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/klaibercore/cirrus/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/klaibercore/cirrus/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/klaibercore/cirrus/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/klaibercore/cirrus/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/klaibercore/cirrus/releases/tag/v1.0.0
