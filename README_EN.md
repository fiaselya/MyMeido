# MyMeido

**English** | [中文](README.md)

Raise a **maid of your own** in Minecraft. She takes orders and works, hits back when she's hit,
and gets on with her day on her own. Hook her up to any OpenAI-compatible model and she will
**talk with you, remember what you've said, and come over to start a conversation herself.**

- Minecraft **1.21.1** + **Fabric**
- Singleplayer, LAN, and dedicated servers
- **Works without any AI.** Without a model she answers with her built-in lines. A real
  conversation only unlocks once you connect one.

---

## Features

### 1. She works

| Mode | What she does |
|---|---|
| Wander | Roams around her home, fights back if attacked |
| Hold position | Stands still where she is |
| Come here | Walks to the position you point at |
| Bind home (bed) | Remembers a "home" spot and goes back to sleep there at night |
| Fishing | Casts and reels on her own; catches follow the vanilla fishing loot table |
| Farming | Plants, waits for growth, harvests, and replants (wheat / nether wart) |
| Guard the front | Stays on her post block; charges anything hostile that comes close |
| Drop items | Drops things from her inventory at a spot you choose |

- **Her own inventory.** Whatever you put in it, she uses. She eats on her own when she's
  carrying food, and when she's hurt.
- **Tools only when needed.** She pulls out the right tool only while working — a rod for
  fishing, a weapon for fighting. Her hands stay empty otherwise.
- **Sleeping at night.** When she goes home to sleep she stows anything storable in the chest
  next to her bed, keeping food, weapons, and armor on her.
- **Always loaded.** The 3×3 chunks around her stay loaded, so she keeps working and keeps
  coming home even when you walk far away.

### 2. She fights

- She hits back when attacked, and she fights *hit-and-run*: back off, then return the blow
  rather than standing there trading hits.
- In Guard mode she charges anything hostile that enters her range — but only swings if she's
  actually holding a weapon.
- Her expression changes when she's hit, when she hits, when she's happy, and when she's sad.
  You can also set it by hand with `/mymeido emotion`.

### 3. She talks (bring your own model)

The short version:

- **No API configured** = built-in mode. Right-click her and she answers with her own lines.
  She will **not** reply to your chat messages and will **not** start conversations.
- **API configured** = AI mode. Type in chat and she answers. She gets context, memory,
  a persona, and she'll come talk to you on her own.

**Any OpenAI-compatible endpoint works** — a local llama.cpp / LM Studio / Ollama
compatibility port, or any hosted service. Configure it in `config/mymeido/chat/api.txt`:

```ini
api_base_url=http://127.0.0.1:8080/v1    # your endpoint, include /v1
api_key=                                  # fill in if it needs auth
api_model=qwen3-4b                        # model name
```

Apply changes with `/mymeido aireload`, check the current state with `/mymeido aistatus`,
and see the full walkthrough with `/mymeido aiguide`.

Two compatibility switches, for endpoints that don't play by the standard rules:

```ini
# Server says "non-streaming not supported" / a 400 mentioning stream → set true to use SSE
api_stream=false
# Extra request headers, "Name:Value; Name:Value" — for endpoints whose auth header
# isn't called Authorization, or that expect a spoofed client header
api_extra_headers=
```

**Persona.** `config/mymeido/personas/<skin>.txt` holds her personality, speech quirks,
how she addresses you, and her likes and dislikes. Whatever you write by hand is never
overwritten. After each exchange she also merges what she picked up into an "auto" section
of that file (turn it off with `persona_auto_update=false`).

**Memory.** Everything said and the key-point summaries are saved with the world (NBT is the
single source of truth), with a readable Markdown snapshot mirrored into
`config/mymeido/memory/`.

**She starts conversations.** Stay near the player who created her for long enough
(5 minutes by default) and, **during the day**, she'll walk over and speak first. Once it's
dark she leaves you alone. Twice an hour at most, and the line is generated fresh by the
model — avoiding what she recently said and adjusting her tone to your current relationship.
`/mymeido proactive` shows why she hasn't spoken yet; `/mymeido proactive say` makes her
say something right now.

**Language (bilingual EN/中文)**: the mod's own text — command feedback, her built-in lines,
the config files it generates, and the prompts sent to the model (that is, the language she
speaks to you) — **follows your game language automatically**: Chinese environment gets
Chinese, everything else gets English. To pin it, set `language=auto|zh_cn|en_us` in
`config/mymeido/settings.txt`, or just run `/mymeido lang <value>` (takes effect immediately
and writes the file). Item names and keybind labels go through Minecraft's own language
files and follow your game language setting.

### 4. She remembers who you are

- **Affection.** It goes up when you treat her well, give her gifts, or fight alongside her,
  and down when she gets hit. The number is deliberately never shown — it only affects her
  tone and her mood.
- **Maid contract.** A new world gives you one contract. One contract, one maid, and it's
  visible at the item level (stack size 1, still consumed in creative mode). A crafting
  recipe has been added so contracts are obtainable.
