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

a { color:var(--teal); } a.tab, a.nav, a.kpi-link, .crumbs a { text-decoration:none; }
a:focus-visible, button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible { outline:2px solid var(--teal); outline-offset:2px; }
.skip { position:absolute; left:-999px; top:8px; background:var(--teal); color:#042f2e; padding:6px 12px; border-radius:8px; z-index:10; text-decoration:none; }
.skip:focus { left:8px; }
.top { padding:10px 16px; } .row.tight { margin:0; gap:10px; } .who { max-width:40vw; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; } .pill.role { margin:0; }
.shell { display:grid; grid-template-columns:232px minmax(0,1fr); align-items:start; }
.side { position:sticky; top:57px; height:calc(100vh - 57px); overflow-y:auto; padding:14px 10px; border-right:1px solid var(--line); }
.grp { margin-bottom:18px; } .grp-t { text-transform:uppercase; letter-spacing:.08em; font-size:11px; color:var(--muted); padding:0 10px 6px; }
a.nav { display:flex; align-items:center; gap:10px; padding:8px 10px; border-radius:10px; color:var(--muted); border:1px solid transparent; }
a.nav:hover { color:var(--text); background:var(--card); }
a.nav.on { color:var(--teal); background:rgba(0,199,183,.1); border-color:rgba(0,199,183,.35); }
.icon { width:18px; height:18px; flex:none; fill:none; stroke:currentColor; stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round; }
.badge { margin-left:auto; background:var(--red); color:#2a0709; border-radius:999px; font-size:11px; font-weight:700; padding:1px 7px; } .badge[hidden] { display:none; }
.main { padding:18px 22px 40px; max-width:1100px; width:100%; }
.crumbs ol { list-style:none; display:flex; flex-wrap:wrap; gap:0; margin:0 0 6px; padding:0; font-size:12px; color:var(--muted); }
.crumbs li + li::before { content:"/"; margin:0 7px; opacity:.6; }
.pagehead { margin-bottom:14px; } .pagehead h1 { font-size:22px; margin:0 0 2px; outline:none; } .desc { margin:0; }
.seg { gap:6px; margin:0 0 12px; }
a.tab { display:inline-block; color:var(--text); } a.tab:hover { border-color:var(--teal); } a.tab.on { background:var(--deep); border-color:var(--deep); }
a.kpi-link { display:block; color:inherit; } a.kpi-link:hover { border-color:var(--teal); }
.scroll { overflow-x:auto; } .scroll table { min-width:560px; }
.menu-btn, .scrim { display:none; }
@media (max-width: 860px) {
  .shell { grid-template-columns:minmax(0,1fr); }
  .menu-btn { display:inline-flex; align-items:center; padding:7px 10px; }
  .side { position:fixed; top:57px; left:0; bottom:0; height:auto; width:264px; max-width:84vw; background:var(--bg); z-index:4; transform:translateX(-105%); visibility:hidden; transition:transform .18s ease, visibility 0s linear .18s; }
  .menu-open .side { transform:none; visibility:visible; transition:transform .18s ease, visibility 0s; }
  .menu-open .scrim { display:block; position:fixed; inset:57px 0 0 0; background:rgba(0,0,0,.6); z-index:3; }
  .main { padding:14px 14px 40px; } .who { display:none; }
}
@media (prefers-reduced-motion: reduce) { .side, .menu-open .side { transition:none; } }
`;
