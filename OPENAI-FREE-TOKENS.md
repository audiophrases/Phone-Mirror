# OpenAI: free daily tokens on this account

What the OpenAI dashboard showed on 2026-11-16 (organization "Personal" → Settings → Data controls → Sharing). Look
there again before relying on it: the offer and its list of models can change.

- **Sharing is on for all projects.** Both settings on that page say *Enabled for all projects*, including *Share
  inputs and outputs with OpenAI*: OpenAI may use what the apps send (students' text included) and what it answers to
  improve and train its models.
- **"You're enrolled for complimentary daily tokens."** Free daily usage on traffic shared with OpenAI:
  - up to **250 thousand tokens a day** across gpt-5.4, gpt-5.2, gpt-5.1, gpt-5, gpt-4.1, gpt-4o, o1 and o3;
  - up to **2.5 million tokens a day** across gpt-5.4-mini, gpt-5.4-nano, gpt-5-mini, gpt-5-nano, gpt-4.1-mini,
    gpt-4.1-nano, gpt-4o-mini, o3-mini and o4-mini.
- *"Usage beyond these limits, as well as usage for other models, will be billed at standard rates. Some limitations
  apply."*

## What it means for the apps

- **Free:** a model on the 2.5 million list, within the day's amount. `gpt-4o-mini` is the one the apps use. A grammar
  correction or hint is about 700 tokens, so the free amount covers a few thousand a day, shared by every app on the
  account.
- **Billed:** every model not on those lists, such as `gpt-6-luna`, `gpt-5.6-*` and `gpt-6.1-*`, and audio and
  image models such as `whisper-1`. Moderation (`omni-moderation-latest`) costs nothing either way.
- **Before switching a model**, check it is on the list: a newer model can be better without being free. Prices on
  developers.openai.com/api/docs/pricing on 2026-11-16, per million tokens in / out: gpt-4o-mini $0.15 / $0.60,
  gpt-6-luna $0.10 / $0.50, gpt-6.1-terra $1.00 / $5.00.
- **To keep students' text out of training**, choose *Enabled for selected projects* and leave out the OpenAI project
  whose key the classroom apps use (its calls then get no free tokens), or disable sharing.

The same note is in every project that calls the OpenAI API: Communicator, HelloChat, Pixel Plaza, PinPlay and
Phone Mirror.

## In this project

- **ConvoRecorder** transcribes recordings with `whisper-1`
  (`ConvoRecorder/app/src/com/classaudio/recorder/Transcriber.java`; base URL and model are set in the app). Audio
  models are not on the free lists, so transcription is **billed** by the minute of audio.
- ClassAudioHook only names the ChatGPT app (`com.openai.chatgpt`); it makes no API calls.
