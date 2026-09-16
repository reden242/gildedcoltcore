import pathlib

p = pathlib.Path("GildedCore-final/src/main/java/com/gildedmc/core/modules/ChatGuardModule.java")
g = p.read_text(encoding="utf-8")

old = '''        String evidence = address != null ? address : soft.evidence();
        double patternConfidence = address != null ? 1.0D : soft.heavy() ? 0.60D : 0.50D;
        double advertisingProbability = this.local.advertisingProbability(advertForm(raw));

        if (advertisingProbability >= 0.0D && advertisingProbability >= 0.85D) {
            return blockAdvertising(player, raw, source, evidence,
                    soft != null && !soft.heavy() ? CAT_LIGHT_ADVERT : CAT_ADVERT,
                    "L1+L2", advertisingProbability);
        }
        if (advertisingProbability >= 0.0D && advertisingProbability < 0.40D) {
            this.plugin.getLogger().info("[LocalAI] L1 false positive cleared: " + raw);
            return false;
        }

        LocalContextAggregator.Verdict context = this.advertContext.evaluate(
                player.getUniqueId(), raw, true, advertisingProbability);
        if (context.block()) {
            return blockAdvertising(player, raw, source, evidence,
                    soft != null && !soft.heavy() ? CAT_LIGHT_ADVERT : CAT_ADVERT,
                    "L3 " + context.reason(), context.confidence());
        }
        if (context.review()) {
            alert("&e" + player.getName() + " &7advertising review &8(&7L3 "
                    + context.reason() + ", " + String.format(Locale.ROOT, "%.0f%%",
                    context.confidence() * 100.0D) + "&8): &f" + raw);
            return false;
        }
        if (advertisingProbability < 0.0D && address != null) {
            return blockAdvertising(player, raw, source, evidence, CAT_ADVERT,
                    "L1 exact while model trains", patternConfidence);
        }
        this.plugin.getLogger().info("[LocalAI] L3 cleared " + player.getName()
                + " (" + context.reason() + "): " + raw);
        return false;
    }

    private boolean blockAdvertising(Player player, String raw, String source,
                                     String evidence, String category,
                                     String reason, double confidence) {
        String trainingLabel = CAT_LIGHT_ADVERT.equals(category)
                ? LocalAiModule.LABEL_ADVERTISING : category;
        this.local.learn(raw, trainingLabel, "chat-filter-" + reason);
        warn(player, "&cAdvertising other servers is not allowed here.");
        alert("&4" + player.getName() + " &7advertising &f" + evidence
                + " &7in &f" + source + " &8(&7" + reason + ", "
                + String.format(Locale.ROOT, "%.0f%%", confidence * 100.0D) + "&8): &f" + raw);
        punishAsync(player, category, raw, source);
        return true;
    }'''

