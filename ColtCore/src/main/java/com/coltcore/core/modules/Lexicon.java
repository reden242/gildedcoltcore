package com.coltcore.core.modules;

import java.util.ArrayList;
import java.util.List;

/**
 * English profanity and hate vocabulary.
 *
 * <h2>Why this is English only</h2>
 * It used to carry 2,562 terms across 24 languages, taken from LDNOOBW. That
 * was measurably worse than carrying nothing for those languages, because of
 * what happens downstream: every term is also encoded phonetically, and the
 * encoder is an <em>English</em> metaphone. Run a Czech or Filipino word
 * through it and the output is not a pronunciation, it is debris -
 * {@code cu} became {@code K}, {@code pau} became {@code P}, {@code 3p} became
 * {@code P}. A one-letter code matches essentially any sentence, so
 * {@code gg wp} was reported as a slur and {@code brb food} matched four
 * different foreign terms at once.
 *
 * <p>The languages were not adding coverage. They were adding a few hundred
 * one- and two-sound codes that made the phonetic layer fire on ordinary chat.
 *
 * <h2>The two lists are not the same offence</h2>
 * {@link #profanity()} is crude language. {@link #hate()} is slurs and
 * organised-hate vocabulary. They were previously one undifferentiated list,
 * which meant {@code fuck} and a racial slur produced the same punishment -
 * either the swear was treated as hate speech or the slur was treated as a
 * swear. Splitting them is what lets the ladder be proportionate.
 *
 * <p>The owner's own terms are added to these, never instead of them.
 */
public final class Lexicon {

    private Lexicon() { }

    /** Crude and sexual language, English. From LDNOOBW, 382 terms. */
    private static final String[] PROFANITY = {
        "2 girls 1 cup", "2g1c", "acrotomophilia", "alabama hot pocket", "alaskan pipeline",
        "anal", "anilingus", "anus", "apeshit", "arsehole", "ass", "asshole", "assmunch",
        "auto erotic", "autoerotic", "babeland", "baby batter", "baby juice", "ball gag",
        "ball gravy", "ball kicking", "ball licking", "ball sack", "ball sucking", "bangbros",
        "bangbus", "bareback", "barely legal", "barenaked", "bastard", "bastardo", "bastinado",
        "bbw", "bdsm", "beastiality", "beaver cleaver", "beaver lips", "bestiality",
        "big black", "big breasts", "big knockers", "big tits", "bimbos", "birdlock", "bitch",
        "bitches", "black cock", "blonde action", "blonde on blonde action", "blow job",
        "blow your load", "blowjob", "blue waffle", "blumpkin", "bollocks", "bondage", "boner",
        "boob", "boobs", "booty call", "brown showers", "brunette action", "bukkake",
        "bulldyke", "bullet vibe", "bullshit", "bung hole", "bunghole", "busty", "butt",
        "buttcheeks", "butthole", "camel toe", "camgirl", "camslut", "camwhore",
        "carpet muncher", "carpetmuncher", "chocolate rosebuds", "cialis", "circlejerk",
        "cleveland steamer", "clit", "clitoris", "clover clamps", "clusterfuck", "cock",
        "cocks", "coprolagnia", "coprophilia", "cornhole", "creampie", "cum", "cumming",
        "cumshot", "cumshots", "cunnilingus", "cunt", "darkie", "date rape", "daterape",
        "deep throat", "deepthroat", "dendrophilia", "dick", "dildo", "dingleberries",
        "dingleberry", "dirty pillows", "dirty sanchez", "dog style", "doggie style",
        "doggiestyle", "doggy style", "doggystyle", "dolcett", "domination", "dominatrix",
        "dommes", "donkey punch", "double dong", "double penetration", "dp action", "dry hump",
        "dvda", "eat my ass", "ecchi", "ejaculation", "erotic", "erotism", "escort", "eunuch",
        "fecal", "felch", "fellatio", "feltch", "female squirting", "femdom", "figging",
        "fingerbang", "fingering", "fisting", "foot fetish", "footjob", "frotting",
        "fuck buttons", "fucktards", "fudge packer", "fudgepacker",
        "futanari", "g-spot", "gang bang", "gangbang", "gay sex", "genitals", "giant cock",
        "girl on", "girl on top", "girls gone wild", "goatcx", "goatse", "gokkun",
        "golden shower", "goo girl", "goodpoop", "goregasm", "grope", "group sex", "guro",
        "hand job", "handjob", "hard core", "hardcore", "hentai", "homoerotic", "honkey",
        "hooker", "horny", "hot carl", "hot chick", "how to kill", "how to murder", "huge fat",
        "humping", "incest", "intercourse", "jack off", "jail bait", "jailbait", "jelly donut",
        "jerk off", "jigaboo", "jiggaboo", "jiggerboo", "jizz", "juggs", "kinbaku", "kinkster",
        "kinky", "knobbing", "leather restraint", "leather straight jacket", "lemon party",
        "livesex", "lolita", "lovemaking", "make me come", "male squirting", "masturbate",
        "masturbating", "masturbation", "menage a trois", "milf", "missionary position",
        "mong", "motherfucker", "mound of venus", "mr hands", "muff diver", "muffdiving",
        "nambla", "nawashi", "neonazi", "nig nog", "nimphomania", "nipple", "nipples", "nsfw",
        "nsfw images", "nude", "nudity", "nutten", "nympho", "nymphomania", "octopussy",
        "omorashi", "one cup two girls", "one guy one jar", "orgasm", "orgy", "paedophile",
        "panties", "panty", "pedobear", "pedophile", "pegging", "penis", "phone sex",
        "piece of shit", "piss pig", "pissing", "pisspig", "playboy", "pleasure chest",
        "pole smoker", "ponyplay", "poof", "poon", "poontang", "poop chute", "poopchute",
        "porn", "porno", "pornography", "prince albert piercing", "pthc", "pubes", "punany",
        "pussy", "queaf", "queef", "quim", "raging boner", "rape", "raping", "rapist",
        "rectum", "reverse cowgirl", "rimjob", "rimming", "rosy palm",
        "rosy palm and her 5 sisters", "rusty trombone", "s&m", "sadism", "santorum", "scat",
        "schlong", "scissoring", "semen", "sex", "sexcam", "sexo", "sexual", "sexuality",
        "sexually", "sexy", "shaved beaver", "shaved pussy", "shibari", "shitblimp",
        "shitty", "shota", "shrimping", "skeet", "slanteye", "slut", "smut", "snatch",
        "snowballing", "sodomize", "sodomy", "splooge", "splooge moose", "spooge",
        "spread legs", "spunk", "strap on", "strapon", "strappado", "strip club",
        "style doggy", "suck a", "suck my", "suicide girls", "sultry women", "swastika", "swinger",
        "tainted love", "taste my", "tea bagging", "threesome", "throating", "thumbzilla",
        "tied up", "tight white", "tit", "tits", "titties", "titty", "tongue in a", "topless",
        "tosser", "tribadism", "tub girl", "tubgirl", "tushy", "twat", "twink", "twinkie",
        "two girls one cup", "undressing", "upskirt", "urethra play", "urophilia", "vagina",
        "venus mound", "viagra", "vibrator", "violet wand", "vorarephilia", "voyeur",
        "voyeurweb", "voyuer", "vulva", "wank", "wet dream", "whore", "worldsex",
        "wrapping men", "wrinkled starfish", "xx", "xxx", "yaoi", "yellow showers", "yiffy",
        "zoophilia"
    };

