"""Reduce the Common Crawl fastText vectors to something we can train with.

crawl-300d-2M-subword.zip ships 2 million 300-dimensional word vectors. We want
neither the 2 million (Minecraft chat uses a few thousand distinct words) nor the
300 dimensions (300 floats per row puts the shipped model far over its 5 MB
budget). So: stream the .vec straight out of the zip without unpacking 4.5 GB to
disk, keep the frequency-ordered head, and project it down with PCA.

Output: antiad/crawl-100d.npz, a word -> 100-dim vector table used only to
initialise the classifier's embedding rows. It never ships with the plugin.
"""
import zipfile, sys, numpy as np, pathlib, io

ZIP = pathlib.Path(r"C:\Users\Administrator\Downloads\crawl-300d-2M-subword.zip")
MEMBER = "crawl-300d-2M-subword.vec"
OUT = pathlib.Path(__file__).with_name("crawl-100d.npz")
TOP_WORDS = 200_000     # frequency-ordered, so the head is the useful part
OUT_DIM = 100

def main():
    words, rows = [], []
    with zipfile.ZipFile(ZIP) as z, z.open(MEMBER) as raw:
        stream = io.TextIOWrapper(raw, encoding="utf-8", errors="replace")
        header = stream.readline().split()
        total, dim = int(header[0]), int(header[1])
        print(f"source: {total} words x {dim} dims; taking first {TOP_WORDS}", flush=True)
        for i, line in enumerate(stream):
            if i >= TOP_WORDS:
                break
            parts = line.rstrip().rsplit(" ", dim)
            if len(parts) != dim + 1:
                continue
            word = parts[0]
            # Vectors are only useful to us lowercased: chat is lowercased before
            # it ever reaches the model, and the head of the list has both cases.
            lower = word.lower()
            if lower != word:
                continue
            words.append(lower)
            rows.append(np.asarray(parts[1:], dtype=np.float32))
            if len(words) % 25_000 == 0:
                print(f"  read {len(words)} kept / {i} scanned", flush=True)

    x = np.vstack(rows)
    del rows
    print(f"kept {x.shape[0]} words, matrix {x.shape}", flush=True)

    # PCA by eigendecomposition of the 300x300 covariance - cheap and exact at
    # this width, no need for sklearn's randomized solver.
    mean = x.mean(axis=0)
    x -= mean
    cov = (x.T @ x) / (x.shape[0] - 1)
    eigvals, eigvecs = np.linalg.eigh(cov)
    order = np.argsort(eigvals)[::-1][:OUT_DIM]
    basis = eigvecs[:, order]                       # (300, 100)
    kept_variance = eigvals[order].sum() / eigvals.sum()
    reduced = (x @ basis).astype(np.float32)        # (N, 100)

    # L2-normalise so every initialised row starts at a comparable scale.
    norms = np.linalg.norm(reduced, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    reduced /= norms

    print(f"PCA {x.shape[1]} -> {OUT_DIM} dims, retained variance {kept_variance:.3f}", flush=True)
    np.savez_compressed(OUT, words=np.array(words, dtype=object), vectors=reduced)
    print(f"wrote {OUT} ({OUT.stat().st_size/1e6:.1f} MB)", flush=True)

if __name__ == "__main__":
    sys.exit(main())
