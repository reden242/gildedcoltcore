import com.coltcore.core.modules.SeedCorpus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Offline dump of the SeedCorpus training data to label<TAB>text TSV. */
public final class DumpCorpus {

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "seed_corpus.tsv");
        List<SeedCorpus.Sample> samples = SeedCorpus.build(
                List.of("clean", "advertising"));
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (SeedCorpus.Sample s : samples) {
                String text = s.text().replace('\t', ' ').replace('\n', ' ').trim();
                if (text.isEmpty()) continue;
                w.write(s.label());
                w.write('\t');
                w.write(text);
                w.write('\n');
            }
        }
        System.out.println("wrote " + out + " samples=" + samples.size());
    }
}