    /** Slurs and organised-hate vocabulary, English usage. 100 terms. */
    private static final String[] HATE = {
        "nigger", "niggers", "nigga", "niggas", "niger", "negro", "negroes", "coon", "coons",
        "spic", "spics", "wetback", "wetbacks", "chink", "chinks", "chinky", "gook", "gooks",
        "jap", "japs", "paki", "pakis", "raghead", "ragheads", "sandnigger", "towelhead",
        "kike", "kikes", "yid", "yids", "wop", "wops", "dago", "dagos", "kraut", "krauts",
        "gyppo", "pikey", "pikeys", "abo", "abos", "beaner", "beaners", "zipperhead",
        "half breed", "mudblood", "pajeet", "pajeets", "curry muncher", "porch monkey",
        "jungle bunny", "tar baby", "sambo", "golliwog", "redskin", "squaw", "kkk",
        "ku klux klan", "kuklux", "klansman", "white power", "heil hitler", "sieg heil",
        "gas the jews", "1488", "14 88", "blood and soil", "race war", "lynch the", "faggot",
        "faggots", "fagot", "fag", "fags", "dyke", "dykes", "tranny", "trannies", "shemale",
        "shemales", "queer bait", "batty boy", "bumboy", "poofter", "retard", "retards",
        "retarded", "spastic", "spastics", "mongoloid", "mongoloids", "window licker",
        "n1gger", "n1gga", "n1663r", "f4ggot", "f4g", "r3tard", "ch1nk", "p4ki",
        "niga", "nigas", "nijga", "nijger", "nijjer", "nygga", "nygger",
        "nibba", "nibber", "nikka", "nikker", "ngga", "nggas", "ngger", "nggers"
    };

    /** Crude language. Mutes, escalating; not a ban ladder. */
    public static List<String> profanity() {
        return new ArrayList<>(List.of(PROFANITY));
    }

    /** Slurs and organised-hate vocabulary. The harsher ladder. */
    public static List<String> hate() {
        return new ArrayList<>(List.of(HATE));
    }

    /** Every term, both lists. */
    public static List<String> all() {
        List<String> out = new ArrayList<>(PROFANITY.length + HATE.length);
        out.addAll(List.of(PROFANITY));
        out.addAll(List.of(HATE));
        return out;
    }

    public static int size() {
        return PROFANITY.length + HATE.length;
    }
}
