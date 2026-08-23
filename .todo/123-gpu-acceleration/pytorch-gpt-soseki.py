# The book's chapter-3 GPT (nanoGPT-style char model) at the book's shapes, in PyTorch,
# for a step-time comparison against rontolisp's port on the same machine.
import math, sys, time, torch, torch.nn as nn, torch.nn.functional as F

mode = sys.argv[1] if len(sys.argv) > 1 else "fp32"        # fp32 | bf16 | compile
steps = int(sys.argv[2]) if len(sys.argv) > 2 else 300
torch.manual_seed(1337)
dev = "cuda"
text = open("soseki.txt", encoding="utf-8").read()
chars = sorted(set(text)); stoi = {c: i for i, c in enumerate(chars)}
data = torch.tensor([stoi[c] for c in text], dtype=torch.long)
n = int(0.9 * len(data)); train, val = data[:n], data[n:]
V, B, T, C, L, H, DROP = len(chars), 64, 256, 384, 6, 6, 0.1
print(f"corpus {len(text)} chars, vocab {V}, mode {mode}, steps {steps}")

def get_batch(split):
    d = train if split == "train" else val
    ix = torch.randint(len(d) - T, (B,))
    x = torch.stack([d[i:i+T] for i in ix]); y = torch.stack([d[i+1:i+1+T] for i in ix])
    return x.to(dev, non_blocking=True), y.to(dev, non_blocking=True)

class Head(nn.Module):
    def __init__(s, hs):
        super().__init__(); s.k = nn.Linear(C, hs, bias=False); s.q = nn.Linear(C, hs, bias=False); s.v = nn.Linear(C, hs, bias=False)
        s.register_buffer("tril", torch.tril(torch.ones(T, T))); s.drop = nn.Dropout(DROP)
    def forward(s, x):
        b, t, c = x.shape; k, q, v = s.k(x), s.q(x), s.v(x)
        w = q @ k.transpose(-2, -1) * k.shape[-1] ** -0.5
        w = w.masked_fill(s.tril[:t, :t] == 0, float("-inf")); w = F.softmax(w, dim=-1); w = s.drop(w)
        return w @ v
class MHA(nn.Module):
    def __init__(s):
        super().__init__(); s.heads = nn.ModuleList([Head(C // H) for _ in range(H)]); s.proj = nn.Linear(C, C); s.drop = nn.Dropout(DROP)
    def forward(s, x): return s.drop(s.proj(torch.cat([h(x) for h in s.heads], dim=-1)))
class FF(nn.Module):
    def __init__(s): super().__init__(); s.net = nn.Sequential(nn.Linear(C, 4 * C), nn.GELU(), nn.Linear(4 * C, C), nn.Dropout(DROP))
    def forward(s, x): return s.net(x)
class Block(nn.Module):
    def __init__(s): super().__init__(); s.sa = MHA(); s.ff = FF(); s.ln1 = nn.LayerNorm(C); s.ln2 = nn.LayerNorm(C)
    def forward(s, x): x = x + s.sa(s.ln1(x)); return x + s.ff(s.ln2(x))
class GPT(nn.Module):
    def __init__(s):
        super().__init__(); s.tok = nn.Embedding(V, C); s.pos = nn.Embedding(T, C)
        s.blocks = nn.Sequential(*[Block() for _ in range(L)]); s.ln = nn.LayerNorm(C); s.head = nn.Linear(C, V)
    def forward(s, idx, targets=None):
        b, t = idx.shape; x = s.tok(idx) + s.pos(torch.arange(t, device=dev))
        logits = s.head(s.ln(s.blocks(x)))
        loss = None if targets is None else F.cross_entropy(logits.view(-1, V), targets.view(-1))
        return logits, loss

model = GPT().to(dev)
print(f"{sum(p.numel() for p in model.parameters())/1e6:.2f} M parameters")
opt = torch.optim.AdamW(model.parameters(), lr=3e-4, weight_decay=0.1)
fwd = torch.compile(model) if mode == "compile" else model
autocast = torch.autocast("cuda", dtype=torch.bfloat16) if mode in ("bf16", "compile") else torch.autocast("cuda", enabled=False)
t0 = time.time(); marks = {}
for step in range(steps):
    x, y = get_batch("train")
    with autocast:
        _, loss = fwd(x, y)
    opt.zero_grad(set_to_none=True); loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0); opt.step()
    if step in (3, 13, 40, 200, steps - 1):
        torch.cuda.synchronize(); marks[step] = time.time() - t0
        print(f"step {step} loss {loss.item():.4f} t {marks[step]:.1f}s", flush=True)
torch.cuda.synchronize(); total = time.time() - t0
if 13 in marks: print(f"(t13 - t3)/10 = {(marks[13]-marks[3])/10:.4f} s/step")
if 200 in marks and 40 in marks: print(f"(t200 - t40)/160 = {(marks[200]-marks[40])/160:.4f} s/step")
print(f"total {total:.1f} s for {steps} steps = {total/steps:.4f} s/step")
