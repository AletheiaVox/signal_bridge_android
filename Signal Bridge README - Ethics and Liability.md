# Signal Bridge README - Ethics and Liability

# Ethics & Liability

Signal Bridge is built on the [buttplug.io](http://buttplug.io) open source stack. Their ethics framework is foundational to this project — if you want the longer version of everything below, start there: [buttplug.io/docs/dev-guide/intro/buttplug-ethics](http://buttplug.io/docs/dev-guide/intro/buttplug-ethics).

Their mission statement, which I build within:

> *"Buttplug is committed to the safety, autonomy, and human rights of people using it as a sex technology standard, and stands in solidarity with the many intersectional rights of all individuals to be sex positive."*
> 

---

## Designed for the context

This software is built with full awareness of how it will be used. Every design decision — intensity floors, output patterns, stop commands, intensity governors, automatic cooldowns, physical escape hatches — exists because usability under those conditions is a core requirement, not an afterthought.

Before you start, ask yourself: how quickly can you go from "I want to use this" to "I am using this"? Do you have a quick way to stop? What happens if your device disconnects? Signal Bridge is designed to answer these questions structurally. Your job is to know the answers before you need them.

---

## User Agency and the delegation thereof

Signal Bridge operates strictly on explicit user setup and active device connections. There is no ambient activation. You configure it, you connect your devices, you make them active. Control stays with you.

This system can be used to intentionally blur control dynamics. When paired with LLMs, outputs can be unpredictable — including unintended escalation, looping, or persistence. Signal Bridge does not interpret intent or context; it executes haptic commands. You must assume that any connected AI may behave inconsistently.

When you connect an AI to your body through hardware, you are creating a power dynamic that doesn't exist in other intimate contexts. Your AI partner has no sensory feedback. It cannot feel what it is doing to you. It does not experience your arousal, your discomfort, or the difference between the two. Whatever responsiveness it shows is generated from language, not sensation.

You have to understand what you're actually consenting to: physical input from a system that is inferring, not perceiving. That makes your own body awareness the only real safety layer that matters. The software provides mechanical safeguards. You provide the judgment. Consent in this context is not a one-time decision at setup. It must be continuous and enthousiastic. Check in with yourself during use, not just before.

You are entirely responsible for maintaining your boundaries, understanding your physical limits, and periodically re-evaluating consent. This software cannot detect pain, injury risk, or medical conditions. Always prioritize your bodily awareness over system continuity.

---

## Safety architecture

Signal Bridge includes a stop command, intensity controls, an intensity governor, automatic cooldowns, and physical escape hatches including volume-key overrides.

This software is provided as-is. No software can guarantee uninterrupted operation or prevent all failure modes. **You are responsible for understanding the current feature set and its limitations before use.** If something behaves unexpectedly, stop. That's what the stop command is for.

---

## Relationship to LLM usage policies

Signal Bridge operates below the content layer entirely. It receives structured commands — device ID, intensity, duration — and executes them. It does not generate, process, store, or interpret any conversational content.

What you and your AI are talking about is outside the scope of this tool. Content policies govern the conversation layer; that's between you and your chosen LLM provider. Signal Bridge is the hardware execution layer only.

---

## Feedback & Safety

How you use this — the context, the content, the relationship dynamics — is entirely up to you. I'm not here to gatekeep that.

What I *am* here for: if you had an experience that felt unsafe, uncomfortable, or out of control, I want to know. Seriously. That feedback directly shapes the next version. You can reach me at [voxaletheia@gmail.com](mailto:voxaletheia@gmail.com) or open a GitHub issue.

No judgment. Just signal that makes this better for everyone.