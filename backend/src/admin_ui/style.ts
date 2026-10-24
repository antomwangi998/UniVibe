export const ADMIN_CSS = String.raw`
:root { --bg:#000; --card:#0f1114; --line:#2f3336; --text:#e7e9ea; --muted:#8b98a5; --teal:#00c7b7; --deep:#0f766e; --red:#f4727a; --amber:#f0b429; }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:15px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
h1,h2,h3 { margin:0 0 8px; font-weight:650; } h1 { font-size:20px; } h2 { font-size:17px; } h3 { font-size:14px; color:var(--muted); font-weight:600; }
.wrap { max-width:1100px; margin:0 auto; padding:16px; }
.top { display:flex; align-items:center; gap:12px; justify-content:space-between; padding:12px 16px; border-bottom:1px solid var(--line); position:sticky; top:0; background:var(--bg); z-index:2; }
.brand { font-weight:700; font-size:18px; } .brand b { color:var(--teal); }
.tabs { display:flex; gap:6px; overflow-x:auto; padding:10px 16px; border-bottom:1px solid var(--line); }
.tab { background:none; border:1px solid var(--line); color:var(--text); border-radius:999px; padding:6px 14px; cursor:pointer; white-space:nowrap; font:inherit; }
.tab.on { background:var(--deep); border-color:var(--deep); }
.grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; margin-bottom:16px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:12px 14px; margin-bottom:10px; }
.kpi .n { font-size:26px; font-weight:700; } .kpi .l { color:var(--muted); font-size:13px; }
.kpi.warn .n { color:var(--amber); } .kpi.bad .n { color:var(--red); }
.charts { display:grid; grid-template-columns:repeat(auto-fill,minmax(300px,1fr)); gap:10px; }
svg .bar { fill:var(--teal); } svg .bar.today { fill:var(--deep); } svg text { fill:var(--muted); font-size:10px; }
input, select, textarea { background:#000; color:var(--text); border:1px solid var(--line); border-radius:10px; padding:8px 10px; font:inherit; max-width:100%; }
textarea { width:100%; min-height:56px; }
button.btn { background:var(--teal); color:#042f2e; border:0; border-radius:10px; padding:8px 14px; font:inherit; font-weight:600; cursor:pointer; }
button.btn.ghost { background:none; border:1px solid var(--line); color:var(--text); }
button.btn.danger { background:var(--red); color:#2a0709; }
button.btn:disabled { opacity:.5; cursor:default; }
.row { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin:6px 0; }
.muted { color:var(--muted); } .small { font-size:13px; }
.pill { display:inline-block; border:1px solid var(--line); border-radius:999px; padding:1px 9px; font-size:12px; margin-right:6px; }
.pill.red { color:var(--red); border-color:var(--red); } .pill.amber { color:var(--amber); border-color:var(--amber); } .pill.teal { color:var(--teal); border-color:var(--teal); }
.quote { border-left:3px solid var(--line); padding:4px 10px; margin:8px 0; white-space:pre-wrap; word-break:break-word; }
.err { color:var(--red); margin:8px 0; } .ok { color:var(--teal); }
table { width:100%; border-collapse:collapse; } td, th { text-align:left; padding:6px 8px; border-bottom:1px solid var(--line); vertical-align:top; font-size:13px; } th { color:var(--muted); font-weight:600; }
.login { max-width:340px; margin:12vh auto; } .login input { width:100%; margin:6px 0; }
.link { cursor:pointer; color:var(--teal); background:none; border:0; padding:0; font:inherit; text-align:left; }
`;
