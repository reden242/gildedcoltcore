"""Train the anti-advertising classifier and export the FTA1 binary.

This replaces the fasttext C++ library (which does not build on Python 3.14) with
an equivalent trainer in NumPy: hashed char 3-6-gram subword embeddings + word
vectors, mean-pooled, into a softmax over the labels. The subword hash is
FNV-1a 32-bit unsigned over the UTF-8 bytes of the n-gram with '<'/'>' boundary
markers, exactly as the Java loader computes it, so training and inference see
identical features.

Training data (label<TAB>text lines):
  - seed_corpus.tsv   from SeedCorpus (clean + advertising, mutated variants)
  - chat_corpus.txt   real clean chat from the production server
  - benign domain mentions are already inside the seed corpus clean set

Outputs:
  - antiad.ft.bin         quantized model, FTA1 format, hard-fails above 5 MB
  - vectors-check.tsv     sample texts + expected probabilities for Java parity
"""
import sys, math, re, pathlib
import numpy as np

HERE = pathlib.Path(__file__).parent
SEED = HERE / "seed_corpus.tsv"
TLD_CORPUS = HERE / "tld_corpus.tsv"
REALCHAT = HERE.parent / "chat_corpus.txt"
VECTORS = HERE / "crawl-100d.npz"
MODEL_OUT = HERE / "antiad.ft.bin"
CHECK_OUT = HERE / "vectors-check.tsv"

DIM = 100
MINN, MAXN = 3, 6
BUCKET = 30000
MIN_COUNT = 2
EPOCHS = 25
LR = 0.4
LABELS = ["advertising", "clean"]   # index order is part of the file format

TOKEN_RE = re.compile(r"[a-z0-9']+")

def fnv1a(data: bytes) -> int:
    h = 2166136261
    for b in data:
        h ^= b
        h = (h * 16777619) & 0xFFFFFFFF
    return h

def subwords(word: str):
    """Character n-grams with fastText's '<'/'>' boundary markers."""
    padded = f"<{word}>"
    out = []
    for n in range(MINN, MAXN + 1):
        for i in range(len(padded) - n + 1):
            out.append(padded[i:i + n])
    return out

def tokenize(text: str):
    return TOKEN_RE.findall(text.lower())