new = '''        String evidence = address != null ? address : soft.evidence();
        double patternConfidence = address != null ? 1.0D : soft.heavy() ? 0.60D : 0.50D;
        // Layer 2: the fastText model first, the legacy LinearModel only as a
        // fallback when the pipeline is not wired or its model failed to load.
        double advertisingProbability;
        boolean fromFastText = this.antiAd != null && this.antiAd.l2Ready();
        if (fromFastText) {
            advertisingProbability = this.antiAd.l2Probability(advertForm(raw));
        } else {
            advertisingProbability = this.local.advertisingProbability(advertForm(raw));
        }

        if (advertisingProbability >= 0.0D && advertisingProbability >= 0.85D) {
            return blockAdvertising(player, raw, source, evidence,
                    soft != null && !soft.heavy() ? CAT_LIGHT_ADVERT : CAT_ADVERT,
                    "L1+L2", advertisingProbability);
        }
        if (advertisingProbability >= 0.0D && advertisingProbability < 0.40D) {
            this.plugin.getLogger().info("[LocalAI] L1 false positive cleared: " + raw);
            if (this.antiAd != null) {
                this.antiAd.log(player.getName(), source, raw, evidence,
                        advertisingProbability, null, "pass-l2-clear");
            }
            return false;
        }

        // Ambiguous band (0.40-0.85): ask the local LLM (millenium-5) for a
        // second opinion before falling back to the heuristic aggregator. The
        // LLM call blocks, so it only runs when the fastText model put the
        // message here - never on clean or confident-flag traffic.
        if (fromFastText && advertisingProbability >= 0.40D && this.antiAd != null) {
            AntiAdPipeline.LlmVerdict llm = this.antiAd.llmCheck(
                    player.getUniqueId(), player.getName(), raw);
            if (llm.flag() && llm.confidence() >= 0.60D) {
                this.antiAd.log(player.getName(), source, raw, evidence,
                        advertisingProbability, llm.reasoning(), "flag-llm");
                return blockAdvertising(player, raw, source, evidence,
                        CAT_ADVERT, "L3 LLM: " + llm.reasoning(),
                        Math.max(llm.confidence(), advertisingProbability), true);
            }
            if (llm.confidence() >= 0.0D) {
                // The LLM answered and cleared it.
                this.antiAd.log(player.getName(), source, raw, evidence,
                        advertisingProbability, llm.reasoning(), "pass-llm-clear");
                this.plugin.getLogger().info("[AntiAd] LLM cleared " + player.getName()
                        + " (" + llm.reasoning() + "): " + raw);
                return false;
            }
            // No opinion (rate limit / error): fall through to the aggregator.
        }

        LocalContextAggregator.Verdict context = this.advertContext.evaluate(
                player.getUniqueId(), raw, true, advertisingProbability);
        if (context.block()) {
            return blockAdvertising(player, raw, source, evidence,
                    soft != null && !soft.heavy() ? CAT_LIGHT_ADVERT : CAT_ADVERT,
                    "L3 " + context.reason(), context.confidence());
        }
        if (context.review()) {
            alert("&e" + player.getName() + " &7advertising review &8(&7L3 "
                    + context.reason() + ", " + String.format(Locale.ROOT, "%.0f%%",
                    context.confidence() * 100.0D) + "&8): &f" + raw);
            return false;
        }
        if (advertisingProbability < 0.0D && address != null) {
            return blockAdvertising(player, raw, source, evidence, CAT_ADVERT,
                    "L1 exact while model trains", patternConfidence);
        }
        this.plugin.getLogger().info("[LocalAI] L3 cleared " + player.getName()
                + " (" + context.reason() + "): " + raw);
        return false;
    }

    private boolean blockAdvertising(Player player, String raw, String source,
                                     String evidence, String category,
                                     String reason, double confidence) {
        return blockAdvertising(player, raw, source, evidence, category,
                reason, confidence, false);
    }

    /**
     * @param llmConfirmed true when the flag came from the L3 LLM. Such flags
     *                     survive {@link #advertDoubleCheck} by construction -
     *                     the model saw an obfuscated address the regex engine
     *                     cannot re-find, and silently dropping the punishment
     *                     there was the old behaviour's failure mode.
     */
    private boolean blockAdvertising(Player player, String raw, String source,
                                     String evidence, String category,
                                     String reason, double confidence,
                                     boolean llmConfirmed) {
        String trainingLabel = CAT_LIGHT_ADVERT.equals(category)
                ? LocalAiModule.LABEL_ADVERTISING : category;
        this.local.learn(raw, trainingLabel, "chat-filter-" + reason);
        warn(player, "&cAdvertising other servers is not allowed here.");
        alert("&4" + player.getName() + " &7advertising &f" + evidence
                + " &7in &f" + source + " &8(&7" + reason + ", "
                + String.format(Locale.ROOT, "%.0f%%", confidence * 100.0D) + "&8): &f" + raw);
        punishAsync(player, category, raw, source, llmConfirmed);
        return true;
    }'''

assert g.count(old) == 1, "screenAdvertising body"
g = g.replace(old, new)

# punishAsync overload
old_punish = '''    private void punishAsync(Player p, String category, String text, String source) {
        final String name = p.getName();
        final String uuid = p.getUniqueId().toString();
        final String ip = MuteStore.addressOf(p);
        // ADVERTISING-ONLY MODE: every other category alerts but never punishes.
        if (!CAT_ADVERT.equals(category)) {
            this.plugin.getLogger().info("[TextGuard] " + category + " for " + name
                    + " left unpunished (advertising-only mode).");
            return;
        }
        // Second opinion: an AI advert hit must survive the regex engine on the
        // allowlist/player-name-stripped text before anyone gets muted.
        String advertConfirm = this.advertDoubleCheck(text);
        if (advertConfirm == null) {'''

new_punish = '''    private void punishAsync(Player p, String category, String text, String source) {
        punishAsync(p, category, text, source, false);
    }

    /**
     * @param regexConfirmed the L1 regex already confirmed this text (or the
     *                      flag came from the L3 LLM), so the second-opinion
     *                      re-scan is skipped. Without this, every model/LLM
     *                      confirmed flag was silently dropped when the regex
     *                      engine could not re-find an obfuscated address.
     */
    private void punishAsync(Player p, String category, String text, String source,
                             boolean regexConfirmed) {
        final String name = p.getName();
        final String uuid = p.getUniqueId().toString();
        final String ip = MuteStore.addressOf(p);
        // ADVERTISING-ONLY MODE: every other category alerts but never punishes.
        if (!CAT_ADVERT.equals(category)) {
            this.plugin.getLogger().info("[TextGuard] " + category + " for " + name
                    + " left unpunished (advertising-only mode).");
            return;
        }
        // Second opinion: an AI advert hit must survive the regex engine on the
        // allowlist/player-name-stripped text before anyone gets muted.
        String advertConfirm = regexConfirmed ? "confirmed" : this.advertDoubleCheck(text);
        if (advertConfirm == null) {'''

assert g.count(old_punish) == 1, "punishAsync"
g = g.replace(old_punish, new_punish)

p.write_text(g, encoding="utf-8")
print("part 2 OK")
