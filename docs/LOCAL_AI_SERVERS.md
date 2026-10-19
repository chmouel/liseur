# Local AI servers

Liseur can translate with a language model running on your own computer
and read aloud with a voice server on your own network, so the text can
stay at home. Any server that speaks the OpenAI API works.
This page covers the ones that have been tried with Liseur.

| Server                                                     | Translation | Read aloud | Address                   |
|------------------------------------------------------------|-------------|------------|---------------------------|
| [LM Studio](https://lmstudio.ai)                           | Yes         | No         | `http://<computer>:1234`  |
| [Ollama](https://ollama.com)                               | Yes         | No         | `http://<computer>:11434` |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) server  | Yes         | No         | `http://<computer>:8080`  |
| [Kokoro-FastAPI](https://github.com/remsky/Kokoro-FastAPI) | No          | Yes        | `http://<computer>:8880`  |

`<computer>` is the address of the machine running the server on your
local network, such as `192.168.1.10`. `localhost` will not work, because
on the phone it means the phone itself. Liseur adds `/v1` to the address
itself, so typing it or leaving it out both work.

## Translation

1. Start the server and load or pull a model (see below).
2. In Liseur, open External services and API keys and tap Add server.
   Pick LM Studio or Ollama from the list and replace the example
   address with your computer's, or type the address yourself.
3. In Translation settings, choose Server (OpenAI-compatible), pick the
   server, then pick a model from the list.

No API key is needed for any of these unless you set one up yourself.
Android asks for permission to reach devices on your local network the
first time.

Once a server listens on the network, any device that can reach it can
use it, and over plain `http://` the passages travel unencrypted. Run it
on a network you trust, keep it off the internet (no port forwarding),
and restrict it with your firewall if other people share the network.
LM Studio can require an API key, and a reverse proxy can add HTTPS and
a key in front of Ollama or llama.cpp; Liseur sends the key you enter
for the server.

### LM Studio

Download a model, then open the Developer tab and start the server. Turn
on Serve on Local Network, otherwise it only answers on the computer
itself. With just-in-time model loading on, which is LM Studio's
default, the model list in Liseur shows every model you have downloaded
and LM Studio loads the one Liseur asks for. With it off, the list shows
only the models that are loaded.

### Ollama

Pull a model, for example `ollama pull translategemma:4b`. Ollama
listens only on the computer itself until you start it with
`OLLAMA_HOST=0.0.0.0` (for the systemd service, set it with
`systemctl edit ollama`). Ollama unloads a model after five idle
minutes, so the first sentence after a pause can take a while as the
model loads again; the `keep_alive` option (see Advanced options below)
keeps it loaded longer. Liseur waits up to two minutes for an answer.

### llama.cpp

Start `llama-server` with `--host 0.0.0.0` so the phone can reach it. It
serves the single model it was started with.

### Choosing a model

Page translation sends one sentence at a time, so speed matters more
than size. A small instruction model in the 3B to 8B range is usually
enough for common language pairs. Liseur asks a model whose name
contains `translategemma` in the prompt format it was trained on,
without the previous sentence as context. Reasoning models think before
every sentence, which makes page translation slow; Liseur drops a
thinking block the reply starts with, but a model without reasoning, or
with reasoning turned off on the server, gives faster results. Pages
saved before Liseur learned to hide that thinking keep it until you
clear them under Translation settings, Advanced, Saved translations.

### Advanced options

Translation settings has an Advanced section for the chosen server:
temperature (0 unless you change it), whether page translation sends the
previous sentence as context, extra instructions added to the prompt,
the most tokens a reply may use, and Ollama's `keep_alive`. `keep_alive`
replaces Ollama's five idle minutes with a time of your own, as seconds
or a duration such as `30m`. With Ollama 0.40.3, through the
OpenAI-compatible endpoint Liseur uses, `30m` kept the model loaded for
30 minutes and `0` unloaded it as soon as it answered.

## Read aloud

LM Studio, Ollama and llama.cpp only produce text. If you point read
aloud at one of them, Liseur tells you that the server has no speech
endpoint.

For voices from your own network, run a speech server such as
Kokoro-FastAPI:

```bash
docker run -d -p 8880:8880 ghcr.io/remsky/kokoro-fastapi-cpu
```

Add it in External services and API keys the same way (Kokoro is in the
list), then in Read aloud settings choose Speech server
(OpenAI-compatible), pick the server, and choose a voice.

## Privacy

Liseur sends the text to translate or read aloud only to the server you
chose. Whether it stays on your network after that depends on the
server: LM Studio, Ollama, llama.cpp and Kokoro-FastAPI run the model
on that computer, but a proxy or a server set up to forward requests
could send it elsewhere. See [PRIVACY.md](PRIVACY.md).
