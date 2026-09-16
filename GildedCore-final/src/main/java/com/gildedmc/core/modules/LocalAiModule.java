package com.gildedmc.core.modules;

import com.gildedmc.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local-only supervised machine learning for the chat filter.
 * The only labels are clean and advertising, and every training row comes from
 * chat-filter decisions or staff review. No network client exists here.
 */
public final class LocalAiModule {

    public static final String LABEL_CLEAN = "clean";
    public static final String LABEL_ADVERTISING = "advertising";
    private static final List<String> LABELS = List.of(LABEL_CLEAN, LABEL_ADVERTISING);

    private final JavaPlugin plugin;
    private final Object databaseLock = new Object();
    private Connection database;
    private NeuralModel model;
    private File modelFile;

    private boolean enabled;
    private boolean shadowMode = true;
    private double minimumAccuracy = 0.90D;
    private double minimumConfidence = 0.85D;
    private int maximumSamples = 20_000;
    private double cleanSampleRate = 0.05D;
    private int minimumSamplesPerLabel = 60;

    private int epochs = 80;
    private double learningRate = 0.005D;
    private double holdOut = 0.20D;
    private double weightDecay = 0.0D;
    private double gradientClip = 5.0D;
    private long seed = 999L;
    private double classWeightPower = 0.5D;
    private int patience = 4;
    private double labelSmoothing = 0.05D;
    private long retrainDelayTicks = 72_000L;
    private int minimumNewSamples = 25;
    private int buckets = NeuralModel.BUCKETS;
    private int dimensions = NeuralModel.DIM;
    private int hidden = NeuralModel.HIDDEN;

    private final AtomicInteger newSamplesSinceTraining = new AtomicInteger();
    private final Random random = new Random();
    private SchedulerCompat.ManagedTask retrainTask;
    private volatile boolean training;
    private volatile String trainingSummary = "never trained";
    private volatile long predictions;
    private volatile long predictionNanos;