def main():
    # ---- load data -------------------------------------------------------
    texts, labels = [], []
    def add(text, label):
        text = " ".join(text.split())
        if not text or len(text) > 300:
            return
        texts.append(text)
        labels.append(label)

    with open(SEED, encoding="utf-8") as f:
        for line in f:
            label, _, text = line.rstrip("\n").partition("\t")
            if label in LABELS:
                add(text, label)

    # Every IANA TLD, advertising and benign, from tld_corpus.py. This is what
    # keeps a host on an obscure TLD from being invisible to the model.
    if TLD_CORPUS.exists():
        with open(TLD_CORPUS, encoding="utf-8") as f:
            for line in f:
                label, _, text = line.rstrip("\n").partition("\t")
                if label in LABELS:
                    add(text, label)

    if REALCHAT.exists():
        with open(REALCHAT, encoding="utf-8") as f:
            for line in f:
                add(line, "clean")

    y = np.array([0 if l == "advertising" else 1 for l in labels], dtype=np.int64)
    print(f"loaded {len(texts)} samples "
          f"({int((y==0).sum())} advertising / {int((y==1).sum())} clean)")

    # ---- build vocab -----------------------------------------------------
    counts = {}
    for t in texts:
        for w in set(tokenize(t)):
            counts[w] = counts.get(w, 0) + 1
    vocab = [w for w, c in counts.items() if c >= MIN_COUNT]
    vocab.sort(key=lambda w: (-counts[w], w))
    word_index = {w: i for i, w in enumerate(vocab)}
    print(f"vocab: {len(vocab)} words (min_count={MIN_COUNT})")

    # ---- embedding init from crawl vectors -------------------------------
    init = np.zeros((len(vocab), DIM), dtype=np.float32)
    hit = 0
    if VECTORS.exists():
        data = np.load(VECTORS, allow_pickle=True)
        table = {w: i for i, w in enumerate(data["words"])}
        vecs = data["vectors"]
        for w, i in word_index.items():
            j = table.get(w)
            if j is not None:
                init[i] = vecs[j]
                hit += 1
        del data, table, vecs
        print(f"initialised {hit}/{len(vocab)} word rows from crawl vectors")

    rng = np.random.default_rng(1337)
    # Unknown words still need non-zero rows so subwords alone can carry signal.
    for i in range(len(vocab)):
        if not init[i].any():
            init[i] = rng.normal(0, 0.05, DIM).astype(np.float32)

    input_matrix = np.vstack([
        init,                                   # word rows
        rng.normal(0, 0.05, (BUCKET, DIM)).astype(np.float32),  # subword rows
    ])
    output_matrix = np.zeros((len(LABELS), DIM), dtype=np.float32)

    # ---- feature index lists ---------------------------------------------
    def features(text):
        """Indices into input_matrix: word rows then hashed subword rows."""
        idx = []
        for w in tokenize(text):
            wi = word_index.get(w)
            if wi is not None:
                idx.append(wi)
            if len(w) + 2 >= MINN:
                idx.append(len(vocab) + fnv1a(f"<{w}>".encode()) % BUCKET)
            for sw in subwords(w):
                idx.append(len(vocab) + fnv1a(sw.encode()) % BUCKET)
        return idx

    print("featurizing...", flush=True)
    feats = [features(t) for t in texts]
    order = rng.permutation(len(texts))
    n_val = max(200, len(texts) // 10)
    val_idx, tr_idx = order[:n_val], order[n_val:]

    # ---- training --------------------------------------------------------
    print(f"training: {len(tr_idx)} train / {len(val_idx)} val, "
          f"{EPOCHS} epochs, lr {LR}", flush=True)
    for epoch in range(EPOCHS):
        lr = LR * (1 - epoch / EPOCHS)
        rng.shuffle(tr_idx)
        total_loss, seen = 0.0, 0
        for i in tr_idx:
            idx = feats[i]
            if not idx:
                continue
            rows = input_matrix[idx]                # (k, DIM)
            hidden = rows.mean(axis=0)              # (DIM,)
            logits = output_matrix @ hidden         # (2,)
            logits -= logits.max()
            exp = np.exp(logits)
            p = exp / exp.sum()
            total_loss -= math.log(max(p[y[i]], 1e-12))
            seen += 1
            # softmax cross-entropy gradient
            g = p.copy()
            g[y[i]] -= 1.0
            grad_hidden = g @ output_matrix        # (DIM,)
            output_matrix -= lr * np.outer(g, hidden)
            input_matrix[idx] -= lr * (grad_hidden / len(idx))
        if epoch % 5 == 4 or epoch == EPOCHS - 1:
            val_metrics = evaluate(feats, y, val_idx, input_matrix, output_matrix)
            print(f"  epoch {epoch+1}/{EPOCHS} loss={total_loss/max(seen,1):.4f} "
                  f"val P/R={val_metrics[0]:.3f}/{val_metrics[1]:.3f}", flush=True)

    # ---- final metrics gates ---------------------------------------------
    precision, recall, confs = evaluate(feats, y, val_idx, input_matrix,
                                        output_matrix, want_confs=True)
    clean_confs = confs[y[val_idx] == 1]
    clean_flag_rate = float((clean_confs >= 0.5).mean())
    print(f"raw     precision={precision:.3f} recall={recall:.3f} "
          f"clean-flag-rate={clean_flag_rate:.3f}")
    if precision < 0.97 or recall < 0.90 or clean_flag_rate > 0.10:
        print("METRIC GATES FAILED - not exporting", flush=True)
        return 1

    # ---- temperature scaling ---------------------------------------------
    # The raw model is near-perfectly confident, which empties the 0.4-0.85
    # ambiguity band that gates the L3 LLM referral. Scaling the output matrix
    # by 1/T divides every logit by T: identical 0.5-threshold decisions, but
    # softer confidences. Pick the smallest T that puts useful mass in the band.
    band = 0.0
    for t in (1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0):
        _, _, c = evaluate(feats, y, val_idx, input_matrix,
                           output_matrix / t, want_confs=True)
        band = float(((c > 0.4) & (c < 0.85)).mean())
        print(f"  temperature {t}: ambiguous-band={band:.3f}")
        if band >= 0.03:
            output_matrix = (output_matrix / t).astype(np.float32)
            print(f"using temperature {t}")
            break
    if band < 0.01:
        print("HARD FAIL: no confidence mass in the 0.4-0.85 band, "
              "L3 would never fire", flush=True)
        return 1

    # ---- export ----------------------------------------------------------
    q_input, in_scale = quantize(input_matrix)
    q_output, out_scale = quantize(output_matrix)
    write_model(vocab, q_input, in_scale, q_output, out_scale)
    # Parity vectors must come from the DEQUANTIZED matrices: what Java actually
    # loads. Golden values from the raw floats would include int8 rounding the
    # shipped model does not have.
    dq_input = q_input.astype(np.float32) * in_scale
    dq_output = q_output.astype(np.float32) * out_scale
    write_check(texts, y, feats, dq_input, dq_output)
    return 0

def evaluate(feats, y, idx, input_matrix, output_matrix, want_confs=False):
    tp = fp = fn = 0
    confs = []
    for i in idx:
        f = feats[i]
        if not f:
            confs.append(0.0)
            continue
        hidden = input_matrix[f].mean(axis=0)
        logits = output_matrix @ hidden
        e = np.exp(logits - logits.max())
        p = e / e.sum()
        confs.append(float(p[0]))           # p(advertising)
        pred = 0 if p[0] >= 0.5 else 1
        if pred == 0 and y[i] == 0: tp += 1
        elif pred == 0 and y[i] == 1: fp += 1
        elif pred == 1 and y[i] == 0: fn += 1
    precision = tp / max(tp + fp, 1)
    recall = tp / max(tp + fn, 1)
    if want_confs:
        return precision, recall, np.array(confs)
    return precision, recall

def quantize(matrix):
    scale = float(np.abs(matrix).max()) / 127.0 or 1.0
    q = np.clip(np.round(matrix / scale), -127, 127).astype(np.int8)
    return q, scale

def write_model(vocab, q_input, in_scale, q_output, out_scale):
    import struct
    with open(MODEL_OUT, "wb") as f:
        f.write(b"FTA1")
        f.write(struct.pack(">i", DIM))
        f.write(struct.pack(">i", MINN))
        f.write(struct.pack(">i", MAXN))
        f.write(struct.pack(">i", BUCKET))
        f.write(struct.pack(">i", len(vocab)))
        f.write(struct.pack(">i", len(LABELS)))
        for w in vocab:                      # writeUTF format: u2 len + bytes
            b = w.encode("utf-8")
            f.write(struct.pack(">H", len(b)))
            f.write(b)
        for label in LABELS:
            b = label.encode("utf-8")
            f.write(struct.pack(">H", len(b)))
            f.write(b)
        f.write(struct.pack(">f", in_scale))
        f.write(q_input.tobytes())
        f.write(struct.pack(">f", out_scale))
        f.write(q_output.tobytes())
    size = MODEL_OUT.stat().st_size
    print(f"wrote {MODEL_OUT} ({size/1e6:.2f} MB)")
    if size > 5_000_000:
        print("HARD FAIL: model exceeds 5 MB budget")
        sys.exit(1)

def write_check(texts, y, feats, input_matrix, output_matrix):
    """Golden vectors for the Java parity test: text -> p(advertising)."""
    rng = np.random.default_rng(7)
    ads = [i for i in range(len(texts)) if y[i] == 0]
    cleans = [i for i in range(len(texts)) if y[i] == 1]
    picks = list(rng.choice(ads, 5)) + list(rng.choice(cleans, 5))
    with open(CHECK_OUT, "w", encoding="utf-8") as f:
        for i in picks:
            f_ = feats[i]
            hidden = input_matrix[f_].mean(axis=0) if len(f_) else np.zeros(DIM)
            logits = output_matrix @ hidden
            e = np.exp(logits - logits.max())
            p = e / e.sum()
            text = texts[i].replace("\t", " ")
            f.write(f"{p[0]:.6f}\t{text}\n")
    print(f"wrote {CHECK_OUT}")

if __name__ == "__main__":
    sys.exit(main())
