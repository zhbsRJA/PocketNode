# PocketNode

Run quantized language models **entirely on your Android device**, and expose
them over your local network through an OpenAI-compatible API.

No cloud. No account. No telemetry.

---

## What it does

**Chat locally.** Download a model once, load it into memory, and talk to it.
All inference happens on-device using MediaPipe LLM Inference. Airplane mode
works fine.

**Serve to your LAN.** Turn the built-in relay on and any OpenAI-compatible
client on the same WiFi can use your phone as an API endpoint. Supports SSE
streaming.

**Bring your own models.** Import a model file you already have instead of
downloading it again. Name it whatever you like.

---

## Notes

- Models are 0.5 GB to 3.7 GB. Download them over WiFi.
- Inference runs on the CPU. Expect a flagship device for the larger models.
- The relay is plain HTTP and intended for local networks only. Do not expose
  it to the internet without a TLS tunnel.
- Available in 13 languages.

---

## Privacy

No data is collected, transmitted, or shared. See the
[privacy policy](privacy) for details.

---

## Contact

Open an issue on this repository.
