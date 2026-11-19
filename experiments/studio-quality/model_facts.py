"""What the BS-RoFormer SW checkpoint is: parameters, precision, and the settings it runs with."""
import sys, torch
ckpt, cfg = sys.argv[1], sys.argv[2]
state = torch.load(ckpt, map_location="cpu", weights_only=False)
if isinstance(state, dict) and "state_dict" in state: state = state["state_dict"]
n = sum(v.numel() for v in state.values() if hasattr(v, "numel"))
dtypes = sorted({str(v.dtype) for v in state.values() if hasattr(v, "dtype")})
print(f"FACT parameters {n / 1e6:.1f} M, stored as {', '.join(dtypes)}")
text = open(cfg).read()
for key in ("sample_rate", "chunk_size", "dim:", "depth:", "num_stems", "instruments", "num_overlap", "dim_t", "flash_attn", "stft_hop_length", "time_transformer_depth", "freq_transformer_depth"):
    for line in text.splitlines():
        if line.strip().startswith(key):
            print("FACT", line.strip()); break