- **Command alarm.** A way to call her over without using the contract, from any distance.
  One-to-one, so it never reaches the wrong maid.

---

## Installation

1. Install **Fabric Loader** (≥ 0.19.0) and **Fabric API** for **1.21.1**.
2. Drop `mymeido-x.y.z.jar` into `.minecraft/mods/`.
3. Launch the game. On first launch the config folder and templates are generated under
   `config/mymeido/`.

## Quick start

1. Craft a **maid contract**: 8 paper in a ring with a nether star in the middle.
2. Right-click the contract — chat lists the available skin numbers — then type a number,
   and there she is.
3. Right-click her to open the mode menu and pick **Wander** to let her do her own thing.
   To assign work, follow the prompts that appear in chat.

## Skins (important)

**This mod ships no character textures at all.** Characters *grow out of a folder*:
**one PNG = one character = one number in the picker, with no upper limit.**
Just drop images into `.minecraft/config/mymeido/skins/` — **the file name is the
character's name**:

| File you drop in | Shown in the menu | Character id (stored in the save) |
|---|---|---|
| `yuuka.png` | `1) yuuka` | `yuuka` |
| `Sakurai Momoka.png` | `2) Sakurai Momoka` | `momoka` ※ |

※ In 0.1.0 this character's id was already `momoka`, so **existing saves and existing
persona files keep working** and she won't be renamed to something else. Any character
added from now on gets `id = file name with spaces/underscores/case removed`.

- Numbers are assigned automatically in **file-name order** — however many images you add
  is however many choices you get.
- **No game restart needed to add or swap images.** Press `F3+T` to reload resources
  (the folder is rescanned and textures are reread).
- Ids ignore case, spaces, and underscores — `Sakurai Momoka.png` and `sakurai_momoka.png`
  are treated as the same character, so you won't get a duplicate out of nowhere.
- If an image is missing or unreadable, that maid **falls back to vanilla Steve**, but her
  character id does not change — so deleting an image will never make the maid in an old
  save "swap faces".
- With **no images at all**, the menu offers 4 built-in placeholder slots
  (hoshino / rikka / kotone / momoka) textured as vanilla Steve, so a fresh install isn't
  met with a menu that does nothing. Drop images in and they take those slots over.

`/mymeido skins` lists which files are currently recognized and where the folder is;
`/mymeido skins reload` forces a rescan (and creates persona templates for new characters).

Skins belong to their original artists — please make sure you have the right to use them.
That's also why this mod doesn't bundle any.

## Keybinds

| Key | Action |
|---|---|
| `G` | Open the chat box (on multiplayer use `@name`) |
| `H` | Open the conversation history panel (key points / what you said / what she said) |

## Commands

```
/mymeido summon [skin]      Summon a maid
/mymeido mode <mode>        Switch mode (hold / come here / fishing / farming / guard ...)
/mymeido contract           Get a maid contract
/mymeido alarm              Use the alarm to find her
/mymeido name <name>        Rename her
/mymeido skin <skin>        Change her skin
/mymeido skins [reload]     List recognized skin files / rescan the skins folder
/mymeido favor              Show her affection tier
/mymeido chat <message>     Say something to her directly (no chat box needed)
/mymeido say <message>      Make her say a line (does not go through the model)
/mymeido emotion [emotion]  Set her expression manually
/mymeido inventory          Show her inventory
/mymeido proactive [say]    Proactive chat: show status / make her speak now
/mymeido aistatus           Show AI backend status (URL / streaming / custom headers / persona updates)
/mymeido aiguide            How to connect a model (several routes explained)
/mymeido aireload           Hot-reload api.txt after editing
/mymeido lang [auto|zh_cn|en_us]  Show / switch the language this mod speaks (writes settings.txt)
/mymeido persona [extract]  Show her persona file / have the model summarize it
/mymeido memory             Show her memory key points
```

## FAQ

**She ignores what I type.** Run `/mymeido aistatus` first: no API configured means
built-in mode, which is by design (she only has her canned lines). If it is configured,
check whether the URL is missing `/v1`, whether the model name is right, or whether the
endpoint needs `api_stream=true`.

**She replies with nothing / empty messages.** You've most likely picked a reasoning model,
which puts the whole reply into `reasoning_content`. Switch to a non-reasoning model —
`/mymeido aistatus` will point this out when it happens.

**I can't find the items in the creative inventory.** Both items are also placed in the
vanilla **Redstone** tab. The custom "MyMeido" tab is pushed onto **page 2** by Fabric API —
click the `›` at the right end of the tab row to page over.

**The world behind the chat box / history panel is very blurry.** 1.21 applies gaussian
blur to all screens by default; this mod replaces it with a translucent gradient. If your
modpack put it back, something else in the pack did that.

## License

MIT. Maid skins, model weights, and any other third-party assets are not included in this
repository and remain the property of their respective authors.
