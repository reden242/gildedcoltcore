package com.gildedmc.core.modules;

import com.vexsoftware.votifier.model.VotifierEvent;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Bridge from Votifier-family providers (Votifier, NuVotifier, VotifierPlus)
 * into {@link VoteModule}. All three fire the same
 * {@code com.vexsoftware.votifier.model.VotifierEvent}, so one listener
 * covers every provider. Registered only when the event class is present.
 */
public final class VoteListener implements Listener {

    private final VoteModule votes;

    public VoteListener(VoteModule votes) {
        this.votes = votes;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVote(VotifierEvent event) {
        if (event == null || event.getVote() == null) return;
        this.votes.handleVote(event.getVote().getUsername(), event.getVote().getServiceName());
    }
}