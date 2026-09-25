# Graph Report - gildedfixes  (2026-09-25)

## Corpus Check
- 147 files · ~403,819 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3070 nodes · 7378 edges · 152 communities (67 shown, 70 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS · INFERRED: 7 edges (avg confidence: 0.83)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `16a7dd59`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- .onEnable
- .onEnable
- MillenniumNet
- AdvertContext
- PlayerWipe
- ActiveRankModule
- MillenniumNet
- LocalAiModule
- ActiveRankModule
- PunishmentBridge
- MuteStore
- StaffMonitorModule
- StashModule
- MuteStore
- StashModule
- coltcore/core/modules/StashModule.java
- Script
- Script
- StaffMonitorModule
- SeedCorpus
- EntityLimitModule
- EntityLimitModule
- LocalAiModule
- PlayerWipe
- PlayerWipeModule
- org.bukkit.plugin.Plugin
- JoinPacketIsolation
- PlayerWipeModule
- java.util.regex.Pattern
- ChatGuardModule
- CreativeGuardModule
- ColtCore — AI training and detection results
- AntiAdPipeline
- GildedCore — AI training and detection results
- SyntheticPlayerLoader
- TrainMillennium
- KelpGrowthModule
- org.bukkit.entity.Player
- AntiAdPipeline
- NeuralModel
- ChatGuardModule
- ChatLimiterModule
- ChatLimiterModule
- Mute
- NeuralModel
- RewardsModule
- ReviewGui
- RewardsModule
- P
- Mutations
- Mutations
- org.bukkit.block.Block
- ColtCore and GildedCore — the anti-advertising filter, rebuilt on a text-native Millennium 5
- Prior
- Phonetics
- AntibotGuard
- LinearModel
- Phonetics
- ReviewGui
- java.io.DataInputStream
- RedstoneThrottle
- AntibotGuard
- org.bukkit.Material
- DiscordBridge
- ColtCorePlugin
- org.bukkit.Location
- GildedCorePlugin
- RedstoneThrottle
- LogicCenter
- PatternPack
- TextFeatures
- LogicCenter
- PatternPack
- TextFeatures
- SeedCorpus
- PunishmentBridge
- Prior
- build_corpus_v2.py
- org.bukkit.plugin.java.JavaPlugin
- DupeIpParser
- org.bukkit.command.CommandSender
- LocalContextAggregator
- DupeIpParser
- LocalContextAggregator
- Tlds
- LinearModel
- Tlds
- coltcore/core/modules/EntityLimitModule.java
- Lexicon
- Lexicon
- AdvertContext
- VanishAnnouncer
- org.bukkit.event.EventHandler
- tld_corpus.py
- BroadcastCompat
- BroadcastCompat
- .ChatGuardModule
- RenameContextTracker
- HandWritten
- HandWritten
- com.coltcore:ColtCore
- com.gildedmc:GildedCore
- RenameContextTracker
- FlagReviewStore
- ContextAwareAntiAd
- ContextAwareAntiAd
- KelpGrowthModule
- FancyChatHack
- NeuralWordCache
- Mute
- NeuralWordCache
- ConsoleGuard
- ModelProbe
- P
- SyntheticPlayerLoader
- DiscordBridge
- RewardsGui
- Backend
- ChatBlockProbe
- CommandTemplate
- CommandTemplate
- BareProbe
- ChatMineFilter
- AdvertFormCheck
- patch_chatguard.py
- TldCheck.java
- ZeroTokenCheck.java
- patch_advertonly.py
- patch_content.py
- patch_dead.py
- patch_dos.py
- patch_millenniumnet.py
- patch_scripts.py
- patch_surfaces.py
- RewardsGui
- RedeemCodeModule
- .ChatGuardModule

## God Nodes (most connected - your core abstractions)
1. `ColtCorePlugin` - 134 edges
2. `GildedCorePlugin` - 134 edges
3. `ChatGuardModule` - 98 edges
4. `ChatGuardModule` - 98 edges
5. `EntityLimitModule` - 56 edges
6. `StaffMonitorModule` - 56 edges
7. `EntityLimitModule` - 56 edges
8. `StaffMonitorModule` - 56 edges
9. `RewardsModule` - 54 edges
10. `RewardsModule` - 54 edges

## Surprising Connections (you probably didn't know these)
- `ColtCorePlugin` --references--> `ActiveRankModule`  [EXTRACTED]
  ColtCore/src/main/java/com/coltcore/core/ColtCorePlugin.java → ColtCore/src/main/java/com/coltcore/core/modules/ActiveRankModule.java
- `ColtCorePlugin` --references--> `AntiAdPipeline`  [EXTRACTED]
  ColtCore/src/main/java/com/coltcore/core/ColtCorePlugin.java → ColtCore/src/main/java/com/coltcore/core/modules/AntiAdPipeline.java
- `ColtCorePlugin` --references--> `AntibotGuard`  [EXTRACTED]
  ColtCore/src/main/java/com/coltcore/core/ColtCorePlugin.java → ColtCore/src/main/java/com/coltcore/core/modules/AntibotGuard.java
- `ColtCorePlugin` --references--> `ChatGuardModule`  [EXTRACTED]
  ColtCore/src/main/java/com/coltcore/core/ColtCorePlugin.java → ColtCore/src/main/java/com/coltcore/core/modules/ChatGuardModule.java
- `ColtCorePlugin` --references--> `ChatLimiterModule`  [EXTRACTED]
  ColtCore/src/main/java/com/coltcore/core/ColtCorePlugin.java → ColtCore/src/main/java/com/coltcore/core/modules/ChatLimiterModule.java

## Import Cycles
- None detected.

## Communities (152 total, 70 thin omitted)

### Community 0 - ".onEnable"
Cohesion: 0.06
Nodes (27): ActiveRankModule, AntiAdPipeline, AntibotGuard, ChatGuardModule, ChatLimiterModule, ConsoleGuard, ContextAwareAntiAd, DeepslateDecoyModule (+19 more)

### Community 1 - ".onEnable"
Cohesion: 0.06
Nodes (27): ActiveRankModule, AntiAdPipeline, AntibotGuard, ChatGuardModule, ChatLimiterModule, ConsoleGuard, ContextAwareAntiAd, DeepslateDecoyModule (+19 more)

### Community 4 - "PlayerWipe"
Cohesion: 0.11
Nodes (3): IntegratedCoreModuleX, PendingWipe, PlayerWipe

### Community 5 - "ActiveRankModule"
Cohesion: 0.06
Nodes (11): ActiveRankModule, ManagedTask, Check, DiagnosticsModule, Level, FAIL, PASS, WARN (+3 more)

### Community 7 - "LocalAiModule"
Cohesion: 0.06
Nodes (10): ManagedTask, NeuralModel, LocalAiModule, Fallback, APPLY, DROP, ESCALATE, ManagedTask (+2 more)

### Community 8 - "ActiveRankModule"
Cohesion: 0.06
Nodes (11): ActiveRankModule, ManagedTask, Check, DiagnosticsModule, Level, FAIL, PASS, WARN (+3 more)

### Community 11 - "StaffMonitorModule"
Cohesion: 0.10
Nodes (5): ItemStack, ManagedTask, Override, Menu, StaffMonitorModule

### Community 12 - "StashModule"
Cohesion: 0.10
Nodes (7): AirGapResult, StashAlertHook, StashModule, TamperType, BREAK, INTERACT, PLACE

### Community 14 - "StashModule"
Cohesion: 0.06
Nodes (16): Cached, LitematicReader, RegionBlocks, AirGapResult, StashAlertHook, StashModule, TamperType, BREAK (+8 more)

### Community 15 - "coltcore/core/modules/StashModule.java"
Cohesion: 0.11
Nodes (9): DeepslateDecoyModule, DeepslateDecoyModule, java.net.http.HttpClient, java.security.SecureRandom, java.util.concurrent.ThreadLocalRandom, org.bukkit.event.block.BlockBreakEvent, org.bukkit.event.block.BlockPlaceEvent, org.bukkit.event.world.ChunkLoadEvent (+1 more)

### Community 17 - "Script"
Cohesion: 0.06
Nodes (29): Pattern, Script, ARABIC, ARMENIAN, BENGALI, CYRILLIC, DEVANAGARI, ETHIOPIC (+21 more)

### Community 18 - "Script"
Cohesion: 0.06
Nodes (29): Pattern, Script, ARABIC, ARMENIAN, BENGALI, CYRILLIC, DEVANAGARI, ETHIOPIC (+21 more)

### Community 19 - "StaffMonitorModule"
Cohesion: 0.10
Nodes (5): ItemStack, ManagedTask, Override, Menu, StaffMonitorModule

### Community 21 - "EntityLimitModule"
Cohesion: 0.11
Nodes (3): EntityLimitModule, ManagedTask, org.bukkit.entity.Entity

### Community 23 - "LocalAiModule"
Cohesion: 0.06
Nodes (10): ManagedTask, NeuralModel, LocalAiModule, Fallback, APPLY, DROP, ESCALATE, ManagedTask (+2 more)

### Community 24 - "PlayerWipe"
Cohesion: 0.11
Nodes (3): IntegratedCoreModuleX, PendingWipe, PlayerWipe

### Community 25 - "PlayerWipeModule"
Cohesion: 0.11
Nodes (5): Category, YamlConfiguration, PlayerWipeModule, QueuedWipe, Session

### Community 26 - "org.bukkit.plugin.Plugin"
Cohesion: 0.12
Nodes (6): ManagedTask, SchedulerCompat, ManagedTask, SchedulerCompat, org.bukkit.plugin.Plugin, org.bukkit.scheduler.BukkitTask

### Community 27 - "JoinPacketIsolation"
Cohesion: 0.12
Nodes (7): PacketAdapter, JoinPacketIsolation, com.comphenix.protocol.events.PacketAdapter, com.comphenix.protocol.events.PacketContainer, com.comphenix.protocol.PacketType, PacketAdapter, JoinPacketIsolation

### Community 28 - "PlayerWipeModule"
Cohesion: 0.11
Nodes (5): Category, YamlConfiguration, PlayerWipeModule, QueuedWipe, Session

### Community 29 - "java.util.regex.Pattern"
Cohesion: 0.20
Nodes (4): ConsoleGuard, Handler, java.util.logging.Handler, java.util.regex.Pattern

### Community 32 - "CreativeGuardModule"
Cohesion: 0.16
Nodes (6): CreativeGuardModule, ItemStack, CreativeGuardModule, ItemStack, org.bukkit.event.inventory.InventoryCreativeEvent, org.bukkit.event.player.PlayerDropItemEvent

### Community 33 - "ColtCore — AI training and detection results"
Cohesion: 0.10
Nodes (20): 10. The two Grim forks, 11. Cheat source collection — blocked, and what unblocks it, 1. The chat model, 2. The adversarial suite, 3. Slurs — read this before testing, 4. Signs and item names, 5. Console log scanning, 6. Anticheat architecture — two stages (+12 more)

### Community 35 - "GildedCore — AI training and detection results"
Cohesion: 0.10
Nodes (20): 10. The two Grim forks, 11. Cheat source collection — blocked, and what unblocks it, 1. The chat model, 2. The adversarial suite, 3. Slurs — read this before testing, 4. Signs and item names, 5. Console log scanning, 6. Anticheat architecture — two stages (+12 more)

### Community 36 - "SyntheticPlayerLoader"
Cohesion: 0.21
Nodes (4): SyntheticPlayerLoader, java.lang.reflect.Constructor, java.lang.reflect.Field, java.lang.reflect.Method

### Community 37 - "TrainMillennium"
Cohesion: 0.13
Nodes (3): MillenniumNet, Metrics, TrainMillennium

### Community 39 - "org.bukkit.entity.Player"
Cohesion: 0.21
Nodes (3): VanishSupport, VanishSupport, org.bukkit.entity.Player

### Community 41 - "NeuralModel"
Cohesion: 0.15
Nodes (4): DataInputStream, Prediction, NeuralModel, Pass

### Community 43 - "ChatLimiterModule"
Cohesion: 0.17
Nodes (4): ChatLimiterModule, CheckResult, MessageEntry, PlayerState

### Community 44 - "ChatLimiterModule"
Cohesion: 0.17
Nodes (4): ChatLimiterModule, CheckResult, MessageEntry, PlayerState

### Community 46 - "NeuralModel"
Cohesion: 0.15
Nodes (4): DataInputStream, Prediction, NeuralModel, Pass

### Community 47 - "RewardsModule"
Cohesion: 0.12
Nodes (3): ManagedTask, Reward, RewardsModule

### Community 48 - "ReviewGui"
Cohesion: 0.14
Nodes (5): Flag, FlagReviewStore, OfflinePlayer, OfflineHead, ReviewGui

### Community 49 - "RewardsModule"
Cohesion: 0.12
Nodes (3): ManagedTask, Reward, RewardsModule

### Community 53 - "org.bukkit.block.Block"
Cohesion: 0.08
Nodes (12): Action, SYNC, TURN_OFF, ManagedTask, RedstoneUnstaler, Action, SYNC, TURN_OFF (+4 more)

### Community 54 - "ColtCore and GildedCore — the anti-advertising filter, rebuilt on a text-native Millennium 5"
Cohesion: 0.11
Nodes (17): 10. Removed in earlier passes, 11. Build and verify, 1. The pipeline, as it actually runs, 2. The engine — Millennium 5, rebuilt for text, 3. What was measured, 4. Layer 3, and a design that was thrown away, 5. Known limits, 6. CheatDetector removal (+9 more)

### Community 58 - "LinearModel"
Cohesion: 0.18
Nodes (3): Example, LinearModel, Prediction

### Community 60 - "ReviewGui"
Cohesion: 0.23
Nodes (3): OfflinePlayer, OfflineHead, ReviewGui

### Community 61 - "java.io.DataInputStream"
Cohesion: 0.17
Nodes (4): DataOutputStream, DataOutputStream, java.io.DataInputStream, java.io.DataOutputStream

### Community 63 - "RedstoneThrottle"
Cohesion: 0.19
Nodes (3): ManagedTask, RedstoneThrottle, Tier

### Community 66 - "org.bukkit.Material"
Cohesion: 0.05
Nodes (16): CrafterBulkHopper, ItemStack, InventoryHolder, ItemStack, Sound, UiKit, CrafterBulkHopper, ItemStack (+8 more)

### Community 68 - "ColtCorePlugin"
Cohesion: 0.13
Nodes (4): ColtCorePlugin, PlayerPicker, PlayerTargetGate, RecentPlayerTracker

### Community 69 - "org.bukkit.Location"
Cohesion: 0.06
Nodes (11): BookSignProximity, ItemStack, BrokenSignEntry, SignContextTracker, SignEntry, BookSignProximity, ItemStack, BrokenSignEntry (+3 more)

### Community 70 - "GildedCorePlugin"
Cohesion: 0.13
Nodes (4): GildedCorePlugin, PlayerPicker, PlayerTargetGate, RecentPlayerTracker

### Community 71 - "RedstoneThrottle"
Cohesion: 0.19
Nodes (3): ManagedTask, RedstoneThrottle, Tier

### Community 72 - "LogicCenter"
Cohesion: 0.24
Nodes (6): Decision, ALLOW, PROCEED, REVIEW, LogicCenter, Verdict

### Community 75 - "LogicCenter"
Cohesion: 0.24
Nodes (6): Decision, ALLOW, PROCEED, REVIEW, LogicCenter, Verdict

### Community 78 - "SeedCorpus"
Cohesion: 0.27
Nodes (3): DumpCorpus, Sample, SeedCorpus

### Community 80 - "PunishmentBridge"
Cohesion: 0.14
Nodes (5): Backend, INTERNAL, LITEBANS, PunishmentBridge, Result

### Community 82 - "build_corpus_v2.py"
Cohesion: 0.16
Nodes (16): advert_hit_lines(), Corpus, dedup(), _dotted_allowed(), interior_windows(), leading_windows(), main(), mined_advert() (+8 more)

### Community 84 - "org.bukkit.plugin.java.JavaPlugin"
Cohesion: 0.09
Nodes (20): JoinMessage, PingCommand, JoinRewardModule, JoinMessage, PingCommand, JoinRewardModule, java.sql.Connection, net.kyori.adventure.text.Component (+12 more)

### Community 86 - "org.bukkit.command.CommandSender"
Cohesion: 0.14
Nodes (4): Override, Override, org.bukkit.command.Command, org.bukkit.command.CommandSender

### Community 87 - "LocalContextAggregator"
Cohesion: 0.33
Nodes (3): Entry, LocalContextAggregator, Verdict

### Community 90 - "LocalContextAggregator"
Cohesion: 0.33
Nodes (3): Entry, LocalContextAggregator, Verdict

### Community 92 - "LinearModel"
Cohesion: 0.18
Nodes (3): Example, LinearModel, Prediction

### Community 94 - "coltcore/core/modules/EntityLimitModule.java"
Cohesion: 0.22
Nodes (6): org.bukkit.Chunk, org.bukkit.entity.EntityType, org.bukkit.event.block.BlockDispenseEvent, org.bukkit.event.entity.EntitySpawnEvent, org.bukkit.event.vehicle.VehicleCreateEvent, org.bukkit.event.vehicle.VehicleMoveEvent

### Community 98 - "VanishAnnouncer"
Cohesion: 0.07
Nodes (16): Hook, FunctionalInterface, Override, RankContext, Reader, Toggle, VanishAnnouncer, Hook (+8 more)

### Community 99 - "org.bukkit.event.EventHandler"
Cohesion: 0.05
Nodes (13): Location, Location, io.papermc.paper.event.player.AsyncChatEvent, net.luckperms.api.LuckPerms, org.bukkit.event.block.SignChangeEvent, org.bukkit.event.EventHandler, org.bukkit.event.inventory.InventoryClickEvent, org.bukkit.event.inventory.PrepareAnvilEvent (+5 more)

### Community 101 - "tld_corpus.py"
Cohesion: 0.50
Nodes (4): main(), obfuscations(), Builds training rows that teach the classifier every TLD, not just .com. The…, The same host in the disguises normalization is designed to undo.

### Community 104 - ".ChatGuardModule"
Cohesion: 0.50
Nodes (3): LocalAiModule, MuteStore, PunishmentBridge

### Community 107 - "RenameContextTracker"
Cohesion: 0.13
Nodes (4): BookSignProximity, LocalContextAggregator, RenameContextTracker, RenameEntry

### Community 112 - "RenameContextTracker"
Cohesion: 0.13
Nodes (4): BookSignProximity, LocalContextAggregator, RenameContextTracker, RenameEntry

### Community 116 - "KelpGrowthModule"
Cohesion: 0.21
Nodes (3): KelpGrowthModule, org.bukkit.event.block.BlockGrowEvent, org.bukkit.event.block.BlockSpreadEvent

### Community 117 - "FancyChatHack"
Cohesion: 0.24
Nodes (6): FancyChatHack, Override, ChatOutputEvent, net.wurstclient.events.ChatOutputListener, net.wurstclient.hack.Hack, net.wurstclient.SearchTags

### Community 130 - "Backend"
Cohesion: 0.40
Nodes (4): Backend, EXTERNAL, INTERNAL, LITEBANS

### Community 153 - "RedeemCodeModule"
Cohesion: 0.06
Nodes (7): ConfigUpdater, YamlConfiguration, RedeemCodeModule, ConfigUpdater, YamlConfiguration, RedeemCodeModule, org.bukkit.configuration.file.YamlConfiguration

### Community 158 - ".ChatGuardModule"
Cohesion: 0.50
Nodes (3): LocalAiModule, MuteStore, PunishmentBridge

## Knowledge Gaps
- **129 isolated node(s):** `com.coltcore:ColtCore`, `PASS`, `WARN`, `FAIL`, `ALLOW` (+124 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 698 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **70 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GildedCorePlugin` connect `GildedCorePlugin` to `.onEnable`, `LocalAiModule`, `ActiveRankModule`, `StashModule`, `coltcore/core/modules/StashModule.java`, `StaffMonitorModule`, `EntityLimitModule`, `RewardsGui`, `PlayerWipeModule`, `.kitall`, `JoinPacketIsolation`, `RedeemCodeModule`, `org.bukkit.plugin.Plugin`, `.gradientBorder`, `ChatGuardModule`, `CreativeGuardModule`, `java.util.regex.Pattern`, `AntiAdPipeline`, `ChatLimiterModule`, `ReviewGui`, `RewardsModule`, `org.bukkit.block.Block`, `RedstoneThrottle`, `AntibotGuard`, `org.bukkit.Location`, `org.bukkit.plugin.java.JavaPlugin`, `org.bukkit.command.CommandSender`, `org.bukkit.event.EventHandler`, `.color`, `RenameContextTracker`, `ContextAwareAntiAd`, `KelpGrowthModule`, `.colorComponent`, `ConsoleGuard`?**
  _High betweenness centrality (0.082) - this node is a cross-community bridge._
- **Why does `ColtCorePlugin` connect `ColtCorePlugin` to `.onEnable`, `RewardsGui`, `ActiveRankModule`, `StaffMonitorModule`, `StashModule`, `coltcore/core/modules/StashModule.java`, `.kitall`, `EntityLimitModule`, `LocalAiModule`, `RedeemCodeModule`, `org.bukkit.plugin.Plugin`, `JoinPacketIsolation`, `PlayerWipeModule`, `java.util.regex.Pattern`, `CreativeGuardModule`, `AntiAdPipeline`, `KelpGrowthModule`, `ChatGuardModule`, `ChatLimiterModule`, `RewardsModule`, `org.bukkit.block.Block`, `AntibotGuard`, `ReviewGui`, `org.bukkit.Location`, `RedstoneThrottle`, `.gradientBorder`, `org.bukkit.plugin.java.JavaPlugin`, `org.bukkit.command.CommandSender`, `org.bukkit.event.EventHandler`, `.demote`, `RenameContextTracker`, `FlagReviewStore`, `ContextAwareAntiAd`, `.color`?**
  _High betweenness centrality (0.082) - this node is a cross-community bridge._
- **Why does `ChatGuardModule` connect `ChatGuardModule` to `.screen`, `AntiAdPipeline`, `org.bukkit.event.EventHandler`, `ColtCorePlugin`, `ActiveRankModule`, `.ChatGuardModule`, `PunishmentBridge`, `MuteStore`, `.termHits`, `FlagReviewStore`, `ContextAwareAntiAd`, `org.bukkit.plugin.java.JavaPlugin`, `LocalContextAggregator`, `LocalAiModule`, `ReviewGui`, `java.util.regex.Pattern`, `.typedAdvertHit`?**
  _High betweenness centrality (0.067) - this node is a cross-community bridge._
- **What connects `com.coltcore:ColtCore`, `PASS`, `WARN` to the rest of the system?**
  _129 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `.onEnable` be split into smaller, more focused modules?**
  _Cohesion score 0.06451612903225806 - nodes in this community are weakly interconnected._
- **Should `.onEnable` be split into smaller, more focused modules?**
  _Cohesion score 0.06451612903225806 - nodes in this community are weakly interconnected._
- **Should `MillenniumNet` be split into smaller, more focused modules?**
  _Cohesion score 0.10695187165775401 - nodes in this community are weakly interconnected._