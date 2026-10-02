# Graph Report - gildedfixes  (2026-10-02)

## Corpus Check
- 153 files · ~428,704 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3233 nodes · 7979 edges · 170 communities (76 shown, 80 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS · INFERRED: 29 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `31a3a14d`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- .onEnable
- .onEnable
- MillenniumNet
- VoteModule
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
- org.bukkit.event.Listener
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
- ConsoleGuard
- ChatGuardModule
- CreativeGuardModule
- ColtCore — AI training and detection results
- AntiAdPipeline
- GildedCore — AI training and detection results
- SyntheticPlayerLoader
- TrainMillennium
- KelpGrowthModule
- UiKit
- AntiAdPipeline
- NeuralModel
- ChatGuardModule
- ChatLimiterModule
- ChatLimiterModule
- Mute
- NeuralModel
- RewardsModule
- ReviewGui
- org.bukkit.entity.Player
- org.bukkit.inventory.Inventory
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
- RedstoneDestaler
- RedstoneThrottle
- AntibotGuard
- org.bukkit.Material
- org.bukkit.inventory.ItemStack
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
- java.util.regex.Pattern
- LocalContextAggregator
- Tlds
- LinearModel
- Tlds
- org.bukkit.entity.EntityType
- Lexicon
- Lexicon
- DiagnosticsModule
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
- com.sk89q.worldedit.extent.clipboard.Clipboard
- ContextAwareAntiAd
- ContextAwareAntiAd
- KelpGrowthModule
- FancyChatHack
- NeuralWordCache
- Mute
- NeuralWordCache
- VanishAnnouncer
- ModelProbe
- P
- SyntheticPlayerLoader
- ReviewModule
- RewardsGui
- ReviewModule
- IntegratedCoreModuleX
- IntegratedCoreModuleX
- ChatBlockProbe
- net.kyori.adventure.text.Component
- CommandTemplate
- org.bukkit.event.Event
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
- LitematicReader
- CreativeGuardModule
- TamperType
- RedeemCodeModule
- DeepslateDecoyModule
- Pending
- DeepslateDecoyModule
- org.bukkit.entity.Entity
- .ChatGuardModule
- TamperType
- .refund
- .refund
- Overnight goal — memory log
- Fallback
- VoteListener
- Fallback
- RankContext

## God Nodes (most connected - your core abstractions)
1. `GildedCorePlugin` - 137 edges
2. `ColtCorePlugin` - 134 edges
3. `ChatGuardModule` - 98 edges
4. `ChatGuardModule` - 98 edges
5. `RewardsModule` - 89 edges
6. `EntityLimitModule` - 56 edges
7. `StaffMonitorModule` - 56 edges
8. `EntityLimitModule` - 56 edges
9. `StaffMonitorModule` - 56 edges
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

## Communities (170 total, 80 thin omitted)

### Community 0 - ".onEnable"
Cohesion: 0.07
Nodes (26): ActiveRankModule, AntiAdPipeline, AntibotGuard, ChatGuardModule, ChatLimiterModule, ConsoleGuard, ContextAwareAntiAd, DeepslateDecoyModule (+18 more)

### Community 1 - ".onEnable"
Cohesion: 0.07
Nodes (26): ActiveRankModule, AntiAdPipeline, AntibotGuard, ChatGuardModule, ChatLimiterModule, ConsoleGuard, ContextAwareAntiAd, DeepslateDecoyModule (+18 more)

### Community 3 - "VoteModule"
Cohesion: 0.10
Nodes (5): Holder, ItemStack, Override, VoteGui, VoteModule

### Community 5 - "ActiveRankModule"
Cohesion: 0.06
Nodes (11): ActiveRankModule, ManagedTask, Check, DiagnosticsModule, Level, FAIL, PASS, WARN (+3 more)

### Community 6 - "MillenniumNet"
Cohesion: 0.08
Nodes (4): DataInputStream, MillenniumNet, P, Sample

### Community 7 - "LocalAiModule"
Cohesion: 0.13
Nodes (3): ManagedTask, NeuralModel, LocalAiModule

### Community 8 - "ActiveRankModule"
Cohesion: 0.09
Nodes (5): ActiveRankModule, ManagedTask, CommandSender, Player, MaintenanceModule

### Community 9 - "PunishmentBridge"
Cohesion: 0.12
Nodes (6): Backend, EXTERNAL, INTERNAL, LITEBANS, PunishmentBridge, Result

### Community 11 - "StaffMonitorModule"
Cohesion: 0.11
Nodes (5): ItemStack, ManagedTask, Override, Menu, StaffMonitorModule

### Community 15 - "org.bukkit.event.Listener"
Cohesion: 0.10
Nodes (18): java.net.http.HttpClient, java.util.concurrent.ThreadLocalRandom, org.bukkit.configuration.file.FileConfiguration, org.bukkit.event.block.BlockBreakEvent, org.bukkit.event.block.BlockDispenseEvent, org.bukkit.event.block.BlockPlaceEvent, org.bukkit.event.block.SignChangeEvent, org.bukkit.event.inventory.InventoryCreativeEvent (+10 more)

### Community 17 - "Script"
Cohesion: 0.06
Nodes (29): Pattern, Script, ARABIC, ARMENIAN, BENGALI, CYRILLIC, DEVANAGARI, ETHIOPIC (+21 more)

### Community 18 - "Script"
Cohesion: 0.06
Nodes (29): Pattern, Script, ARABIC, ARMENIAN, BENGALI, CYRILLIC, DEVANAGARI, ETHIOPIC (+21 more)

### Community 19 - "StaffMonitorModule"
Cohesion: 0.11
Nodes (5): ItemStack, ManagedTask, Override, Menu, StaffMonitorModule

### Community 22 - "EntityLimitModule"
Cohesion: 0.12
Nodes (3): EntityLimitModule, ManagedTask, org.bukkit.event.entity.EntitySpawnEvent

### Community 23 - "LocalAiModule"
Cohesion: 0.12
Nodes (3): ManagedTask, NeuralModel, LocalAiModule

### Community 25 - "PlayerWipeModule"
Cohesion: 0.11
Nodes (5): Category, YamlConfiguration, PlayerWipeModule, QueuedWipe, Session

### Community 26 - "org.bukkit.plugin.Plugin"
Cohesion: 0.09
Nodes (8): ManagedTask, SchedulerCompat, ManagedTask, SchedulerCompat, java.sql.Connection, org.bukkit.event.EventPriority, org.bukkit.plugin.Plugin, org.bukkit.scheduler.BukkitTask

### Community 27 - "JoinPacketIsolation"
Cohesion: 0.12
Nodes (7): PacketAdapter, JoinPacketIsolation, com.comphenix.protocol.events.PacketAdapter, com.comphenix.protocol.events.PacketContainer, com.comphenix.protocol.PacketType, PacketAdapter, JoinPacketIsolation

### Community 28 - "PlayerWipeModule"
Cohesion: 0.11
Nodes (5): Category, YamlConfiguration, PlayerWipeModule, QueuedWipe, Session

### Community 29 - "ConsoleGuard"
Cohesion: 0.11
Nodes (5): ConsoleGuard, Handler, ConsoleGuard, Handler, java.util.logging.Handler

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

### Community 38 - "KelpGrowthModule"
Cohesion: 0.21
Nodes (3): KelpGrowthModule, org.bukkit.event.block.BlockGrowEvent, org.bukkit.event.block.BlockSpreadEvent

### Community 39 - "UiKit"
Cohesion: 0.11
Nodes (4): InventoryHolder, ItemStack, Sound, UiKit

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
Cohesion: 0.11
Nodes (3): ManagedTask, Reward, RewardsModule

### Community 48 - "ReviewGui"
Cohesion: 0.14
Nodes (5): Flag, FlagReviewStore, OfflinePlayer, OfflineHead, ReviewGui

### Community 49 - "org.bukkit.entity.Player"
Cohesion: 0.05
Nodes (9): VanishSupport, DailyRewardsGui, Holder, ItemStack, ManagedTask, Reward, RewardsModule, VanishSupport (+1 more)

### Community 50 - "org.bukkit.inventory.Inventory"
Cohesion: 0.14
Nodes (11): CrafterBulkHopper, PingCommand, Override, CrafterBulkHopper, PingCommand, Override, org.bukkit.command.CommandExecutor, org.bukkit.command.TabCompleter (+3 more)

### Community 53 - "org.bukkit.block.Block"
Cohesion: 0.07
Nodes (15): Action, SYNC, TURN_OFF, ManagedTask, RedstoneUnstaler, Action, SYNC, TURN_OFF (+7 more)

### Community 54 - "ColtCore and GildedCore — the anti-advertising filter, rebuilt on a text-native Millennium 5"
Cohesion: 0.11
Nodes (17): 10. Removed in earlier passes, 11. Build and verify, 1. The pipeline, as it actually runs, 2. The engine — Millennium 5, rebuilt for text, 3. What was measured, 4. Layer 3, and a design that was thrown away, 5. Known limits, 6. CheatDetector removal (+9 more)

### Community 58 - "LinearModel"
Cohesion: 0.18
Nodes (3): Example, LinearModel, Prediction

### Community 60 - "ReviewGui"
Cohesion: 0.14
Nodes (5): Flag, FlagReviewStore, OfflinePlayer, OfflineHead, ReviewGui

### Community 61 - "java.io.DataInputStream"
Cohesion: 0.17
Nodes (4): DataOutputStream, DataOutputStream, java.io.DataInputStream, java.io.DataOutputStream

### Community 62 - "RedstoneDestaler"
Cohesion: 0.13
Nodes (3): ManagedTask, RedstoneDestaler, org.bukkit.Chunk

### Community 63 - "RedstoneThrottle"
Cohesion: 0.19
Nodes (3): ManagedTask, RedstoneThrottle, Tier

### Community 66 - "org.bukkit.Material"
Cohesion: 0.10
Nodes (6): InventoryHolder, ItemStack, Sound, UiKit, org.bukkit.Material, org.bukkit.OfflinePlayer

### Community 67 - "org.bukkit.inventory.ItemStack"
Cohesion: 0.22
Nodes (4): Holder, ItemStack, PlaytimeRewardsGui, org.bukkit.inventory.ItemStack

### Community 68 - "ColtCorePlugin"
Cohesion: 0.14
Nodes (4): ColtCorePlugin, PlayerPicker, PlayerTargetGate, RecentPlayerTracker

### Community 69 - "org.bukkit.Location"
Cohesion: 0.06
Nodes (9): BookSignProximity, BrokenSignEntry, SignContextTracker, SignEntry, BookSignProximity, BrokenSignEntry, SignContextTracker, SignEntry (+1 more)

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
Cohesion: 0.10
Nodes (5): DiscordBridge, JoinRewardModule, DiscordBridge, JoinRewardModule, org.bukkit.plugin.java.JavaPlugin

### Community 86 - "org.bukkit.command.CommandSender"
Cohesion: 0.14
Nodes (4): Override, Override, org.bukkit.command.Command, org.bukkit.command.CommandSender

### Community 87 - "LocalContextAggregator"
Cohesion: 0.33
Nodes (3): Entry, LocalContextAggregator, Verdict

### Community 89 - "java.util.regex.Pattern"
Cohesion: 0.15
Nodes (7): AdvertContext, Hit, AdvertContext, Hit, DupeIpParser, Span, java.util.regex.Pattern

### Community 90 - "LocalContextAggregator"
Cohesion: 0.33
Nodes (3): Entry, LocalContextAggregator, Verdict

### Community 92 - "LinearModel"
Cohesion: 0.18
Nodes (3): Example, LinearModel, Prediction

### Community 97 - "DiagnosticsModule"
Cohesion: 0.16
Nodes (6): Check, DiagnosticsModule, Level, FAIL, PASS, WARN

### Community 98 - "VanishAnnouncer"
Cohesion: 0.17
Nodes (6): Hook, FunctionalInterface, Override, Reader, Toggle, VanishAnnouncer

### Community 99 - "org.bukkit.event.EventHandler"
Cohesion: 0.06
Nodes (17): Location, JoinMessage, Location, JoinMessage, io.papermc.paper.event.player.AsyncChatEvent, java.security.SecureRandom, net.luckperms.api.LuckPerms, org.bukkit.event.EventHandler (+9 more)

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

### Community 113 - "com.sk89q.worldedit.extent.clipboard.Clipboard"
Cohesion: 0.20
Nodes (7): Cached, com.sk89q.jnbt.CompoundTag, com.sk89q.worldedit.extent.clipboard.Clipboard, com.sk89q.worldedit.world.block.BlockState, Cached, LitematicReader, RegionBlocks

### Community 117 - "FancyChatHack"
Cohesion: 0.24
Nodes (6): FancyChatHack, Override, ChatOutputEvent, net.wurstclient.events.ChatOutputListener, net.wurstclient.hack.Hack, net.wurstclient.SearchTags

### Community 123 - "VanishAnnouncer"
Cohesion: 0.19
Nodes (4): Hook, Override, RankContext, VanishAnnouncer

### Community 135 - "org.bukkit.event.Event"
Cohesion: 0.35
Nodes (4): FunctionalInterface, Reader, Toggle, org.bukkit.event.Event

### Community 152 - "TamperType"
Cohesion: 0.22
Nodes (5): StashAlertHook, TamperType, BREAK, INTERACT, PLACE

### Community 153 - "RedeemCodeModule"
Cohesion: 0.06
Nodes (7): ConfigUpdater, YamlConfiguration, RedeemCodeModule, ConfigUpdater, YamlConfiguration, RedeemCodeModule, org.bukkit.configuration.file.YamlConfiguration

### Community 158 - ".ChatGuardModule"
Cohesion: 0.50
Nodes (3): LocalAiModule, MuteStore, PunishmentBridge

### Community 160 - "TamperType"
Cohesion: 0.29
Nodes (5): StashAlertHook, TamperType, BREAK, INTERACT, PLACE

### Community 165 - "Overnight goal — memory log"
Cohesion: 0.40
Nodes (4): Edit log, Overnight goal — memory log, Rewards V2 rework (GildedCore, uncommitted), Scope map (2026-09-18 ~19:55 UTC)

### Community 166 - "Fallback"
Cohesion: 0.50
Nodes (4): Fallback, APPLY, DROP, ESCALATE

### Community 168 - "Fallback"
Cohesion: 0.50
Nodes (4): Fallback, APPLY, DROP, ESCALATE

## Knowledge Gaps
- **134 isolated node(s):** `com.coltcore:ColtCore`, `PASS`, `WARN`, `FAIL`, `ALLOW` (+129 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 703 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **80 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ColtCorePlugin` connect `ColtCorePlugin` to `.onEnable`, `RewardsGui`, `ReviewModule`, `ActiveRankModule`, `StaffMonitorModule`, `StashModule`, `org.bukkit.event.Listener`, `.kitall`, `EntityLimitModule`, `RedeemCodeModule`, `DeepslateDecoyModule`, `JoinPacketIsolation`, `PlayerWipeModule`, `ConsoleGuard`, `org.bukkit.plugin.Plugin`, `CreativeGuardModule`, `AntiAdPipeline`, `KelpGrowthModule`, `ChatGuardModule`, `ChatLimiterModule`, `RewardsModule`, `org.bukkit.inventory.Inventory`, `org.bukkit.block.Block`, `AntibotGuard`, `ReviewGui`, `org.bukkit.Location`, `RedstoneThrottle`, `.gradientBorder`, `org.bukkit.plugin.java.JavaPlugin`, `org.bukkit.command.CommandSender`, `java.util.regex.Pattern`, `org.bukkit.event.EventHandler`, `.color`, `RenameContextTracker`, `ContextAwareAntiAd`, `.colorComponent`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **Why does `GildedCorePlugin` connect `GildedCorePlugin` to `.onEnable`, `VoteModule`, `ActiveRankModule`, `StashModule`, `org.bukkit.event.Listener`, `StaffMonitorModule`, `EntityLimitModule`, `CreativeGuardModule`, `PlayerWipeModule`, `RedeemCodeModule`, `JoinPacketIsolation`, `DeepslateDecoyModule`, `ConsoleGuard`, `.gradientBorder`, `ChatGuardModule`, `org.bukkit.plugin.Plugin`, `AntiAdPipeline`, `ChatLimiterModule`, `ReviewGui`, `org.bukkit.entity.Player`, `org.bukkit.inventory.Inventory`, `org.bukkit.block.Block`, `RedstoneDestaler`, `RedstoneThrottle`, `AntibotGuard`, `org.bukkit.inventory.ItemStack`, `org.bukkit.Location`, `org.bukkit.plugin.java.JavaPlugin`, `org.bukkit.command.CommandSender`, `java.util.regex.Pattern`, `DiagnosticsModule`, `org.bukkit.event.EventHandler`, `.demote`, `RenameContextTracker`, `ContextAwareAntiAd`, `KelpGrowthModule`, `.color`, `ReviewModule`?**
  _High betweenness centrality (0.070) - this node is a cross-community bridge._
- **Why does `ChatGuardModule` connect `ChatGuardModule` to `ReviewModule`, `ActiveRankModule`, `PunishmentBridge`, `MuteStore`, `org.bukkit.event.Listener`, `LocalAiModule`, `ConsoleGuard`, `.blockedByMute`, `AntiAdPipeline`, `ReviewGui`, `.screen`, `ColtCorePlugin`, `.command`, `org.bukkit.plugin.java.JavaPlugin`, `LocalContextAggregator`, `java.util.regex.Pattern`, `org.bukkit.event.EventHandler`, `.ChatGuardModule`, `ContextAwareAntiAd`?**
  _High betweenness centrality (0.065) - this node is a cross-community bridge._
- **What connects `com.coltcore:ColtCore`, `PASS`, `WARN` to the rest of the system?**
  _134 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `.onEnable` be split into smaller, more focused modules?**
  _Cohesion score 0.06666666666666667 - nodes in this community are weakly interconnected._
- **Should `.onEnable` be split into smaller, more focused modules?**
  _Cohesion score 0.06666666666666667 - nodes in this community are weakly interconnected._
- **Should `MillenniumNet` be split into smaller, more focused modules?**
  _Cohesion score 0.10695187165775401 - nodes in this community are weakly interconnected._