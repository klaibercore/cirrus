# `/api/show` fixtures

Bodies for `POST /api/show`, used by `ModelCapabilityDetectorTest`.

These mirror the response shape of **Ollama 0.32.x** (the `capabilities` array landed in 0.5.0
and `remote_model`/`remote_host` arrived with cloud models). They are trimmed: the real response
also carries `license`, `modelfile`, `parameters`, `template` and several dozen more `model_info`
keys, none of which the picker reads. The keys that *are* present, and their types, match the
wire format.

Provenance, stated plainly: only `error_not_found.json` is a verbatim capture — from a local
`ollama serve` 0.32.6 answering `{"model":"nope"}`. The rest are hand-built to the documented
shape for models this machine has not pulled, so treat them as shape fixtures rather than
recordings. If you pull one of these models, re-capture and overwrite:

```bash
curl -s http://localhost:11434/api/show -d '{"model":"qwen3:8b"}' | jq > qwen3_tools_thinking.json
```

| Fixture | Stands for |
|---|---|
| `qwen3_tools_thinking.json` | A modern local model: tools + reasoning, 40960 context |
| `llama32_vision.json` | Vision model, 131072 context |
| `qwen2_32k.json` | `families: ["qwen2"]`, 32768 context — the "32K context" chip |
| `text_only.json` | Completion only, no `model_info` at all |
| `gpt_oss_cloud.json` | Cloud-hosted, and an unknown capability a newer server might send |
| `embedding_minimal.json` | Nulls and empties throughout — the defensive-parsing case |
| `error_not_found.json` | Verbatim error body from Ollama 0.32.6 |