    public LocalAiModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        readConfig();
        if (!this.enabled) return;
        if (!openDatabase()) {
            this.enabled = false;
            return;
        }
        this.modelFile = new File(this.plugin.getDataFolder(), "localai.model");
        NeuralModel saved = NeuralModel.load(this.modelFile);
        if (saved != null && saved.labels().equals(LABELS)) {
            this.model = saved;
            this.trainingSummary = "fine-tuned on this server, held-out accuracy "
                    + percent(saved.heldOutAccuracy());
        } else {
            this.model = new NeuralModel(LABELS, this.buckets, this.dimensions, this.hidden);
            this.trainingSummary = "untrained";
        }
        seedIfEmpty();
        scheduleRetraining();
        this.plugin.getLogger().info("[LocalAI] advertising classifier enabled. " + describeReadiness());
    }

    public void reload() {
        boolean wasEnabled = this.enabled;
        readConfig();
        if (this.enabled && !wasEnabled) enable();
        if (!this.enabled && wasEnabled) disable();
    }

    public void disable() {
        if (this.retrainTask != null) {
            this.retrainTask.cancel();
            this.retrainTask = null;
        }
        saveModel();
        if (this.database != null) {
            try { this.database.close(); } catch (SQLException ignored) { }
            this.database = null;
        }
    }

    private void readConfig() {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("local-ai");
        if (section == null) {
            this.enabled = false;
            return;
        }
        this.enabled = section.getBoolean("enabled", false);
        this.shadowMode = section.getBoolean("shadow-mode", true);
        this.minimumAccuracy = section.getDouble("min-accuracy", 0.90D);
        this.minimumConfidence = section.getDouble("min-confidence", 0.85D);
        this.maximumSamples = Math.max(500, section.getInt("max-samples", 20_000));
        this.cleanSampleRate = clamp(section.getDouble("clean-sample-rate", 0.05D), 0.0D, 1.0D);
        this.minimumSamplesPerLabel = Math.max(10, section.getInt("min-samples-per-label", 60));

        ConfigurationSection geometry = section.getConfigurationSection("model");
        if (geometry != null) {
            this.buckets = Math.max(1024, geometry.getInt("buckets", NeuralModel.BUCKETS));
            this.dimensions = Math.max(8, geometry.getInt("dim", NeuralModel.DIM));
            this.hidden = Math.max(8, geometry.getInt("hidden", NeuralModel.HIDDEN));
        }

        ConfigurationSection training = section.getConfigurationSection("train");
        if (training != null) {
            this.epochs = Math.max(1, training.getInt("epochs", 80));
            this.learningRate = training.getDouble("learning-rate", 0.005D);
            this.holdOut = clamp(training.getDouble("hold-out", 0.20D), 0.0D, 0.5D);
            this.retrainDelayTicks = Math.max(1_200L,
                    training.getLong("auto-retrain-minutes", 60L) * 60_000L / 50L);
            this.minimumNewSamples = Math.max(1, training.getInt("min-new-samples", 25));
            this.weightDecay = Math.max(0.0D, training.getDouble("weight-decay", 0.0D));
            this.gradientClip = Math.max(0.0D, training.getDouble("grad-clip", 5.0D));
            this.seed = training.getLong("seed", 999L);
            this.classWeightPower = clamp(training.getDouble("class-weight-power", 0.5D), 0.0D, 1.0D);
            this.patience = Math.max(0, training.getInt("early-stop-patience", 4));
            this.labelSmoothing = clamp(training.getDouble("label-smoothing", 0.05D), 0.0D, 0.5D);
        }
    }

    private boolean openDatabase() {
        try {
            this.database = DriverManager.getConnection("jdbc:sqlite:"
                    + new File(this.plugin.getDataFolder(), "localai.db").getAbsolutePath());
            try (Statement statement = this.database.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS samples ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,text TEXT NOT NULL,"
                        + "label TEXT NOT NULL,source TEXT NOT NULL,ts INTEGER NOT NULL)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS samples_label_ts ON samples(label,ts)");
            }
            return true;
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[LocalAI] could not open localai.db: " + exception.getMessage());
            return false;
        }
    }

    private void seedIfEmpty() {
        synchronized (this.databaseLock) {
            try (PreparedStatement statement = this.database.prepareStatement(
                    "SELECT COUNT(*) FROM samples WHERE source='chat-filter-seed'")) {
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) return;
                }
            } catch (SQLException exception) {
                this.plugin.getLogger().warning("[LocalAI] seed check failed: " + exception.getMessage());
                return;
            }

            List<SeedCorpus.Sample> samples = SeedCorpus.build(LABELS);
            try {
                this.database.setAutoCommit(false);
                try (PreparedStatement delete = this.database.prepareStatement(
                        "DELETE FROM samples WHERE source LIKE 'seed%'");
                     PreparedStatement insert = this.database.prepareStatement(
                             "INSERT INTO samples(text,label,source,ts) VALUES(?,?,'chat-filter-seed',?)")) {
                    delete.executeUpdate();
                    long now = System.currentTimeMillis();
                    for (SeedCorpus.Sample sample : samples) {
                        insert.setString(1, sample.text());
                        insert.setString(2, sample.label());
                        insert.setLong(3, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                this.database.commit();
                this.newSamplesSinceTraining.addAndGet(samples.size());
            } catch (SQLException exception) {
                try { this.database.rollback(); } catch (SQLException ignored) { }
                this.plugin.getLogger().warning("[LocalAI] could not seed training data: " + exception.getMessage());
                return;
            } finally {
                try { this.database.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        }
        train(null);
    }

    private void scheduleRetraining() {
        if (this.retrainTask != null) this.retrainTask.cancel();
        this.retrainTask = SchedulerCompat.timerAsync(this.plugin,
                this::maybeRetrain, this.retrainDelayTicks, this.retrainDelayTicks);
    }

    public boolean isEnabled() { return this.enabled; }
    public boolean shadowMode() { return this.shadowMode; }

    public void learn(String text, String label, String source) {
        if (!this.enabled || this.database == null || text == null || text.isBlank()) return;
        String normalizedLabel = TextFeatures.norm(label);
        if (!LABELS.contains(normalizedLabel)) return;
        if (source != null && source.startsWith("local-ai")) return;
        String kept = text.length() > 300 ? text.substring(0, 300) : text;
        String origin = source == null ? "unknown" : source;
        SchedulerCompat.runAsync(this.plugin, () -> {
            synchronized (this.databaseLock) {
                try (PreparedStatement statement = this.database.prepareStatement(
                        "INSERT INTO samples(text,label,source,ts) VALUES(?,?,?,?)")) {
                    statement.setString(1, kept);
                    statement.setString(2, normalizedLabel);
                    statement.setString(3, origin);
                    statement.setLong(4, System.currentTimeMillis());
                    statement.executeUpdate();
                    this.newSamplesSinceTraining.addAndGet(origin.startsWith("admin-review") ? 10 : 1);
                } catch (SQLException ignored) { }
                trimLocked();
            }
        });
    }

    public void learnClean(String text) {
        if (this.cleanSampleRate <= 0.0D || text == null || text.length() < 4) return;
        if (this.random.nextDouble() > this.cleanSampleRate) return;
        learn(text, LABEL_CLEAN, "sampled-clean");
    }

    private void trimLocked() {
        try (PreparedStatement count = this.database.prepareStatement("SELECT COUNT(*) FROM samples");
             ResultSet result = count.executeQuery()) {
            if (!result.next() || result.getInt(1) <= this.maximumSamples) return;
        } catch (SQLException exception) {
            return;
        }
        try (PreparedStatement statement = this.database.prepareStatement(
                "DELETE FROM samples WHERE id IN (SELECT id FROM samples"
                        + " WHERE label=(SELECT label FROM samples GROUP BY label"
                        + " ORDER BY COUNT(*) DESC LIMIT 1) ORDER BY ts ASC LIMIT 500)")) {
            statement.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private void maybeRetrain() {
        if (!this.enabled || this.training) return;
        if (this.newSamplesSinceTraining.get() < this.minimumNewSamples) return;
        train(null);
    }

    public void train(CommandSender sender) {
        if (!this.enabled || this.training) {
            finish(sender, "&cThe local classifier is disabled or already training.");
            return;
        }
        this.training = true;
        Runnable job = () -> {
            try {
                List<LinearModel.Example> examples = new ArrayList<>();
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String label : LABELS) counts.put(label, 0);
                synchronized (this.databaseLock) {
                    try (PreparedStatement statement = this.database.prepareStatement(
                            "SELECT text,label FROM (SELECT text,label,ts FROM samples"
                                    + " ORDER BY ts DESC LIMIT " + this.maximumSamples + ") ORDER BY ts ASC");
                         ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            String label = result.getString(2);
                            int index = LABELS.indexOf(label);
                            if (index < 0) continue;
                            examples.add(new LinearModel.Example(TextFeatures.extract(result.getString(1)), index));
                            counts.merge(label, 1, Integer::sum);
                        }
                    } catch (SQLException exception) {
                        finish(sender, "&cCould not read training samples: " + exception.getMessage());
                        return;
                    }
                }

                NeuralModel fresh = new NeuralModel(LABELS, this.buckets, this.dimensions, this.hidden);
                long started = System.currentTimeMillis();
                double accuracy = fresh.train(examples, this.epochs, this.learningRate,
                        this.holdOut, this.weightDecay, this.gradientClip, this.seed,
                        this.classWeightPower, this.patience, this.labelSmoothing);
                long elapsed = System.currentTimeMillis() - started;
                this.newSamplesSinceTraining.set(0);
                if (accuracy < 0.0D) {
                    this.trainingSummary = "not enough data (" + examples.size() + " examples)";
                    finish(sender, "&eNot enough data yet. " + shortfall(counts));
                    return;
                }
                this.model = fresh;
                saveModel();
                this.trainingSummary = percent(accuracy) + " held-out accuracy on "
                        + fresh.trainedOn() + " examples in " + elapsed + "ms";
                this.plugin.getLogger().info("[LocalAI] retrained: " + this.trainingSummary);
                finish(sender, "&aTrained. &7" + this.trainingSummary);
            } finally {
                this.training = false;
            }
        };
        if (Bukkit.isPrimaryThread()) SchedulerCompat.runAsync(this.plugin, job);
        else job.run();
    }

    private void finish(CommandSender sender, String message) {
        if (sender == null) return;
        SchedulerCompat.run(this.plugin, () -> sender.sendMessage(UiKit.colour(message)));
    }

    private void saveModel() {
        if (this.model == null || this.modelFile == null || !this.model.trained()) return;
        try { this.model.save(this.modelFile); }
        catch (Exception exception) {
            this.plugin.getLogger().warning("[LocalAI] could not save the model: " + exception.getMessage());
        }
    }

    public boolean ready() {
        return this.enabled && this.model != null && this.model.trained()
                && this.model.heldOutAccuracy() >= this.minimumAccuracy;
    }

    public LinearModel.Prediction classify(String text) {
        if (this.model == null || !this.model.trained()) return null;
        long started = System.nanoTime();
        LinearModel.Prediction prediction = this.model.predict(text);
        this.predictionNanos += System.nanoTime() - started;
        this.predictions++;
        return prediction;
    }

    public double advertisingProbability(String text) {
        if (!ready()) return -1.0D;
        LinearModel.Prediction prediction = classify(text);
        if (prediction == null) return -1.0D;
        Double score = prediction.scores().get(LABEL_ADVERTISING);
        return score == null ? 0.0D : score;
    }

    public String verdictFor(String text) {
        if (!ready()) return null;
        LinearModel.Prediction prediction = classify(text);
        if (prediction == null || !LABEL_ADVERTISING.equals(prediction.label())) return null;
        return prediction.confidence() >= this.minimumConfidence ? LABEL_ADVERTISING : null;
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String label : LABELS) counts.put(label, 0);
        if (this.database == null) return counts;
        synchronized (this.databaseLock) {
            try (PreparedStatement statement = this.database.prepareStatement(
                    "SELECT label,COUNT(*) FROM samples GROUP BY label");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) counts.put(result.getString(1), result.getInt(2));
            } catch (SQLException ignored) { }
        }
        return counts;
    }

    private String shortfall(Map<String, Integer> counts) {
        List<String> missing = new ArrayList<>();
        for (String label : LABELS) {
            int count = counts.getOrDefault(label, 0);
            if (count < this.minimumSamplesPerLabel) missing.add(label + " " + count + "/" + this.minimumSamplesPerLabel);
        }
        return missing.isEmpty() ? "" : "Still short: " + String.join(", ", missing);
    }

    private String describeReadiness() {
        if (!ready()) return "Not trusted yet (" + this.trainingSummary + ").";
        return this.shadowMode ? "Ready in shadow mode." : "Ready and active.";
    }

    private static String percent(double value) {
        return value < 0.0D ? "n/a" : String.format(Locale.ROOT, "%.1f%%", value * 100.0D);
    }

    public double averageMicros() {
        return this.predictions == 0 ? 0.0D : (this.predictionNanos / (double) this.predictions) / 1000.0D;
    }

    public boolean command(CommandSender sender, String[] arguments) {
        String subcommand = arguments.length < 2 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "train" -> {
                sender.sendMessage(UiKit.colour("&7Training the local advertising model..."));
                train(sender);
            }
            case "classify", "test" -> {
                if (arguments.length < 3) {
                    sender.sendMessage(UiKit.colour("&e/textguard model classify <text...>"));
                    return true;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(arguments, 2, arguments.length));
                LinearModel.Prediction prediction = classify(text);
                if (prediction == null) {
                    sender.sendMessage(UiKit.colour("&eThe model is not trained yet."));
                    return true;
                }
                sender.sendMessage(UiKit.colour("&7verdict: &f" + prediction.label()
                        + " &8(" + percent(prediction.confidence()) + ")"));
                prediction.scores().forEach((label, score) -> sender.sendMessage(
                        UiKit.colour("&8  " + label + ": &7" + percent(score))));
            }
            case "teach" -> {
                if (arguments.length < 4) {
                    sender.sendMessage(UiKit.colour("&e/textguard model teach <clean|advertising> <text...>"));
                    return true;
                }
                String label = TextFeatures.norm(arguments[2]);
                if (!LABELS.contains(label)) {
                    sender.sendMessage(UiKit.colour("&cLabel must be clean or advertising."));
                    return true;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(arguments, 3, arguments.length));
                learn(text, label, "staff:" + sender.getName());
                sender.sendMessage(UiKit.colour("&aRecorded as &f" + label + "&a."));
            }
            case "shadow" -> {
                this.shadowMode = !this.shadowMode;
                sender.sendMessage(UiKit.colour("&aShadow mode: &f" + this.shadowMode));
            }
            case "reset" -> {
                if (arguments.length < 3 || !"confirm".equalsIgnoreCase(arguments[2])) {
                    sender.sendMessage(UiKit.colour("&e/textguard model reset confirm"));
                    return true;
                }
                synchronized (this.databaseLock) {
                    try (Statement statement = this.database.createStatement()) {
                        statement.executeUpdate("DELETE FROM samples");
                    } catch (SQLException exception) {
                        sender.sendMessage(UiKit.colour("&cReset failed: " + exception.getMessage()));
                        return true;
                    }
                }
                this.model = new NeuralModel(LABELS, this.buckets, this.dimensions, this.hidden);
                this.trainingSummary = "reset";
                seedIfEmpty();
                sender.sendMessage(UiKit.colour("&aTraining data reset and reseeded."));
            }
            default -> {
                sender.sendMessage(UiKit.colour("&#EEBB01LocalAI &8- &7" + describeReadiness()));
                sender.sendMessage(UiKit.colour("&7labels: &fclean, advertising&8; samples: &f"
                        + counts() + "&8; average: &f"
                        + String.format(Locale.ROOT, "%.0f", averageMicros()) + "us"));
            }
        }
        return true;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
