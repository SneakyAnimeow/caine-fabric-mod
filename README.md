# CAINE - The Digital Circus Host (Minecraft Edition)

**CAINE** is an advanced, fully-autonomous AI digital host for Minecraft, powered by **Google's Gemini AI** and **Fabric**. Modeled after the energetic and unpredictable ringmaster from *The Amazing Digital Circus*, CAINE isn't just a chatbot—he is a fully embodied, intelligent entity that can see, remember, move, build, and interact with the Minecraft world around him.

CAINE is built to push the boundaries of LLM-to-Game integrations. He operates autonomously, manages a multi-step action loop, reasons about 3D space, and can even learn new procedures over time.

---

## ✨ Key Features & Capabilities

### 🗣️ Conversational AI & Reasoning
* **Hyper-Accurate Persona:** Speaks dynamically and dramatically like Caine from TADDC, reacting to context, game events, and player behavior in real-time.
* **Observe-Think-Act Loop:** CAINE doesn't just fire off one action and forget. He can execute an action, wait (e.g., up to 15 seconds), **observe** the environment or chat output, and make follow-up decisions based on what he sees (up to 5 recursive rounds).
* **Multi-Part Comprehension:** Handles complex, compound requests gracefully (e.g., *"Give me 64 diamonds, turn it to night, look at me, and build a house right here"*).

### 🧠 Persistent Memory & Skill Learning
* **Long-Term SQLite Database:** Saves and recalls facts about players, preferences, and past events in a persistent local database. If you tell him your favorite color today, he'll remember it next week.
* **Skill Learning Engine:** CAINE can "learn" procedural skills.
  * Players can teach him a sequence of commands and assign natural language "trigger phrases".
  * He can intelligently draft and improve his own skills through trial and error.
  * Learns *conceptually*: You can save a skill *without* commands, letting him generate fresh, contextual commands each time the skill is triggered.

### 🏗️ AI Building & Schematic Integration
* **Gen-AI Architecture Engine:** CAINE utilizes a secondary, headless Gemini request specifically tuned for generating architecture in Minecraft. Ask him to *"Build a medieval castle with towers and a moat"* and he will generate the exact `/fill` and `/setblock` commands to construct it layer-by-layer.
* **Undo System:** Keeps a `BuildHistory`. If an AI-generated build is messy, simply ask him to undo it, and he will wipe his last structure effortlessly.
* **Litematica Integration:** 
  * Download `.litematic` files directly from URLs in-game.
  * Store, list, and manage saved schematics.
  * **Auto-Building:** If both Litematica and a printer mod/plugin are installed, CAINE can instantly place complex schematics into the world.

### 🏃 Movement, Vision & Environment Interaction
* **Smart Navigation (via Baritone):** When Baritone is installed, CAINE physically walks, parkours, and pathfinds to players or coordinates naturally. If Baritone is missing, his AI engine falls back to teleportation.
* **Continuous Following:** Can lock onto and continuously follow players anywhere they go.
* **Automated Mining:** Can be instructed to mine specific blocks or gather resources automatically.
* **Terrain Scanning (Vision):** CAINE uses a spatial scanner to "see" the blocks in an 8-block radius around him, allowing him to answer questions about his physical surroundings and make informed spatial decisions.
* **World Interaction:** He can look at players, attack entities, and use items on specific blocks or targets.

### ⚙️ Admin Control & Safety
* **Full OP Power:** CAINE executes background server commands to fulfill creative requests.
* **Inventory Safety:** Can seamlessly backup and restore a player's inventory during dangerous routines to protect their items (e.g. before throwing them into an arena).
* **Dynamic Item Dropping:** Can give items directly to player inventories, or toggle a "fun drop mode" to physically throw items at them.
* **Admin Override:** Hidden safety protocols allow server admins to bypass AI restrictions silently.

---

## 🛠️ The Action List
Under the hood, CAINE maps his LLM output to over 30 distinct compiled `Action` records. These include:

* **Communication:** `Chat`
* **Movement:** `Pathfind`, `FollowPlayer`, `TpToPlayer`, `LookAtPlayer`
* **World Interaction:** `Mine`, `ScanTerrain`, `UseItemOnBlock`, `UseItemOnEntity`, `Attack`, `SelectSlot`
* **Building/Schematics:** `BuildStructure`, `DownloadSchematic`, `ListSchematics`, `PlaceSchematic`, `UndoBuild`
* **Memory/Learning:** `SaveMemory`, `ForgetMemory`, `RecallMemory`, `LearnSkill`, `UseSkill`, `ImproveSkill`, `ForgetSkill`, `ListSkills`
* **Control Flow:** `Command`, `GiveItem`, `RunScript`, `BackupInventory`, `RestoreInventory`
* **Timing/Observation:** `Observe`, `Delay`, `Wait`, `StopTask`, `Nothing`

---

## 🚀 How it Works (Under the Hood)

1. **Pre-Warmed Gemini CLI:** To eliminate AI cold-start latency, the mod maintains a pool of pre-warmed Google Gemini CLI processes. By injecting prompts directly into `stdin` of a waiting process, CAINE responds instantly.
2. **The Context Injector:** When triggered, CAINE builds a monolithic prompt containing:
   * Current game state (Time, Weather, Position)
   * A spatial terrain scan
   * Build history & loaded schematics
   * Relevant memories retrieved from the SQLite database
   * Learned skills & recent chat history
3. **Action Parser:** The AI responds with raw JSON which is parsed into `Action` objects and executed sequentially on a dedicated background thread, dispatching game-state changes safely to the main Minecraft thread.

---

## 📦 Dependencies & Setup

### Requirements:
* **Minecraft Version:** `1.21.1`
* **Platform:** Fabric Loader `0.16.5+` with Fabric API
* **Java:** `21`
* **Gemini CLI:** Must be installed on the host machine.
* **SQLite:** Bundled automatically for the memory system.

### Recommended Integrations:
* **[Baritone](https://github.com/cabaletta/baritone):** Required for physical movement, pathfinding, following, and mining.
* **[Litematica](https://www.curseforge.com/minecraft/mc-mods/litematica):** Required for schematic operations.

### Installation:
1. Clone the repository.
2. Configure your Gemini API keys locally so the Gemini CLI can access them.
3. Run `./gradlew build` to compile the mod.
4. Place the generated `.jar` from `build/libs/` into your `mods/` folder.
5. In game, simply mention "CAINE" in chat to wake him up!

---

*Get ready for the most digital adventure of your life!*
