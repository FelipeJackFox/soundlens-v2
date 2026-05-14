import os
import io
import json
import boto3

import matplotlib
matplotlib.use("Agg")  # muy importante en Lambda
import matplotlib.pyplot as plt
import numpy as np

s3 = boto3.client("s3")


def handler(event, context):
    # 1) de dónde leer el JSON
    bucket = event.get("s3_bucket") or os.environ.get("RESULT_BUCKET")
    result_key = event.get("result_s3_key")
    if not bucket or not result_key:
        return {"ok": False, "reason": "missing_s3_params"}

    # 2) leer result.json
    obj = s3.get_object(Bucket=bucket, Key=result_key)
    data = json.loads(obj["Body"].read())

    req_id = data.get("request_id", "run")
    title = data.get("title", "Unknown")
    artist = data.get("artist", "Unknown")
    best_off = data.get("offset_frames", 0)
    best_count = data.get("matches_at_best_offset", 0)
    conf = data.get("confidence", 0.0)
    hist = data.get("histogram_top") or []

    # pasamos a arrays
    offsets = np.array([h["offset"] for h in hist], dtype=float)
    counts = np.array([h["count"] for h in hist], dtype=float)

    # por si acaso viene vacío
    if offsets.size == 0:
        return {"ok": False, "reason": "histogram_empty"}

    # 3) figura estilo “visualize”
    fig, axs = plt.subplots(
        3, 1, figsize=(12, 9), dpi=120,
        gridspec_kw={"height_ratios": [2.5, 1.5, 1.5]}
    )

    fig.suptitle(f"{title} – {artist}", fontsize=14, fontweight="bold", y=0.98)
    subtitle = f"best offset={best_off} | matches={best_count} | confidence={conf:.3f}"
    fig.text(0.01, 0.965, subtitle, fontsize=9)

    # 3.1 histograma completo
    ax0 = axs[0]
    # para dar color tipo colormap
    norm_counts = counts / counts.max()
    colors = plt.cm.viridis(norm_counts)
    ax0.bar(offsets, counts, width=3, color=colors, edgecolor="none")
    ax0.axvline(best_off, color="red", linestyle="--", linewidth=1.5, label="best offset")
    ax0.set_ylabel("Matches", fontsize=9)
    ax0.set_title("Histogram of matched offsets (full)", fontsize=10)
    ax0.grid(axis="y", linestyle="--", alpha=0.3)
    ax0.legend(loc="upper right", fontsize=8)

    # 3.2 zoom alrededor del best offset
    ax1 = axs[1]
    window = 200  # frames a cada lado
    mask = (offsets >= best_off - window) & (offsets <= best_off + window)
    if mask.any():
        ax1.bar(offsets[mask], counts[mask], width=3, color="#4C72B0")
    ax1.axvline(best_off, color="red", linestyle="--", linewidth=1.5)
    ax1.set_title(f"Zoom around best offset ±{window} frames", fontsize=10)
    ax1.set_ylabel("Matches", fontsize=9)
    ax1.grid(axis="y", linestyle="--", alpha=0.3)

    # 3.3 top-N offsets ordenados por conteo (como “picos”)
    ax2 = axs[2]
    top_n = 20
    idx_sorted = np.argsort(counts)[::-1][:top_n]
    top_offsets = offsets[idx_sorted]
    top_counts = counts[idx_sorted]
    # etiquetas tipo "4465" "4466"...
    ax2.bar(np.arange(len(top_offsets)), top_counts, color="#dd8452")
    ax2.set_xticks(np.arange(len(top_offsets)))
    ax2.set_xticklabels([str(int(o)) for o in top_offsets], rotation=45, ha="right", fontsize=7)
    ax2.set_title(f"Top {len(top_offsets)} offsets by count", fontsize=10)
    ax2.set_ylabel("Matches", fontsize=9)
    ax2.grid(axis="y", linestyle="--", alpha=0.3)

    plt.tight_layout(rect=[0, 0, 1, 0.95])

    # 4) guardar en S3
    out_key = f"{os.environ.get('RESULTS_PREFIX','results/').rstrip('/')}/{req_id}/match.png"
    buf = io.BytesIO()
    fig.savefig(buf, format="png", bbox_inches="tight")
    plt.close(fig)
    buf.seek(0)

    s3.put_object(
        Bucket=bucket,
        Key=out_key,
        Body=buf.getvalue(),
        ContentType="image/png"
    )

    # 5) presign
    presign = os.environ.get("PRESIGN_EX_SEC")
    resp = {"ok": True, "plot_s3_key": out_key}
    if presign:
        resp["plot_url"] = s3.generate_presigned_url(
            "get_object",
            Params={"Bucket": bucket, "Key": out_key},
            ExpiresIn=int(presign),
        )
    return resp
