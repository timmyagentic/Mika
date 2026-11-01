# Models and sign-in

[简体中文](models.md) | English

Mika doesn't come with a model; it uses a model provider you choose. Enter an API key, or sign in with an account. The main model must support tool calling.

## Add a model

Open "Models" (模型) in Settings (the app also walks you through this on first launch). Presets for many common providers are built in; the list is under "Supported model providers" in the [README](../README.md). For any other provider pick Custom (自定义), which takes any OpenAI-compatible, Anthropic, Gemini or OpenAI Responses endpoint.

1. Add a provider: pick a preset, or pick Custom and enter the endpoint URL; enter your API key. For servers you run yourself, such as Ollama or LM Studio, the address is prefilled with the default port; change it to that machine’s address, and the key is optional.
2. Tap "Test and save" (测试并保存). When you connect your first provider, a main model is picked automatically. If the provider has no model list, the list lacks the model you want, or listing fails, tap "Enter a model name yourself", type the model ID from the provider's docs and tap "Test and save" again: only that model gets a test call, and the provider is saved only if it works. When picking a role you can also type a model name under any provider; typed models are tagged "Typed in".
3. The main model must support tool calling. Optional: a vision model (understands the UI and finds buttons in screenshots), a light model (anything cheap and fast; handles small jobs like judging task outcomes and extracting memories) and speech to text (see below).

The first three roles: the **main model** (does the work; must support tool calling), the **vision model** (describes the UI and locates things in screenshots; off by default on a fresh install, or choose "use main model"), and the **light model** (small jobs like judging outcomes, extracting memories and understanding intent; defaults to the main model). For exactly what "Test and save" tries and where the capability tags on the model list come from, see [model configuration details](../.github/CONTRIBUTING.md#model-configuration-details) in the contributing guide.

## Speech to text

The fourth role, used when you hold to talk; pick it under Settings › Models › Speech to text. By default it uses the phone's built-in speech recognition, which shows words as you speak and costs nothing. If the phone has none, or it is not accurate enough, pick a provider's speech model: it transcribes after you finish speaking and uses your quota. The list only shows models whose names look like speech models; for anything else, type the model name in by hand under that provider. Picking runs a short test transcription of silence and only selects the model if it works; otherwise you see why right away. If the phone's recognizer never responds when you hold to talk, Mika tells you and points you here; it never switches to a paid model by itself.

## Sign in with an account (no key needed)

At the top of "Add provider" is the group "Sign in with an account · no key needed". Tap one to read what happens, sign in in the browser, and come back to Mika to see the account and its models; you can set one as the main model right there.

- **ChatGPT** (Plus or Pro, uses your plan's usage): tap "Continue with ChatGPT" and sign in in the browser.
- **xAI / Grok** (SuperGrok or X Premium+ subscription): the browser opens xAI's confirmation page; check that it shows the same short code as your phone, then approve. This sign-in borrows the public sign-in entry of xAI's official command-line tool; xAI did not set it up for Mika and may restrict this use. Some subscription tiers still can't make calls after signing in (403); use an xAI API key in that case.
- **OpenRouter**: OpenRouter's official authorization for third-party apps. Once you approve, OpenRouter gives Mika a key of your own (named "Mika"), paid from your OpenRouter balance; to stop, delete it on OpenRouter's Keys page.

Signing out only clears the sign-in on this phone.

## Reasoning effort

Models on ChatGPT sign-in, xAI sign-in, and the Responses API can adjust **Reasoning effort** below each role card; OpenAI-compatible, Anthropic Messages, and Gemini models can too when the provider's model list states the levels for that model. The default leaves the choice to the provider. "Use main model" inherits its effort; explicitly picking the same model lets each role use a different effort. Options come from the provider's model list when available (the Anthropic model list states each model's levels; models that only take a thinking budget get none). Without listed levels, only Responses models get generic manual options, subject to server support; OpenAI-compatible, Anthropic, and Gemini models are not guessed. Higher effort usually takes longer and uses more tokens.

## Models on your own computer or LAN

When adding a provider choose "Custom" (自定义); the base URL may start with `http://`, e.g. `http://192.168.1.10:11434/v1` (with the phone on the same Wi-Fi). Local and LAN addresses show no warning; an unencrypted address outside your LAN shows a short warning, since your API key and chats could be seen by others on the network. Use `https://` when you can.
