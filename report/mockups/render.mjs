// Static UI mockups for the SGU Tour report.
// Renders HTML pages that are screenshotted with headless Chrome.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.join(__dirname, 'html');
fs.mkdirSync(outDir, { recursive: true });

const P = '#21776d';
const PD = '#17564f';
const ACC = '#f59e0b';
const INK = '#1f2937';
const MUTED = '#6b7280';
const LINE = '#e5e7eb';
const BG = '#f5f7f6';

const money = (n) => n.toLocaleString('vi-VN') + ' ₫';

const css = `
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:1440px;background:#fff;color:${INK};font-family:"Segoe UI",Inter,Arial,sans-serif;-webkit-font-smoothing:antialiased}
a{text-decoration:none;color:inherit}
img{display:block;max-width:100%}
.wrap{width:1280px;margin:0 auto}
.topbar{height:34px;background:${PD};color:#d7ece7;font-size:12.5px}
.topbar .wrap{height:34px;display:flex;align-items:center;justify-content:space-between}
.topbar b{color:#fff;font-weight:600}
header{height:74px;background:#fff;border-bottom:1px solid ${LINE};position:relative;z-index:5}
header .wrap{height:74px;display:flex;align-items:center;gap:34px}
.logo{height:46px}
nav{display:flex;gap:26px;font-size:14.5px;font-weight:600;color:#374151}
nav a.on{color:${P}}
.nav-right{margin-left:auto;display:flex;align-items:center;gap:16px}
.hotline{font-size:12px;color:${MUTED};text-align:right;line-height:1.25}
.hotline b{display:block;color:${P};font-size:16px}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:7px;border:0;cursor:pointer;
  background:${P};color:#fff;font-weight:600;font-size:14px;border-radius:9px;padding:0 22px;height:42px}
.btn:hover{background:${PD}}
.btn.ghost{background:#fff;color:${P};border:1.5px solid ${P}}
.btn.warn{background:${ACC}}
.btn.sm{height:34px;padding:0 14px;font-size:13px;border-radius:7px}
.btn.plain{background:#fff;color:#374151;border:1px solid ${LINE}}
.btn.red{background:#dc2626}
.hero{position:relative;height:392px;background:#123;overflow:visible}
.hero img.bg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.hero .mask{position:absolute;inset:0;background:linear-gradient(90deg,rgba(8,35,32,.82),rgba(8,35,32,.28) 60%,rgba(8,35,32,.1))}
.hero .inner{position:relative;width:1280px;margin:0 auto;padding-top:64px;color:#fff}
.hero h1{font-size:44px;line-height:1.15;max-width:640px;font-weight:800;letter-spacing:-.5px}
.hero p{margin-top:12px;font-size:16px;color:#d8ece7;max-width:560px}
.searchcard{position:absolute;left:50%;transform:translateX(-50%);bottom:-40px;width:1120px;background:#fff;border-radius:16px;
  box-shadow:0 18px 40px rgba(15,50,45,.18);padding:18px 20px;display:grid;grid-template-columns:1.4fr 1fr .9fr 150px;gap:14px;z-index:3}
.field label{display:block;font-size:11.5px;font-weight:700;color:${MUTED};text-transform:uppercase;letter-spacing:.4px;margin-bottom:5px}
.field .box{height:46px;border:1px solid #d7ddd9;border-radius:9px;display:flex;align-items:center;padding:0 13px;font-size:14px;color:#374151;gap:8px}
.searchcard .btn{height:46px;align-self:end}
section.block{padding:74px 0 10px}
.sec-head{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:24px}
.sec-head h2{font-size:27px;font-weight:800;letter-spacing:-.3px}
.sec-head p{color:${MUTED};font-size:13.5px;margin-top:5px}
.sec-head a{color:${P};font-weight:700;font-size:13.5px}
.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:22px}
.tour{background:#fff;border:1px solid ${LINE};border-radius:14px;overflow:hidden;box-shadow:0 4px 14px rgba(15,50,45,.05)}
.tour .ph{height:172px;position:relative;overflow:hidden}
.tour .ph img{width:100%;height:100%;object-fit:cover}
.tour .tag{position:absolute;left:12px;top:12px;background:${ACC};color:#3b2400;font-size:11px;font-weight:800;padding:4px 9px;border-radius:6px}
.tour .tag.blue{background:${P};color:#fff}
.tour .body{padding:14px 15px 16px}
.tour h3{font-size:15.5px;font-weight:700;line-height:1.35;min-height:42px}
.meta{display:flex;align-items:center;gap:8px;color:${MUTED};font-size:12px;margin-top:7px}
.stars{color:${ACC};letter-spacing:1px;font-size:12.5px}
.price-row{display:flex;align-items:flex-end;justify-content:space-between;margin-top:12px;padding-top:11px;border-top:1px dashed ${LINE}}
.price{color:#c2410c;font-weight:800;font-size:17px}
.price small{font-size:11px;color:${MUTED};font-weight:500;display:block}
.why{display:grid;grid-template-columns:repeat(4,1fr);gap:20px;margin-top:6px}
.why .it{background:#fff;border:1px solid ${LINE};border-radius:13px;padding:20px;display:flex;gap:14px;align-items:flex-start}
.ic{width:44px;height:44px;border-radius:11px;background:#e6f3f0;color:${P};display:flex;align-items:center;justify-content:center;font-size:20px;flex:0 0 auto}
.why h4{font-size:14.5px;margin-bottom:4px}
.why p{font-size:12.5px;color:${MUTED};line-height:1.5}
.dest{display:grid;grid-template-columns:repeat(4,1fr);gap:18px}
.dest .d{height:170px;border-radius:13px;overflow:hidden;position:relative}
.dest .d img{width:100%;height:100%;object-fit:cover}
.dest .d span{position:absolute;left:16px;bottom:14px;color:#fff;font-weight:800;font-size:17px;text-shadow:0 2px 8px rgba(0,0,0,.5)}
.reviews{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}
.rev{background:#fff;border:1px solid ${LINE};border-radius:13px;padding:20px}
.rev p{font-size:13px;color:#4b5563;line-height:1.6;margin:10px 0 14px}
.who{display:flex;align-items:center;gap:10px}
.av{width:38px;height:38px;border-radius:50%;background:#d7ece7;color:${P};display:flex;align-items:center;justify-content:center;font-weight:800;font-size:14px}
.who b{font-size:13px}.who span{display:block;font-size:11.5px;color:${MUTED}}
footer{margin-top:66px;background:#0e2b27;color:#b9d3cd;padding:46px 0 0}
footer .cols{display:grid;grid-template-columns:1.5fr 1fr 1fr 1.2fr;gap:36px;padding-bottom:34px}
footer h5{color:#fff;font-size:14px;margin-bottom:14px}
footer p,footer li{font-size:12.5px;line-height:1.9;list-style:none}
footer .bottom{border-top:1px solid rgba(255,255,255,.12);padding:16px 0;font-size:12px;color:#8fb3ac}
.pagehead{height:150px;background:linear-gradient(120deg,${PD},${P});display:flex;align-items:center}
.pagehead .wrap{color:#fff}
.pagehead h1{font-size:32px;font-weight:800}
.pagehead p{margin-top:6px;color:#cde5df;font-size:13.5px}
.crumb{font-size:12.5px;color:#cde5df;margin-bottom:8px}
.layout2{display:grid;grid-template-columns:280px 1fr;gap:26px;padding:34px 0}
.panel{background:#fff;border:1px solid ${LINE};border-radius:13px}
.panel .ph2{padding:15px 18px;border-bottom:1px solid ${LINE};font-weight:800;font-size:14.5px;display:flex;justify-content:space-between;align-items:center}
.panel .pb{padding:16px 18px}
.fgroup{margin-bottom:18px}
.fgroup h6{font-size:12px;text-transform:uppercase;letter-spacing:.5px;color:${MUTED};margin-bottom:9px}
.chk{display:flex;align-items:center;gap:9px;font-size:13px;color:#374151;padding:5px 0}
.chk i{width:16px;height:16px;border:1.5px solid #b9c4bf;border-radius:4px;display:inline-block}
.chk i.on{background:${P};border-color:${P};position:relative}
.chk i.on:after{content:"✓";position:absolute;color:#fff;font-size:10px;left:2px;top:-2px}
.range{height:5px;background:#e3e8e5;border-radius:4px;position:relative;margin:12px 0 6px}
.range:before{content:"";position:absolute;left:0;right:34%;top:0;bottom:0;background:${P};border-radius:4px}
.range i{position:absolute;width:15px;height:15px;border-radius:50%;background:#fff;border:3px solid ${P};top:-5px}
.range i.a{left:0}.range i.b{right:30%}
.grid3{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}
.toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}
.tabs{display:flex;gap:6px;border-bottom:1px solid ${LINE};margin-bottom:4px}
.tabs a{padding:11px 18px;font-size:13.5px;font-weight:700;color:${MUTED};border-bottom:2.5px solid transparent}
.tabs a.on{color:${P};border-color:${P}}
.badge{display:inline-flex;align-items:center;padding:3px 9px;border-radius:999px;font-size:11.5px;font-weight:700}
.b-green{background:#dcfce7;color:#166534}.b-orange{background:#fef3c7;color:#92400e}
.b-gray{background:#f1f5f9;color:#475569}.b-red{background:#fee2e2;color:#991b1b}.b-blue{background:#dbeafe;color:#1e40af}
table{width:100%;border-collapse:collapse}
th{background:#f2f6f4;color:#4b5563;font-size:12px;text-transform:uppercase;letter-spacing:.4px;text-align:left;padding:11px 13px;border-bottom:1px solid ${LINE}}
td{padding:12px 13px;font-size:13px;border-bottom:1px solid #eef1ef;vertical-align:middle}
tr:last-child td{border-bottom:0}
.thumb{width:54px;height:40px;border-radius:7px;object-fit:cover}
.pagination{display:flex;gap:7px;justify-content:center;margin-top:22px}
.pagination span{width:35px;height:35px;border:1px solid ${LINE};border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:700;color:#4b5563}
.pagination span.on{background:${P};border-color:${P};color:#fff}
.steps{display:flex;align-items:center;justify-content:center;gap:0;padding:28px 0 6px}
.step{display:flex;align-items:center;gap:10px;color:${MUTED};font-size:13.5px;font-weight:700}
.step b{width:30px;height:30px;border-radius:50%;background:#e5e7eb;color:#6b7280;display:flex;align-items:center;justify-content:center;font-size:13px}
.step.on{color:${P}}.step.on b{background:${P};color:#fff}
.step-line{width:90px;height:2px;background:#e5e7eb;margin:0 14px}
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.inp{height:43px;border:1px solid #d7ddd9;border-radius:8px;display:flex;align-items:center;padding:0 13px;font-size:13.5px;color:#374151;background:#fff}
.inp.sel:after{content:"▾";margin-left:auto;color:${MUTED}}
.lbl{font-size:12.5px;font-weight:700;color:#374151;margin-bottom:6px;display:block}
.lbl em{color:#dc2626;font-style:normal}
.sumrow{display:flex;justify-content:space-between;font-size:13px;color:#4b5563;padding:8px 0}
.sumrow.total{border-top:1px solid ${LINE};margin-top:8px;padding-top:13px;font-size:16px;font-weight:800;color:#c2410c}
.ok{width:74px;height:74px;border-radius:50%;background:#dcfce7;color:#16a34a;display:flex;align-items:center;justify-content:center;font-size:38px;margin:0 auto 14px}
.center{text-align:center}
.split{display:grid;grid-template-columns:1.05fr .95fr;min-height:760px}
.split .art{position:relative;overflow:hidden}
.split .art img{width:100%;height:100%;object-fit:cover}
.split .art .ov{position:absolute;inset:0;background:linear-gradient(150deg,rgba(14,60,54,.72),rgba(14,60,54,.25));color:#fff;padding:70px 58px}
.split .art h2{font-size:36px;font-weight:800;line-height:1.2;max-width:420px}
.split .art p{margin-top:14px;color:#d7ece7;font-size:14px;max-width:410px;line-height:1.7}
.split .formside{display:flex;align-items:center;justify-content:center;background:#fbfcfb}
.authcard{width:430px}
.authcard h3{font-size:27px;font-weight:800;margin-bottom:6px}
.authcard .sub{color:${MUTED};font-size:13.5px;margin-bottom:24px}
.authcard .inp{margin-bottom:14px;height:46px}
.divider{display:flex;align-items:center;gap:12px;color:#9ca3af;font-size:12px;margin:18px 0}
.divider:before,.divider:after{content:"";height:1px;background:${LINE};flex:1}
.gbtn{height:46px;border:1px solid #d7ddd9;border-radius:9px;display:flex;align-items:center;justify-content:center;gap:10px;font-weight:700;font-size:14px;background:#fff}
.gicon{width:20px;height:20px;border-radius:50%;background:conic-gradient(#ea4335 0 25%,#fbbc05 0 50%,#34a853 0 75%,#4285f4 0);}
.mini{font-size:12.5px;color:${MUTED}}
.chatwrap{position:fixed;right:40px;bottom:0;width:410px;height:720px;background:#fff;border-radius:18px 18px 0 0;box-shadow:0 20px 60px rgba(10,40,36,.28);border:1px solid ${LINE};display:flex;flex-direction:column;overflow:hidden;z-index:20}
.chathead{background:${P};color:#fff;padding:15px 17px;display:flex;align-items:center;gap:11px}
.chathead .av{background:rgba(255,255,255,.2);color:#fff}
.chathead b{font-size:14px}.chathead span{display:block;font-size:11px;color:#cde5df}
.chatbody{flex:1;padding:16px;background:#f7faf9;display:flex;flex-direction:column;gap:12px;overflow:hidden}
.msg{max-width:84%;padding:11px 13px;border-radius:12px;font-size:13px;line-height:1.55}
.msg.bot{background:#fff;border:1px solid ${LINE};border-bottom-left-radius:4px}
.msg.user{background:${P};color:#fff;margin-left:auto;border-bottom-right-radius:4px}
.mini-tour{display:flex;gap:10px;background:#f0f7f5;border:1px solid #d8e9e4;border-radius:10px;padding:9px;margin-top:9px}
.mini-tour img{width:70px;height:54px;border-radius:7px;object-fit:cover}
.mini-tour b{font-size:12px;display:block;line-height:1.35}
.mini-tour span{font-size:11.5px;color:#c2410c;font-weight:800}
.chatinput{border-top:1px solid ${LINE};padding:11px;display:flex;gap:9px;align-items:center}
.chatinput .inp{flex:1;height:40px;font-size:13px;color:#9ca3af}
.roundbtn{width:40px;height:40px;border-radius:50%;background:${P};color:#fff;display:flex;align-items:center;justify-content:center;font-size:16px}
/* Admin */
.admin{display:grid;grid-template-columns:250px 1fr;min-height:1010px;background:${BG}}
.side{background:linear-gradient(180deg,#0d2f2a,#123f38);color:#c7ddd8;padding:20px 14px}
.side .brand{display:flex;align-items:center;gap:11px;padding:4px 8px 20px;border-bottom:1px solid rgba(255,255,255,.1);margin-bottom:16px}
.side .brand img{height:38px;filter:brightness(1.15)}
.side .brand b{color:#fff;font-size:15px}.side .brand span{font-size:10.5px;color:#8fb3ac;display:block}
.menu{list-style:none;font-size:13.5px}
.menu li{display:flex;align-items:center;gap:11px;padding:10px 12px;border-radius:9px;margin-bottom:3px;color:#b9d3cd}
.menu li.on{background:rgba(255,255,255,.12);color:#fff;font-weight:700}
.menu li .dot{width:22px;height:22px;border-radius:6px;background:rgba(255,255,255,.12);display:flex;align-items:center;justify-content:center;font-size:11px}
.menu .grp{font-size:10.5px;text-transform:uppercase;letter-spacing:.8px;color:#7fa39c;margin:16px 10px 7px}
.adminmain{display:flex;flex-direction:column}
.admintop{height:66px;background:#fff;border-bottom:1px solid ${LINE};display:flex;align-items:center;padding:0 26px;gap:18px}
.admintop h2{font-size:18px;font-weight:800}
.admintop .search{margin-left:auto;width:280px}
.admintop .inp{height:38px;font-size:13px;color:#9ca3af}
.adminbody{padding:24px 26px 30px}
.kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:18px;margin-bottom:20px}
.kpi{background:#fff;border:1px solid ${LINE};border-radius:13px;padding:18px}
.kpi span{font-size:12.5px;color:${MUTED};font-weight:600}
.kpi b{display:block;font-size:25px;font-weight:800;margin:6px 0 3px}
.kpi em{font-style:normal;font-size:11.5px;color:#16a34a;font-weight:700}
.kpi em.down{color:#dc2626}
.panel2{background:#fff;border:1px solid ${LINE};border-radius:13px;padding:18px}
.panel2 h4{font-size:14.5px;margin-bottom:14px;display:flex;justify-content:space-between;align-items:center}
.chart{height:190px;display:flex;align-items:flex-end;gap:16px;padding:10px 6px 0}
.bar{flex:1;background:linear-gradient(180deg,#3aa294,#21776d);border-radius:6px 6px 0 0;position:relative}
.bar span{position:absolute;top:-19px;left:0;right:0;text-align:center;font-size:10px;color:${MUTED}}
.bar x{position:absolute;bottom:-19px;left:0;right:0;text-align:center;font-size:10px;color:${MUTED}}
.grid2{display:grid;grid-template-columns:1.55fr 1fr;gap:18px}
.modal{position:fixed;inset:0;background:rgba(15,35,32,.5);display:flex;align-items:center;justify-content:center;z-index:30}
.modalbox{width:760px;background:#fff;border-radius:14px;overflow:hidden;box-shadow:0 30px 80px rgba(0,0,0,.35)}
.modalhead{padding:16px 20px;border-bottom:1px solid ${LINE};display:flex;justify-content:space-between;align-items:center;font-weight:800;font-size:16px}
.modalbody{padding:20px;display:grid;grid-template-columns:1fr 1fr;gap:15px}
.modalfoot{padding:14px 20px;border-top:1px solid ${LINE};display:flex;justify-content:flex-end;gap:10px;background:#fbfcfb}
.span2{grid-column:span 2}
.upload{border:1.5px dashed #bfd3cd;border-radius:10px;height:86px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:${MUTED};font-size:12px;background:#f8fbfa}
.chip{display:inline-flex;padding:3px 9px;border-radius:6px;font-size:11.5px;background:#eef4f2;color:#3f6059;margin:2px}
.timeline{position:relative;padding-left:26px}
.timeline:before{content:"";position:absolute;left:7px;top:6px;bottom:6px;width:2px;background:#d7e4e0}
.tl{position:relative;margin-bottom:18px}
.tl:before{content:"";position:absolute;left:-24px;top:3px;width:12px;height:12px;border-radius:50%;background:${P};border:3px solid #dff0ec}
.tl b{font-size:13.5px}.tl p{font-size:12.5px;color:${MUTED};margin-top:4px;line-height:1.55}
.note{background:#fff9ec;border:1px solid #fde3b0;border-radius:10px;padding:12px 14px;font-size:12.5px;color:#7c4a03;line-height:1.6}
.flow{display:flex;align-items:center;gap:12px;flex-wrap:nowrap}
.fnode{background:#fff;border:2px solid ${P};border-radius:11px;padding:12px 14px;font-size:12.5px;font-weight:700;text-align:center;min-width:132px}
.fnode.ext{border-color:${ACC};background:#fffbeb}
.farrow{color:${P};font-size:20px;font-weight:800}
.flowv{display:flex;flex-direction:column;gap:10px}
.flowrow{display:flex;align-items:center;gap:12px}
.tagline{font-size:12px;color:${MUTED}}
.progress{height:7px;background:#e5e7eb;border-radius:4px;overflow:hidden}
.progress i{display:block;height:100%;background:${P}}
`;

const head = (title) => `<!doctype html><html lang="vi"><head><meta charset="utf-8"><title>${title}</title><style>${css}</style></head><body>`;

const logo = '<img class="logo" src="assets/sgu-tour.svg" alt="SGU Tour">';

const topbar = `<div class="topbar"><div class="wrap"><span>✈ Hotline: <b>1900 636 648</b> · Hỗ trợ 08:00 – 21:00</span><span>Đồng hành cùng sinh viên khám phá Việt Nam</span></div></div>`;

const header = (active = '') => `${topbar}
<header><div class="wrap">${logo}
<nav>
  <a class="${active === 'home' ? 'on' : ''}">Trang chủ</a>
  <a class="${active === 'tours' ? 'on' : ''}">Tour du lịch</a>
  <a>Điểm đến</a>
  <a>Về SGU Tour</a>
  <a>Chính sách</a>
</nav>
<div class="nav-right">
  <div class="hotline">Tư vấn miễn phí<b>1900 636 648</b></div>
  <button class="btn ghost">Đăng nhập</button>
</div></div></header>`;

const footer = `<footer><div class="wrap">
<div class="cols">
  <div><img src="assets/sgu-tour.svg" style="height:46px;filter:brightness(1.2);margin-bottom:14px">
    <p>Nền tảng đặt tour du lịch dành cho sinh viên, vận hành bởi nhóm đồ án Chuyên đề J2EE — Trường Đại học Sài Gòn.</p></div>
  <div><h5>Về chúng tôi</h5><ul><li>Giới thiệu</li><li>Điều khoản sử dụng</li><li>Chính sách bảo mật</li><li>Tuyển dụng</li></ul></div>
  <div><h5>Hỗ trợ</h5><ul><li>Hướng dẫn đặt tour</li><li>Chính sách hoàn tiền</li><li>Câu hỏi thường gặp</li><li>Liên hệ</li></ul></div>
  <div><h5>Liên hệ</h5><p>273 An Dương Vương, Quận 5, TP.HCM</p><p>1900 636 648</p><p>hotro@sgutour.vn</p></div>
</div>
<div class="bottom"><div style="display:flex;justify-content:space-between"><span>© 2026 SGU Tour. Đồ án Chuyên đề J2EE.</span><span>VNPay · MoMo · Cloudinary · Groq</span></div></div>
</div></footer>`;

const tourCard = (img, title, days, price, rating, tag = '', tagClass = '') => `
<div class="tour"><div class="ph"><img src="assets/${img}">${tag ? `<span class="tag ${tagClass}">${tag}</span>` : ''}</div>
<div class="body"><h3>${title}</h3>
<div class="meta"><span>🕘 ${days}</span><span>·</span><span>Khởi hành: Hà Nội</span></div>
<div class="meta"><span class="stars">★★★★★</span><span>${rating} (128 đánh giá)</span></div>
<div class="price-row"><div class="price">${money(price)}<small>/ khách</small></div><button class="btn sm">Đặt ngay</button></div>
</div></div>`;

const adminShell = (active, title, body) => {
  const item = (label, icon, key) => `<li class="${active === key ? 'on' : ''}"><span class="dot">${icon}</span>${label}</li>`;
  return `<div class="admin">
  <aside class="side">
    <div class="brand"><img src="assets/sgu-tour.svg"><div><span>Bảng điều khiển quản trị</span></div></div>
    <ul class="menu">
      ${item('Tổng quan', '▦', 'dash')}
      <div class="grp">Kinh doanh</div>
      ${item('Quản lý booking', '▤', 'booking')}
      ${item('Hóa đơn', '▥', 'invoice')}
      ${item('Đối soát thanh toán', '⇄', 'recon')}
      <div class="grp">Danh mục</div>
      ${item('Quản lý tour', '✈', 'tour')}
      ${item('Lịch khởi hành', '▦', 'departure')}
      ${item('Lịch trình', '☰', 'itinerary')}
      ${item('Địa điểm', '◎', 'location')}
      ${item('Combo giá', '₫', 'price')}
      ${item('Đánh giá', '★', 'review')}
      <div class="grp">Hệ thống</div>
      ${item('Người dùng', '◍', 'user')}
      ${item('Vai trò & quyền', '⚿', 'role')}
      ${item('Báo cáo vận hành', '▧', 'report')}
    </ul>
  </aside>
  <div class="adminmain">
    <div class="admintop"><h2>${title}</h2>
      <div class="search"><div class="inp">🔍 Tìm kiếm nhanh…</div></div>
      <div style="display:flex;align-items:center;gap:10px"><div class="av">AD</div><div><b style="font-size:13px">Quản trị viên</b><div class="mini">admin@example.com</div></div></div>
    </div>
    <div class="adminbody">${body}</div>
  </div>
</div>`;
};

const pages = {};

pages['01-trang-chu'] = `${head('Trang chủ')}${header('home')}
<div class="hero"><img class="bg" src="assets/hero-halong-1600.webp"><div class="mask"></div>
  <div class="inner"><h1>Khám phá Việt Nam theo cách của bạn</h1>
  <p>Hàng trăm tour trong nước, giá minh bạch, giữ chỗ tức thì và thanh toán trực tuyến an toàn.</p></div>
  <div class="searchcard">
    <div class="field"><label>Điểm đến</label><div class="box">◎ Bạn muốn đi đâu?</div></div>
    <div class="field"><label>Ngày khởi hành</label><div class="box">▦ 20/12/2026</div></div>
    <div class="field"><label>Số khách</label><div class="box">◍ 2 người lớn</div></div>
    <button class="btn">Tìm kiếm tour</button>
  </div>
</div>
<div class="wrap">
  <section class="block" style="padding-top:74px">
    <div class="sec-head"><div><h2>Tour nổi bật</h2><p>Được khách hàng lựa chọn nhiều nhất trong tháng này</p></div><a>Xem tất cả →</a></div>
    <div class="cards">
      ${tourCard('sl3.jpg', 'Tour Sapa 3 ngày 2 đêm — săn mây Fansipan', '3 ngày 2 đêm', 3550000, '4.8', 'Bán chạy', '')}
      ${tourCard('hero-halong-1600.webp', 'Tour Hạ Long 2 ngày 1 đêm — du thuyền 4 sao', '2 ngày 1 đêm', 2890000, '4.7')}
      ${tourCard('sl3.jpg', 'Tour Đà Nẵng – Hội An 4 ngày 3 đêm', '4 ngày 3 đêm', 4990000, '4.9', 'Ưu đãi', 'blue')}
      ${tourCard('sl4.jpg', 'Tour Phú Quốc 3 ngày 2 đêm — resort 5 sao', '3 ngày 2 đêm', 6290000, '4.8')}
    </div>
  </section>
  <section class="block">
    <div class="sec-head"><div><h2>Vì sao chọn SGU Tour?</h2></div></div>
    <div class="why">
      <div class="it"><div class="ic">₫</div><div><h4>Giá minh bạch</h4><p>Giá theo nhóm tuổi được công khai, chốt ngay khi đặt tour.</p></div></div>
      <div class="it"><div class="ic">◷</div><div><h4>Giữ chỗ tức thì</h4><p>Hệ thống giữ chỗ có thời hạn, tự động trả chỗ khi hết hạn.</p></div></div>
      <div class="it"><div class="ic">▣</div><div><h4>Thanh toán an toàn</h4><p>VNPay và MoMo sandbox, xác minh chữ ký và số tiền.</p></div></div>
      <div class="it"><div class="ic">✦</div><div><h4>Trợ lý AI</h4><p>Tư vấn tour theo ngân sách, tra cứu booking của chính bạn.</p></div></div>
    </div>
  </section>
  <section class="block">
    <div class="sec-head"><div><h2>Điểm đến yêu thích</h2></div><a>Khám phá →</a></div>
    <div class="dest">${['sl3.jpg|Sapa', 'hero-halong-1600.webp|Hạ Long', 'sl3.jpg|Đà Nẵng', 'sl4.jpg|Phú Quốc'].map(x => { const [i, n] = x.split('|'); return `<div class="d"><img src="assets/${i}"><span>${n}</span></div>`; }).join('')}</div>
  </section>
  <section class="block" style="padding-bottom:20px">
    <div class="sec-head"><div><h2>Khách hàng nói gì</h2></div></div>
    <div class="reviews">
      <div class="rev"><div class="stars">★★★★★</div><p>“Tour Sapa tổ chức rất chu đáo, hướng dẫn viên nhiệt tình. Đặt tour online nhanh, thanh toán VNPay tiện lợi.”</p><div class="who"><div class="av">NA</div><div><b>Nguyễn An</b><span>Đã đi tour Sapa</span></div></div></div>
      <div class="rev"><div class="stars">★★★★★</div><p>“Giá hiển thị rõ theo người lớn và trẻ em, không phát sinh. Email xác nhận đến rất nhanh.”</p><div class="who"><div class="av">TH</div><div><b>Trần Hà</b><span>Đã đi tour Hạ Long</span></div></div></div>
      <div class="rev"><div class="stars">★★★★☆</div><p>“Trợ lý AI gợi ý tour đúng ngân sách, tra cứu được hạn thanh toán của booking.”</p><div class="who"><div class="av">ML</div><div><b>Minh Long</b><span>Đã đi tour Đà Nẵng</span></div></div></div>
    </div>
  </section>
</div>${footer}</body></html>`;

pages['02-danh-sach-tour'] = `${head('Danh sách tour')}${header('tours')}
<div class="pagehead"><div class="wrap"><div class="crumb">Trang chủ / Tour du lịch</div><h1>Tour du lịch</h1><p>128 tour đang mở bán · Cập nhật giá và chỗ trống theo thời gian thực</p></div></div>
<div class="wrap layout2">
  <aside>
    <div class="panel"><div class="ph2">Bộ lọc</div><div class="pb">
      <div class="fgroup"><h6>Điểm đến</h6>
        ${['Sapa', 'Hạ Long', 'Đà Nẵng', 'Phú Quốc', 'Nha Trang'].map((x, i) => `<div class="chk"><i class="${i < 2 ? 'on' : ''}"></i>${x}</div>`).join('')}
      </div>
      <div class="fgroup"><h6>Khoảng giá</h6><div class="range"><i class="a"></i><i class="b"></i></div>
        <div style="display:flex;justify-content:space-between;font-size:12px;color:${MUTED}"><span>0 ₫</span><span>10.000.000 ₫</span></div></div>
      <div class="fgroup"><h6>Ngày khởi hành</h6><div class="inp sel">20/12/2026</div></div>
      <div class="fgroup"><h6>Phương tiện</h6>
        ${['Máy bay', 'Xe giường nằm', 'Du thuyền'].map((x) => `<div class="chk"><i></i>${x}</div>`).join('')}</div>
      <button class="btn" style="width:100%">Áp dụng bộ lọc</button>
    </div></div>
  </aside>
  <main>
    <div class="toolbar"><div class="mini">Hiển thị <b>1–6</b> trong <b>128</b> tour</div>
      <div class="inp" style="width:230px">Sắp xếp: Phổ biến nhất ▾</div></div>
    <div class="grid3">
      ${tourCard('sl3.jpg', 'Tour Sapa 3 ngày 2 đêm — săn mây Fansipan', '3 ngày 2 đêm', 3550000, '4.8', 'Bán chạy')}
      ${tourCard('hero-halong-1600.webp', 'Tour Hạ Long 2 ngày 1 đêm — du thuyền 4 sao', '2 ngày 1 đêm', 2890000, '4.7')}
      ${tourCard('sl3.jpg', 'Tour Đà Nẵng – Hội An 4 ngày 3 đêm', '4 ngày 3 đêm', 4990000, '4.9', 'Ưu đãi', 'blue')}
      ${tourCard('sl4.jpg', 'Tour Phú Quốc 3 ngày 2 đêm — resort 5 sao', '3 ngày 2 đêm', 6290000, '4.8')}
      ${tourCard('hero-halong-1600.webp', 'Tour Ninh Bình 2 ngày 1 đêm — Tràng An', '2 ngày 1 đêm', 1990000, '4.6')}
      ${tourCard('sl3.jpg', 'Tour Mộc Châu 2 ngày 1 đêm — mùa hoa', '2 ngày 1 đêm', 1750000, '4.5')}
    </div>
    <div class="pagination"><span>‹</span><span class="on">1</span><span>2</span><span>3</span><span>…</span><span>22</span><span>›</span></div>
  </main>
</div>${footer}</body></html>`;

pages['03-chi-tiet-tour'] = `${head('Chi tiết tour')}${header('tours')}
<div class="wrap" style="padding:22px 0 0"><div class="crumb" style="color:${MUTED}">Trang chủ / Tour du lịch / Tour Sapa 3 ngày 2 đêm</div>
<div style="display:grid;grid-template-columns:1.9fr 1fr;gap:18px;margin-top:10px">
  <div style="display:grid;grid-template-columns:2fr 1fr;grid-template-rows:130px 130px;gap:9px">
    <img src="assets/sl3.jpg" style="grid-row:span 2;width:100%;height:269px;object-fit:cover;border-radius:12px">
    <img src="assets/hero-halong-1600.webp" style="width:100%;height:130px;object-fit:cover;border-radius:12px">
    <div style="position:relative"><img src="assets/sl4.jpg" style="width:100%;height:130px;object-fit:cover;border-radius:12px"><div style="position:absolute;inset:0;background:rgba(0,0,0,.5);border-radius:12px;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:800">+12 ảnh</div></div>
  </div>
  <div class="panel" style="padding:18px"><div style="display:flex;gap:8px"><span class="badge b-orange">Bán chạy</span><span class="badge b-blue">Xác nhận tức thì</span></div>
  <h2 style="font-size:20px;line-height:1.35;margin:10px 0 6px">Tour Sapa 3 ngày 2 đêm — săn mây Fansipan</h2>
  <div class="meta"><span class="stars">★★★★★</span><span>4.8 · 128 đánh giá</span></div>
  <div style="margin-top:12px;font-size:13px;color:#4b5563;display:grid;gap:7px">
    <div>🕘 3 ngày 2 đêm</div><div>◎ Khởi hành: Hà Nội</div><div>▣ Phương tiện: Xe giớiường nằm</div></div>
  <div style="border-top:1px dashed ${LINE};margin-top:13px;padding-top:12px"><span class="mini">Giá từ</span><div class="price" style="font-size:24px">3.550.000 ₫ <small>/ khách</small></div></div>
  <button class="btn" style="width:100%;margin-top:12px">Đặt tour ngay</button>
  <button class="btn ghost" style="width:100%;margin-top:8px">Gọi tư vấn</button>
  <div class="note" style="margin-top:12px">Miễn phí hủy trước 72 giờ · Giữ chỗ có thời hạn</div></div>
</div></div>
<div class="wrap" style="padding:24px 0 0">
<div style="display:grid;grid-template-columns:1.9fr 1fr;gap:26px">
 <div>
  <div class="tabs"><a class="on">Tổng quan</a><a>Lịch trình</a><a>Đánh giá (128)</a><a>Chính sách</a></div>
  <div class="panel" style="border-top-left-radius:0;padding:20px">
    <h3 style="font-size:17px;margin-bottom:10px">Giới thiệu chuyến đi</h3>
    <p style="font-size:13.5px;line-height:1.75;color:#4b5563">Khởi hành từ Hà Nội, hành trình đưa bạn qua những cung đường đèo hùng vĩ của Tây Bắc, chinh phục đỉnh Fansipan bằng cáp treo và nghỉ đêm tại khách sạn trung tâm thị trấn Sapa. Đoàn được hướng dẫn viên đồng hành suốt tuyến.</p>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:14px 0">
      ${['Xe giường nằm khứ hồi', 'Khách sạn 3 sao', 'Vé cáp treo Fansipan', 'Hướng dẫn viên suốt tuyến'].map(x => `<div class="chip" style="padding:8px 11px;font-size:12.5px">✓ ${x}</div>`).join('')}
    </div>
    <h3 style="font-size:17px;margin:16px 0 12px">Lịch trình</h3>
    <div class="timeline">
      <div class="tl"><b>Ngày 1 · Hà Nội – Sapa</b><p>06:00 khởi hành, nghỉ chân dọc đường, đến Sapa buổi chiều. Tham quan Nhà thờ Đá, chợ đêm Sapa.</p></div>
      <div class="tl"><b>Ngày 2 · Chinh phục Fansipan</b><p>Đi cáp treo lên đỉnh Fansipan, ngắm biển mây. Chiều về thị trấn, tự do khám phá.</p></div>
      <div class="tl"><b>Ngày 3 · Sapa – Hà Nội</b><p>Tham quan bản Cát Cát, mua đặc sản, khởi hành về Hà Nội. Kết thúc chương trình.</p></div>
    </div>
    <h3 style="font-size:17px;margin:16px 0 12px">Đánh giá của khách hàng</h3>
    <div class="rev" style="margin-bottom:10px"><div class="stars">★★★★★</div><p>“Chuyến đi tuyệt vời, hướng dẫn viên vui tính, khách sạn sạch sẽ.”</p><div class="who"><div class="av">NA</div><div><b>Nguyễn An</b><span>20/11/2026</span></div></div></div>
  </div>
 </div>
 <aside>
   <div class="panel"><div class="ph2">Chọn lịch khởi hành</div><div class="pb">
     <div class="lbl">Ngày khởi hành</div>
     <div class="inp sel" style="margin-bottom:10px">20/12/2026 — còn 30 chỗ</div>
     <div class="inp sel" style="margin-bottom:10px">24/12/2026 — còn 12 chỗ</div>
     <div class="inp sel" style="margin-bottom:12px">27/12/2026 — còn 28 chỗ</div>
     <table style="font-size:13px"><tr><td style="border:0;padding:5px 0">Người lớn</td><td style="border:0;text-align:right;padding:5px 0">3.550.000 ₫</td></tr>
     <tr><td style="border:0;padding:5px 0">Trẻ em</td><td style="border:0;text-align:right;padding:5px 0">1.775.000 ₫</td></tr>
     <tr><td style="border:0;padding:5px 0">Trẻ nhỏ</td><td style="border:0;text-align:right;padding:5px 0">0 ₫</td></tr></table>
     <button class="btn" style="width:100%;margin-top:10px">Đặt tour</button>
   </div></div>
   <div class="panel" style="margin-top:16px"><div class="ph2">Cần tư vấn?</div><div class="pb" style="font-size:13px;color:#4b5563;line-height:1.7">Gọi <b style="color:${P}">1900 636 648</b> hoặc trò chuyện với trợ lý AI ở góc màn hình.</div></div>
 </aside>
</div></div>${footer}</body></html>`;

const steps = (n) => `<div class="steps">
${['Thông tin', 'Thanh toán', 'Hoàn tất'].map((s, i) => `<div class="step ${i <= n ? 'on' : ''}"><b>${i + 1}</b>${s}</div>${i < 2 ? '<div class="step-line"></div>' : ''}`).join('')}</div>`;

pages['04-dat-tour'] = `${head('Đặt tour')}${header('tours')}
<div class="wrap">${steps(0)}
<div style="display:grid;grid-template-columns:1.55fr 1fr;gap:24px;padding:14px 0 0">
 <div>
  <div class="panel"><div class="ph2">Thông tin liên hệ</div><div class="pb">
    <div class="form-grid">
      <div><span class="lbl">Họ và tên <em>*</em></span><div class="inp">Nguyễn Văn An</div></div>
      <div><span class="lbl">Số điện thoại <em>*</em></span><div class="inp">0901 234 567</div></div>
      <div><span class="lbl">Email <em>*</em></span><div class="inp">an.nguyen@example.com</div></div>
      <div><span class="lbl">Địa chỉ</span><div class="inp">Quận 5, TP. Hồ Chí Minh</div></div>
    </div>
  </div></div>
  <div class="panel" style="margin-top:16px"><div class="ph2">Danh sách hành khách <button class="btn sm plain">+ Thêm hành khách</button></div><div class="pb" style="padding:0">
    <table><thead><tr><th style="width:44px">STT</th><th>Họ và tên</th><th>Ngày sinh</th><th>Loại khách</th><th>Giá</th><th></th></tr></thead>
    <tbody>
      <tr><td>1</td><td>Nguyễn Văn An</td><td>12/03/1998</td><td><span class="badge b-blue">Người lớn</span></td><td>3.550.000 ₫</td><td style="color:#dc2626">Xóa</td></tr>
      <tr><td>2</td><td>Nguyễn Thị Bình</td><td>05/07/1999</td><td><span class="badge b-blue">Người lớn</span></td><td>3.550.000 ₫</td><td style="color:#dc2626">Xóa</td></tr>
      <tr><td>3</td><td>Nguyễn Gia Hân</td><td>20/09/2015</td><td><span class="badge b-orange">Trẻ em</span></td><td>1.775.000 ₫</td><td style="color:#dc2626">Xóa</td></tr>
    </tbody></table>
  </div></div>
  <div class="panel" style="margin-top:16px"><div class="ph2">Ghi chú</div><div class="pb"><div class="inp" style="height:78px;align-items:flex-start;padding-top:11px;color:#9ca3af">Yêu cầu đặc biệt (ăn chay, phòng đơn…)</div></div></div>
 </div>
 <aside>
  <div class="panel" style="position:sticky;top:16px"><div class="ph2">Tóm tắt đơn hàng</div><div class="pb">
    <div style="display:flex;gap:10px;margin-bottom:12px"><img class="thumb" style="width:64px;height:50px" src="assets/sl3.jpg"><div><b style="font-size:13.5px">Tour Sapa 3 ngày 2 đêm</b><div class="mini">20/12/2026 · 3 khách</div></div></div>
    <div class="sumrow"><span>Người lớn × 2</span><b>7.100.000 ₫</b></div>
    <div class="sumrow"><span>Trẻ em × 1</span><b>1.775.000 ₫</b></div>
    <div class="sumrow"><span>Phụ thu phòng đơn</span><b>0 ₫</b></div>
    <div class="sumrow total"><span>Tổng cộng</span><span>8.875.000 ₫</span></div>
    <button class="btn" style="width:100%;margin-top:12px">Tiến hành thanh toán</button>
    <div class="note" style="margin-top:12px">Chỗ của bạn được giữ trong <b>15:00</b> phút kể từ khi tạo booking.</div>
  </div></div>
 </aside>
</div></div>${footer}</body></html>`;

pages['05-thanh-toan'] = `${head('Thanh toán')}${header('tours')}
<div class="wrap">${steps(1)}
<div style="display:grid;grid-template-columns:1.35fr 1fr;gap:24px;padding:14px 0 0">
 <div>
  <div class="panel"><div class="ph2">Chọn phương thức thanh toán</div><div class="pb">
    <div style="border:2px solid ${P};border-radius:11px;padding:14px;display:flex;align-items:center;gap:14px;margin-bottom:12px;background:#f4faf8">
      <div style="width:18px;height:18px;border-radius:50%;border:5px solid ${P}"></div>
      <div style="background:#005baa;color:#fff;font-weight:800;border-radius:6px;padding:7px 12px;font-size:14px">VNPAY</div>
      <div><b style="font-size:14px">Cổng thanh toán VNPay</b><div class="mini">Thẻ ATM nội địa, thẻ quốc tế, QR Pay</div></div>
      <div class="badge b-green" style="margin-left:auto">Khuyên dùng</div>
    </div>
    <div style="border:1px solid ${LINE};border-radius:11px;padding:14px;display:flex;align-items:center;gap:14px">
      <div style="width:18px;height:18px;border-radius:50%;border:1.5px solid #b9c4bf"></div>
      <img src="assets/momo.png" style="height:30px">
      <div><b style="font-size:14px">Ví điện tử MoMo</b><div class="mini">Thanh toán qua ứng dụng MoMo</div></div>
    </div>
    <div class="note" style="margin-top:16px">Đây là môi trường <b>sandbox</b> phục vụ đồ án. Không phát sinh giao dịch tiền thật.</div>
  </div></div>
  <div class="panel" style="margin-top:16px"><div class="ph2">Thông tin đơn hàng</div><div class="pb">
    <table style="font-size:13px"><tr><td style="border:0;color:${MUTED}">Mã booking</td><td style="border:0;text-align:right"><b>SGUTOUR-2026-000123</b></td></tr>
    <tr><td style="border:0;color:${MUTED}">Tour</td><td style="border:0;text-align:right">Tour Sapa 3 ngày 2 đêm</td></tr>
    <tr><td style="border:0;color:${MUTED}">Khởi hành</td><td style="border:0;text-align:right">20/12/2026</td></tr>
    <tr><td style="border:0;color:${MUTED}">Số khách</td><td style="border:0;text-align:right">3 khách</td></tr></table>
  </div></div>
 </div>
 <aside>
  <div class="panel"><div class="ph2">Thanh toán</div><div class="pb">
    <div class="sumrow"><span>Tạm tính</span><b>8.875.000 ₫</b></div>
    <div class="sumrow"><span>Giảm giá</span><b>0 ₫</b></div>
    <div class="sumrow total"><span>Tổng thanh toán</span><span>8.875.000 ₫</span></div>
    <div style="margin-top:14px;padding:12px;border-radius:10px;background:#fff7e6;border:1px solid #fde3b0">
      <div style="font-size:12px;color:#7c4a03;font-weight:700">Thời gian giữ chỗ còn lại</div>
      <div style="font-size:28px;font-weight:800;color:#c2410c;letter-spacing:1px">13:45</div>
    </div>
    <button class="btn" style="width:100%;margin-top:14px">Thanh toán ngay</button>
    <div class="mini" style="text-align:center;margin-top:10px">Bằng việc thanh toán, bạn đồng ý với điều khoản của SGU Tour.</div>
  </div></div>
 </aside>
</div></div>${footer}</body></html>`;

pages['06-vnpay'] = `${head('VNPAY')}
<div style="background:#eef2f7;min-height:900px;padding:34px 0">
<div style="width:1000px;margin:0 auto;background:#fff;border-radius:12px;box-shadow:0 10px 40px rgba(20,40,70,.12);overflow:hidden">
  <div style="background:linear-gradient(90deg,#005baa,#0079c8);padding:18px 26px;display:flex;align-items:center;gap:16px">
    <div style="background:#fff;color:#005baa;font-weight:900;font-size:22px;padding:6px 12px;border-radius:6px;letter-spacing:.5px">VNPAY</div>
    <div style="color:#dbeafe;font-size:13px">Cổng thanh toán trực tuyến</div>
    <div style="margin-left:auto;color:#fff;font-size:12.5px">🔒 Kết nối bảo mật SSL</div>
  </div>
  <div style="display:grid;grid-template-columns:1fr 1.1fr;gap:0">
    <div style="padding:28px 30px;border-right:1px solid #e2e8f0">
      <div style="font-size:12px;color:#64748b;text-transform:uppercase;letter-spacing:.6px;font-weight:700">Thông tin đơn hàng</div>
      <h2 style="font-size:21px;margin:10px 0 16px">Thanh toán đơn hàng</h2>
      <table style="font-size:13.5px">
        <tr><td style="border:0;color:#64748b;padding:7px 0">Đơn vị bán</td><td style="border:0;text-align:right;font-weight:700">SGU Tour</td></tr>
        <tr><td style="border:0;color:#64748b;padding:7px 0">Mã đơn hàng</td><td style="border:0;text-align:right">SGUTOUR-2026-000123</td></tr>
        <tr><td style="border:0;color:#64748b;padding:7px 0">Nội dung</td><td style="border:0;text-align:right">Thanh toan tour Sapa 3N2D</td></tr>
        <tr><td style="border:0;color:#64748b;padding:7px 0">Số tiền</td><td style="border:0;text-align:right;font-size:20px;font-weight:800;color:#005baa">8.875.000 VND</td></tr>
      </table>
      <div style="margin-top:18px;background:#f8fafc;border:1px dashed #cbd5e1;border-radius:8px;padding:12px;font-size:12px;color:#64748b;line-height:1.7">
        Vui lòng kiểm tra kỹ thông tin trước khi thanh toán. Giao dịch được xác thực bằng chữ ký HMAC-SHA512.
      </div>
    </div>
    <div style="padding:28px 30px">
      <div style="display:flex;gap:6px;margin-bottom:16px">
        <div style="background:#005baa;color:#fff;font-size:11.5px;font-weight:700;padding:6px 12px;border-radius:6px">Thẻ ATM / Tài khoản ngân hàng</div>
        <div style="color:#64748b;font-size:11.5px;padding:6px 12px">QR Pay</div>
      </div>
      <div class="lbl">Chọn ngân hàng</div><div class="inp sel" style="margin-bottom:12px">Ngân hàng TMCP Ngoại thương Việt Nam (Vietcombank)</div>
      <div class="lbl">Số thẻ</div><div class="inp" style="margin-bottom:12px">9704 1999 1234 5678</div>
      <div class="form-grid"><div><div class="lbl">Tên chủ thẻ</div><div class="inp">NGUYEN VAN AN</div></div><div><div class="lbl">Ngày phát hành</div><div class="inp">12/2024</div></div></div>
      <div class="lbl" style="margin-top:14px">Mã OTP <span class="mini">(gửi tới số điện thoại đã đăng ký)</span></div>
      <div style="display:flex;gap:10px"><div class="inp" style="width:150px">••••••</div><button class="btn sm plain">Gửi OTP</button></div>
      <button class="btn" style="width:100%;margin-top:18px;background:#005baa">Thanh toán 8.875.000 VND</button>
      <div class="mini" style="text-align:center;margin-top:12px">Gặp sự cố? Gọi 1900 558 897 (VNPAY)</div>
    </div>
  </div>
  <div style="background:#f8fafc;border-top:1px solid #e2e8f0;padding:12px 26px;font-size:11.5px;color:#94a3b8;display:flex;justify-content:space-between">
    <span>© 2007–2026 VNPAY — Công ty Cổ phần Giải pháp Thanh toán Việt Nam</span><span>Trang mô phỏng sandbox cho đồ án</span>
  </div>
</div></div></body></html>`;

pages['07-hoa-don'] = `${head('Hóa đơn')}${header('tours')}
<div class="wrap">${steps(2)}
<div style="width:820px;margin:12px auto 0;background:#fff;border:1px solid ${LINE};border-radius:14px;padding:28px 34px">
  <div class="center"><div class="ok">✓</div><h2 style="font-size:23px">Thanh toán thành công</h2>
  <p class="mini" style="margin-top:6px">Cảm ơn bạn! Hóa đơn đã được phát hành và gửi tới email an.nguyen@example.com</p></div>
  <div style="display:flex;justify-content:space-between;margin:24px 0 14px;font-size:13px">
    <div><div class="mini">Mã hóa đơn</div><b>HD-2026-000123</b></div>
    <div><div class="mini">Mã booking</div><b>SGUTOUR-2026-000123</b></div>
    <div><div class="mini">Ngày thanh toán</div><b>18/11/2026 10:05</b></div>
    <div><div class="mini">Phương thức</div><b>VNPay</b></div>
  </div>
  <table><thead><tr><th>STT</th><th>Nội dung</th><th style="text-align:center">SL</th><th style="text-align:right">Đơn giá</th><th style="text-align:right">Thành tiền</th></tr></thead>
  <tbody>
    <tr><td>1</td><td>Tour Sapa 3 ngày 2 đêm — Người lớn</td><td style="text-align:center">2</td><td style="text-align:right">3.550.000 ₫</td><td style="text-align:right">7.100.000 ₫</td></tr>
    <tr><td>2</td><td>Tour Sapa 3 ngày 2 đêm — Trẻ em</td><td style="text-align:center">1</td><td style="text-align:right">1.775.000 ₫</td><td style="text-align:right">1.775.000 ₫</td></tr>
    <tr><td colspan="4" style="text-align:right;font-weight:700">Tổng cộng</td><td style="text-align:right;font-weight:800;color:#c2410c;font-size:16px">8.875.000 ₫</td></tr>
  </tbody></table>
  <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:18px"><button class="btn plain">Tải hóa đơn PDF</button><button class="btn">Về trang chủ</button></div>
</div></div>${footer}</body></html>`;

pages['08-lich-su-dat-tour'] = `${head('Lịch sử đặt tour')}${header('tours')}
<div class="pagehead"><div class="wrap"><div class="crumb">Trang chủ / Tài khoản</div><h1>Lịch sử đặt tour</h1><p>Theo dõi trạng thái thanh toán và hóa đơn của bạn</p></div></div>
<div class="wrap" style="padding:26px 0">
  <div class="panel"><div class="pb" style="padding-bottom:0">
    <div class="tabs"><a class="on">Tất cả (5)</a><a>Chờ thanh toán (1)</a><a>Đã thanh toán (3)</a><a>Đã hủy (1)</a></div>
  </div>
  <table style="margin-top:6px"><thead><tr><th>Mã booking</th><th>Tour</th><th>Ngày khởi hành</th><th style="text-align:center">Số khách</th><th style="text-align:right">Tổng tiền</th><th>Trạng thái</th><th></th></tr></thead>
  <tbody>
    <tr><td><b>SGUTOUR-000123</b></td><td><div style="display:flex;gap:10px;align-items:center"><img class="thumb" src="assets/sl3.jpg"><span>Tour Sapa 3 ngày 2 đêm</span></div></td><td>20/12/2026</td><td style="text-align:center">3</td><td style="text-align:right">8.875.000 ₫</td><td><span class="badge b-orange">Chờ thanh toán</span></td><td><button class="btn sm">Thanh toán</button></td></tr>
    <tr><td><b>SGUTOUR-000118</b></td><td><div style="display:flex;gap:10px;align-items:center"><img class="thumb" src="assets/hero-halong-1600.webp"><span>Tour Hạ Long 2 ngày 1 đêm</span></div></td><td>28/11/2026</td><td style="text-align:center">2</td><td style="text-align:right">5.780.000 ₫</td><td><span class="badge b-green">Đã thanh toán</span></td><td><button class="btn sm plain">Xem hóa đơn</button></td></tr>
    <tr><td><b>SGUTOUR-000104</b></td><td><div style="display:flex;gap:10px;align-items:center"><img class="thumb" src="assets/sl3.jpg"><span>Tour Đà Nẵng – Hội An 4N3Đ</span></div></td><td>02/10/2026</td><td style="text-align:center">4</td><td style="text-align:right">19.960.000 ₫</td><td><span class="badge b-green">Đã thanh toán</span></td><td><button class="btn sm plain">Đánh giá</button></td></tr>
    <tr><td><b>SGUTOUR-000091</b></td><td><div style="display:flex;gap:10px;align-items:center"><img class="thumb" src="assets/sl4.jpg"><span>Tour Phú Quốc 3 ngày 2 đêm</span></div></td><td>15/08/2026</td><td style="text-align:center">2</td><td style="text-align:right">12.580.000 ₫</td><td><span class="badge b-green">Đã thanh toán</span></td><td><button class="btn sm plain">Xem hóa đơn</button></td></tr>
    <tr><td><b>SGUTOUR-000077</b></td><td><div style="display:flex;gap:10px;align-items:center"><img class="thumb" src="assets/hero-halong-1600.webp"><span>Tour Ninh Bình 2 ngày 1 đêm</span></div></td><td>10/07/2026</td><td style="text-align:center">2</td><td style="text-align:right">3.980.000 ₫</td><td><span class="badge b-gray">Đã hủy</span></td><td><button class="btn sm plain">Chi tiết</button></td></tr>
  </tbody></table>
  </div>
</div>${footer}</body></html>`;

pages['09-danh-gia'] = `${head('Đánh giá')}${header('tours')}
<div class="pagehead"><div class="wrap"><div class="crumb">Trang chủ / Lịch sử đặt tour / Đánh giá</div><h1>Đánh giá chuyến đi</h1><p>Chia sẻ cảm nhận của bạn về tour Sapa 3 ngày 2 đêm</p></div></div>
<div class="wrap" style="padding:26px 0;display:grid;grid-template-columns:1fr 1fr;gap:24px">
  <div class="panel"><div class="ph2">Viết đánh giá</div><div class="pb">
    <div style="display:flex;gap:8px;margin-bottom:14px"><img class="thumb" style="width:70px;height:52px" src="assets/sl3.jpg"><div><b style="font-size:13.5px">Tour Sapa 3 ngày 2 đêm</b><div class="mini">Khởi hành 20/12/2026</div></div></div>
    <div class="lbl">Chất lượng chuyến đi</div>
    <div style="font-size:34px;color:${ACC};letter-spacing:4px;margin-bottom:14px">★★★★★</div>
    <div class="lbl">Cảm nhận của bạn</div>
    <div class="inp" style="height:120px;align-items:flex-start;padding-top:12px;color:#6b7280">Chuyến đi rất tuyệt vời, hướng dẫn viên thân thiện và chu đáo…</div>
    <div class="lbl" style="margin-top:14px">Thêm ảnh</div>
    <div class="upload" style="height:74px"><div style="font-size:22px">＋</div><span>Tải lên tối đa 5 ảnh (JPEG/PNG/WebP)</span></div>
    <button class="btn" style="width:100%;margin-top:16px">Gửi đánh giá</button>
  </div></div>
  <div class="panel"><div class="ph2">Đánh giá đã duyệt (128)</div><div class="pb">
    <div class="rev" style="margin-bottom:12px;padding:0;border:0"><div class="stars">★★★★★</div><p>“Chuyến đi tuyệt vời, hướng dẫn viên vui tính, khách sạn sạch sẽ. Sẽ tiếp tục ủng hộ SGU Tour.”</p><div class="who"><div class="av">NA</div><div><b>Nguyễn An</b><span>20/11/2026 · Đã xác thực</span></div></div></div>
    <div class="rev" style="margin-bottom:12px;padding:0;border:0;border-top:1px solid ${LINE};padding-top:12px"><div class="stars">★★★★★</div><p>“Lịch trình hợp lý, xe giường nằm thoải mái, đồ ăn ngon.”</p><div class="who"><div class="av">TH</div><div><b>Trần Hà</b><span>18/11/2026 · Đã xác thực</span></div></div></div>
    <div class="rev" style="padding:0;border:0;border-top:1px solid ${LINE};padding-top:12px"><div class="stars">★★★★☆</div><p>“Cáp treo hơi đông nhưng cảnh đẹp, đáng tiền.”</p><div class="who"><div class="av">ML</div><div><b>Minh Long</b><span>15/11/2026 · Đã xác thực</span></div></div></div>
  </div></div>
</div>${footer}</body></html>`;

const authArt = (title, sub) => `<div class="art"><img src="assets/sl3.jpg"><div class="ov"><div style="display:flex;align-items:center;gap:12px;margin-bottom:44px"><img src="assets/sgu-tour.svg" style="height:52px;filter:brightness(1.2)"><b style="font-size:19px;color:#fff">SGU Tour</b></div><h2>${title}</h2><p>${sub}</p>
<div style="margin-top:34px;display:grid;gap:12px;font-size:13.5px;color:#e3f2ee">${['✓ Hàng trăm tour trong nước, giá minh bạch', '✓ Giữ chỗ tức thì, thanh toán an toàn', '✓ Trợ lý AI tư vấn 24/7'].map(x => `<div>${x}</div>`).join('')}</div></div></div>`;

pages['10-dang-nhap'] = `${head('Đăng nhập')}<div class="split">
${authArt('Chào mừng trở lại!', 'Đăng nhập để tiếp tục hành trình khám phá Việt Nam cùng SGU Tour.')}
<div class="formside"><div class="authcard">
  <h3>Đăng nhập</h3><div class="sub">Nhập email và mật khẩu của bạn</div>
  <span class="lbl">Email</span><div class="inp" style="margin-bottom:14px">an.nguyen@example.com</div>
  <span class="lbl">Mật khẩu</span><div class="inp" style="margin-bottom:8px">••••••••</div>
  <div style="display:flex;justify-content:space-between;font-size:12.5px;margin-bottom:16px"><label class="mini"><input type="checkbox" checked> Ghi nhớ đăng nhập</label><a style="color:${P};font-weight:700">Quên mật khẩu?</a></div>
  <button class="btn" style="width:100%">Đăng nhập</button>
  <div class="divider">hoặc</div>
  <div class="gbtn"><span class="gicon"></span>Đăng nhập bằng Google</div>
  <div class="center mini" style="margin-top:18px">Chưa có tài khoản? <a style="color:${P};font-weight:700">Đăng ký ngay</a></div>
</div></div></div></body></html>`;

pages['11-dang-ky'] = `${head('Đăng ký')}<div class="split">
${authArt('Bắt đầu hành trình của bạn', 'Tạo tài khoản miễn phí để đặt tour, nhận ưu đãi và quản lý chuyến đi.')}
<div class="formside"><div class="authcard">
  <h3>Tạo tài khoản</h3><div class="sub">Chỉ mất chưa đến một phút</div>
  <span class="lbl">Họ và tên</span><div class="inp" style="margin-bottom:12px">Nguyễn Văn An</div>
  <span class="lbl">Email</span><div class="inp" style="margin-bottom:12px">an.nguyen@example.com</div>
  <span class="lbl">Số điện thoại</span><div class="inp" style="margin-bottom:12px">0901 234 567</div>
  <div class="form-grid" style="gap:12px"><div><span class="lbl">Mật khẩu</span><div class="inp">••••••••</div></div><div><span class="lbl">Xác nhận</span><div class="inp">••••••••</div></div></div>
  <label class="mini" style="display:block;margin:14px 0"><input type="checkbox" checked> Tôi đồng ý với <a style="color:${P}">điều khoản sử dụng</a></label>
  <button class="btn" style="width:100%">Đăng ký</button>
  <div class="center mini" style="margin-top:16px">Đã có tài khoản? <a style="color:${P};font-weight:700">Đăng nhập</a></div>
</div></div></div></body></html>`;

pages['12-tro-ly-ai'] = `${head('Trợ lý AI')}${header('home')}
<div class="hero" style="height:330px"><img class="bg" src="assets/sl3.jpg"><div class="mask"></div>
<div class="inner"><h1 style="font-size:36px">Trợ lý AI đồng hành cùng bạn</h1><p>Tư vấn tour theo ngân sách, thời gian và sở thích — ngay trong lúc bạn tìm kiếm.</p></div></div>
<div class="wrap" style="padding-top:40px"><div class="sec-head"><div><h2>Tour đang được quan tâm</h2></div></div>
<div class="cards">${tourCard('sl3.jpg', 'Tour Sapa 3 ngày 2 đêm', '3 ngày 2 đêm', 3550000, '4.8')}${tourCard('hero-halong-1600.webp', 'Tour Hạ Long 2 ngày 1 đêm', '2 ngày 1 đêm', 2890000, '4.7')}${tourCard('sl3.jpg', 'Tour Đà Nẵng – Hội An 4N3Đ', '4 ngày 3 đêm', 4990000, '4.9')}${tourCard('sl4.jpg', 'Tour Phú Quốc 3 ngày 2 đêm', '3 ngày 2 đêm', 6290000, '4.8')}</div></div>
<div class="chatwrap">
  <div class="chathead"><div class="av">✦</div><div><b>Trợ lý AI SGU Tour</b><span>Đang hoạt động · Trả lời trong vài giây</span></div><div style="margin-left:auto;font-size:18px">–</div></div>
  <div class="chatbody">
    <div class="msg bot">Xin chào! Mình có thể giúp bạn tìm tour, so sánh giá và tra cứu booking. Bạn muốn đi đâu?</div>
    <div class="msg user">Tôi muốn đi Đà Nẵng 3 ngày, ngân sách khoảng 5 triệu.</div>
    <div class="msg bot">Mình tìm thấy 2 tour phù hợp với ngân sách 5 triệu của bạn:
      <div class="mini-tour"><img src="assets/sl3.jpg"><div><b>Tour Đà Nẵng – Hội An 4N3Đ</b><span>4.990.000 ₫</span></div></div>
      <div class="mini-tour"><img src="assets/hero-halong-1600.webp"><div><b>Tour Đà Nẵng 3N2Đ — Bà Nà</b><span>3.890.000 ₫</span></div></div>
      <div style="font-size:12px;margin-top:8px;color:${MUTED}">Giá và chỗ trống được lấy trực tiếp từ hệ thống tour.</div>
    </div>
    <div class="msg user">Tour 3N2Đ còn chỗ không?</div>
    <div class="msg bot">Tour Đà Nẵng 3N2Đ còn <b>12 chỗ</b> cho ngày 24/12/2026. Bạn có muốn mình hướng dẫn đặt tour không?</div>
  </div>
  <div class="chatinput"><div class="inp">Nhập câu hỏi của bạn…</div><div class="roundbtn">➤</div></div>
</div></body></html>`;

pages['13-thong-tin-ca-nhan'] = `${head('Thông tin cá nhân')}${header('tours')}
<div class="pagehead"><div class="wrap"><div class="crumb">Trang chủ / Tài khoản</div><h1>Thông tin cá nhân</h1><p>Quản lý hồ sơ và bảo mật tài khoản</p></div></div>
<div class="wrap" style="padding:26px 0;display:grid;grid-template-columns:330px 1fr;gap:24px">
 <div class="panel"><div class="pb center">
   <div class="av" style="width:96px;height:96px;font-size:32px;margin:8px auto 12px">NA</div>
   <h3 style="font-size:17px">Nguyễn Văn An</h3><div class="mini">Thành viên từ 03/2025</div>
   <div style="display:flex;gap:8px;justify-content:center;margin-top:12px"><span class="badge b-green">Đang hoạt động</span><span class="badge b-blue">Khách hàng</span></div>
   <button class="btn plain sm" style="margin-top:16px">Đổi ảnh đại diện</button>
   <div style="border-top:1px solid ${LINE};margin-top:18px;padding-top:14px;text-align:left;font-size:13px;display:grid;gap:9px;color:#4b5563">
     <div>✉ an.nguyen@example.com</div><div>☎ 0901 234 567</div><div>◎ Quận 5, TP. Hồ Chí Minh</div></div>
 </div></div>
 <div class="panel"><div class="ph2">Thông tin tài khoản</div><div class="pb">
  <div class="form-grid">
   <div><span class="lbl">Họ và tên</span><div class="inp">Nguyễn Văn An</div></div>
   <div><span class="lbl">Email</span><div class="inp">an.nguyen@example.com</div></div>
   <div><span class="lbl">Số điện thoại</span><div class="inp">0901 234 567</div></div>
   <div><span class="lbl">Ngày sinh</span><div class="inp">12/03/1998</div></div>
   <div class="span2"><span class="lbl">Địa chỉ</span><div class="inp">273 An Dương Vương, Quận 5, TP. Hồ Chí Minh</div></div>
  </div>
  <div style="display:flex;gap:10px;margin-top:18px"><button class="btn">Lưu thay đổi</button><button class="btn plain">Đổi mật khẩu</button></div>
  <div style="border-top:1px solid ${LINE};margin-top:22px;padding-top:16px">
    <h4 style="font-size:14px;margin-bottom:10px">Bảo mật</h4>
    <div style="display:flex;justify-content:space-between;align-items:center;font-size:13px;padding:10px 0;border-bottom:1px solid #eef1ef"><div><b>Xác thực hai lớp</b><div class="mini">Bảo vệ tài khoản bằng mã OTP</div></div><span class="badge b-gray">Chưa bật</span></div>
    <div style="display:flex;justify-content:space-between;align-items:center;font-size:13px;padding:10px 0"><div><b>Phiên đăng nhập</b><div class="mini">Thiết bị hiện tại · TP. Hồ Chí Minh</div></div><span class="badge b-green">Đang hoạt động</span></div>
  </div>
 </div></div>
</div>${footer}</body></html>`;

// ---------------- Admin ----------------

pages['14-admin-tong-quan'] = `${head('Tổng quan')}${adminShell('dash', 'Tổng quan vận hành', `
<div class="kpis">
  <div class="kpi"><span>Doanh thu tháng</span><b>350.000.000 ₫</b><em>▲ 12,4% so với tháng trước</em></div>
  <div class="kpi"><span>Booking mới</span><b>120</b><em>▲ 8,1% so với tháng trước</em></div>
  <div class="kpi"><span>Tỷ lệ lấp chỗ</span><b>78,5%</b><em>▲ 4,2% so với tháng trước</em></div>
  <div class="kpi"><span>Booking hết hạn</span><b>4</b><em class="down">▼ 2 so với tháng trước</em></div>
</div>
<div class="grid2">
  <div class="panel2"><h4>Doanh thu theo tháng <span class="mini">Đơn vị: triệu ₫</span></h4>
    <div class="chart">${[45, 62, 58, 74, 69, 88, 95, 82, 110, 98, 124, 132].map((v, i) => `<div class="bar" style="height:${v}px"><span>${v}</span><x>T${i + 1}</x></div>`).join('')}</div>
    <div style="height:22px"></div>
  </div>
  <div class="panel2"><h4>Tour bán chạy</h4>
    ${[['Sapa 3N2Đ', 38, 'sl3.jpg'], ['Hạ Long 2N1Đ', 31, 'hero-halong-1600.webp'], ['Đà Nẵng 4N3Đ', 27, 'sl3.jpg'], ['Phú Quốc 3N2Đ', 18, 'sl4.jpg']].map(([n, c, img]) => `<div style="display:flex;align-items:center;gap:10px;margin-bottom:12px"><img class="thumb" src="assets/${img}"><div style="flex:1"><div style="display:flex;justify-content:space-between;font-size:12.5px"><b>${n}</b><span>${c} booking</span></div><div class="progress" style="margin-top:5px"><i style="width:${c * 2.5}%"></i></div></div></div>`).join('')}
  </div>
</div>
<div class="panel2" style="margin-top:18px"><h4>Booking gần đây <a style="color:${P};font-size:12.5px;font-weight:700">Xem tất cả →</a></h4>
<table><thead><tr><th>Mã</th><th>Khách hàng</th><th>Tour</th><th>Ngày đi</th><th style="text-align:right">Số tiền</th><th>Trạng thái</th></tr></thead>
<tbody>
<tr><td><b>#000123</b></td><td>Nguyễn Văn An</td><td>Tour Sapa 3N2Đ</td><td>20/12/2026</td><td style="text-align:right">8.875.000 ₫</td><td><span class="badge b-orange">Chờ thanh toán</span></td></tr>
<tr><td><b>#000122</b></td><td>Trần Thị Hà</td><td>Tour Hạ Long 2N1Đ</td><td>28/11/2026</td><td style="text-align:right">5.780.000 ₫</td><td><span class="badge b-green">Đã thanh toán</span></td></tr>
<tr><td><b>#000121</b></td><td>Lê Minh Long</td><td>Tour Đà Nẵng 4N3Đ</td><td>02/12/2026</td><td style="text-align:right">19.960.000 ₫</td><td><span class="badge b-green">Đã thanh toán</span></td></tr>
</tbody></table></div>`)}`;

pages['15-admin-nguoi-dung'] = `${head('Người dùng')}${adminShell('user', 'Quản lý người dùng', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp" style="width:260px">🔍 Tìm theo tên hoặc email…</div><div class="inp sel" style="width:150px">Trạng thái ▾</div><div class="inp sel" style="width:150px">Vai trò ▾</div></div><button class="btn">+ Thêm người dùng</button></div>
<div class="panel2" style="padding:0;overflow:hidden">
<table><thead><tr><th>ID</th><th>Người dùng</th><th>Email</th><th>Số điện thoại</th><th>Vai trò</th><th>Trạng thái</th><th>Ngày tạo</th><th></th></tr></thead>
<tbody>
${[['1', 'Quản trị viên', 'admin@example.com', '0900 000 001', 'Quản trị viên', 'Đang hoạt động', '01/01/2025', '#e0f2fe', '#0369a1'],
   ['7', 'Nguyễn Văn An', 'an.nguyen@example.com', '0901 234 567', 'Khách hàng', 'Đang hoạt động', '12/03/2025', '#dcfce7', '#166534'],
   ['8', 'Trần Thị Hà', 'ha.tran@example.com', '0902 345 678', 'Khách hàng', 'Đang hoạt động', '18/04/2025', '#dcfce7', '#166534'],
   ['9', 'Lê Minh Long', 'long.le@example.com', '0903 456 789', 'Khách hàng', 'Đang hoạt động', '02/06/2025', '#dcfce7', '#166534'],
   ['10', 'Phạm Thu Mai', 'mai.pham@example.com', '0904 567 890', 'Khách hàng', 'Bị khóa', '20/08/2025', '#fee2e2', '#991b1b'],
   ['11', 'Võ Hoàng Nam', 'nam.vo@example.com', '0905 678 901', 'Nhân viên', 'Đang hoạt động', '05/09/2025', '#dbeafe', '#1e40af']
].map(([id, n, e, p, r, s, d, bg, fg]) => `<tr><td>${id}</td><td><div style="display:flex;align-items:center;gap:9px"><div class="av" style="width:32px;height:32px;font-size:11px">${n.split(' ').map(x => x[0]).slice(0, 2).join('')}</div><b>${n}</b></div></td><td>${e}</td><td>${p}</td><td><span class="badge" style="background:${bg};color:${fg}">${r}</span></td><td><span class="badge ${s === 'Bị khóa' ? 'b-red' : 'b-green'}">${s}</span></td><td>${d}</td><td style="color:${P};font-weight:700">Sửa</td></tr>`).join('')}
</tbody></table></div>
<div style="display:flex;justify-content:space-between;align-items:center;margin-top:14px"><span class="mini">Hiển thị 1–6 trong 12 người dùng</span><div class="pagination"><span class="on">1</span><span>2</span></div></div>`)}`;

pages['16-admin-tour'] = `${head('Quản lý tour')}${adminShell('tour', 'Quản lý tour', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp" style="width:280px">🔍 Tìm theo tên tour…</div><div class="inp sel" style="width:160px">Điểm đến ▾</div><div class="inp sel" style="width:150px">Trạng thái ▾</div></div><button class="btn">+ Thêm tour</button></div>
<div class="panel2" style="padding:0;overflow:hidden">
<table><thead><tr><th>ID</th><th>Tour</th><th>Điểm đến</th><th>Thời lượng</th><th>Giá người lớn</th><th>Lịch khởi hành</th><th>Trạng thái</th><th>Thao tác</th></tr></thead>
<tbody>
<tr><td>1</td><td><div style="display:flex;align-items:center;gap:10px"><img class="thumb" src="assets/sl3.jpg"><div><b>Tour Sapa 3 ngày 2 đêm</b><div class="mini">Mã: SAPA-3N2D</div></div></div></td><td>Sapa</td><td>3N2Đ</td><td><b>3.550.000 ₫</b></td><td>12 lịch</td><td><span class="badge b-green">Đang mở bán</span></td><td style="color:${P};font-weight:700">Sửa · Xem</td></tr>
<tr><td>2</td><td><div style="display:flex;align-items:center;gap:10px"><img class="thumb" src="assets/hero-halong-1600.webp"><div><b>Tour Hạ Long 2 ngày 1 đêm</b><div class="mini">Mã: HALONG-2N1D</div></div></div></td><td>Hạ Long</td><td>2N1Đ</td><td><b>2.890.000 ₫</b></td><td>18 lịch</td><td><span class="badge b-green">Đang mở bán</span></td><td style="color:${P};font-weight:700">Sửa · Xem</td></tr>
<tr><td>3</td><td><div style="display:flex;align-items:center;gap:10px"><img class="thumb" src="assets/sl3.jpg"><div><b>Tour Đà Nẵng – Hội An 4N3Đ</b><div class="mini">Mã: DANANG-4N3D</div></div></div></td><td>Đà Nẵng</td><td>4N3Đ</td><td><b>4.990.000 ₫</b></td><td>9 lịch</td><td><span class="badge b-green">Đang mở bán</span></td><td style="color:${P};font-weight:700">Sửa · Xem</td></tr>
<tr><td>4</td><td><div style="display:flex;align-items:center;gap:10px"><img class="thumb" src="assets/sl4.jpg"><div><b>Tour Phú Quốc 3 ngày 2 đêm</b><div class="mini">Mã: PHUQUOC-3N2D</div></div></div></td><td>Phú Quốc</td><td>3N2Đ</td><td><b>6.290.000 ₫</b></td><td>7 lịch</td><td><span class="badge b-green">Đang mở bán</span></td><td style="color:${P};font-weight:700">Sửa · Xem</td></tr>
<tr><td>5</td><td><div style="display:flex;align-items:center;gap:10px"><img class="thumb" src="assets/sl3.jpg"><div><b>Tour Mộc Châu 2 ngày 1 đêm</b><div class="mini">Mã: MOCCHAU-2N1D</div></div></div></td><td>Mộc Châu</td><td>2N1Đ</td><td><b>1.750.000 ₫</b></td><td>6 lịch</td><td><span class="badge b-gray">Tạm dừng</span></td><td style="color:${P};font-weight:700">Sửa · Xem</td></tr>
</tbody></table></div>
<div style="display:flex;justify-content:space-between;align-items:center;margin-top:14px"><span class="mini">Hiển thị 1–5 trong 15 tour</span><div class="pagination"><span class="on">1</span><span>2</span><span>3</span></div></div>`)}`;

pages['17-admin-tour-form'] = `${head('Thêm tour')}${adminShell('tour', 'Quản lý tour', `
<div class="toolbar"><div class="mini" style="font-size:14px;color:${MUTED}">Danh sách tour</div><button class="btn">+ Thêm tour</button></div>
<div class="panel2" style="height:520px;background:#fafbfa;display:flex;align-items:center;justify-content:center;color:#9ca3af">Nội dung danh sách tour…</div>
<div class="modal"><div class="modalbox">
  <div class="modalhead"><span>Thêm tour mới</span><span style="color:#9ca3af">✕</span></div>
  <div class="modalbody">
    <div class="span2"><span class="lbl">Tên tour <em>*</em></span><div class="inp">Tour Sapa 3 ngày 2 đêm — săn mây Fansipan</div></div>
    <div><span class="lbl">Mã tour <em>*</em></span><div class="inp">SAPA-3N2D</div></div>
    <div><span class="lbl">Điểm đến <em>*</em></span><div class="inp sel">Sapa ▾</div></div>
    <div><span class="lbl">Thời lượng <em>*</em></span><div class="inp">3 ngày 2 đêm</div></div>
    <div><span class="lbl">Phương tiện <em>*</em></span><div class="inp sel">Xe giường nằm ▾</div></div>
    <div><span class="lbl">Giá người lớn (VND) <em>*</em></span><div class="inp">3550000</div></div>
    <div><span class="lbl">Giá trẻ em (VND)</span><div class="inp">1775000</div></div>
    <div class="span2"><span class="lbl">Mô tả</span><div class="inp" style="height:80px;align-items:flex-start;padding-top:10px">Khởi hành từ Hà Nội, chinh phục đỉnh Fansipan, nghỉ đêm tại khách sạn trung tâm thị trấn Sapa…</div></div>
    <div class="span2"><span class="lbl">Ảnh tour</span><div class="upload"><div style="font-size:22px">＋</div><span>Kéo thả hoặc chọn ảnh · tối đa 10 ảnh, 5 MiB/ảnh</span></div></div>
    <div class="span2"><span class="lbl">Trạng thái</span><div style="display:flex;gap:16px"><label class="mini"><input type="radio" checked> Đang mở bán</label><label class="mini"><input type="radio"> Tạm dừng</label></div></div>
  </div>
  <div class="modalfoot"><button class="btn plain">Hủy</button><button class="btn">Lưu tour</button></div>
</div></div>`)}`;

pages['18-admin-lich-khoi-hanh'] = `${head('Lịch khởi hành')}${adminShell('departure', 'Quản lý lịch khởi hành', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp" style="width:260px">🔍 Tìm tour…</div><div class="inp sel" style="width:180px">Trạng thái ▾</div><div class="inp" style="width:190px">▦ 12/2026</div></div><button class="btn">+ Thêm lịch khởi hành</button></div>
<div class="panel2" style="padding:0;overflow:hidden">
<table><thead><tr><th>ID</th><th>Tour</th><th>Ngày khởi hành</th><th>Ngày về</th><th style="text-align:center">Sức chứa</th><th style="text-align:center">Đã giữ</th><th style="text-align:center">Còn lại</th><th>Trạng thái</th></tr></thead>
<tbody>
${[['456', 'Tour Sapa 3N2Đ', '20/12/2026 06:00', '22/12/2026 20:00', 36, 6, 30, 'Mở bán'],
   ['457', 'Tour Sapa 3N2Đ', '24/12/2026 06:00', '26/12/2026 20:00', 36, 24, 12, 'Mở bán'],
   ['458', 'Tour Hạ Long 2N1Đ', '28/11/2026 07:30', '29/11/2026 18:00', 30, 30, 0, 'Hết chỗ'],
   ['459', 'Tour Đà Nẵng 4N3Đ', '02/12/2026 05:00', '05/12/2026 22:00', 40, 12, 28, 'Mở bán'],
   ['460', 'Tour Phú Quốc 3N2Đ', '15/12/2026 08:00', '17/12/2026 19:00', 24, 4, 20, 'Mở bán']
].map(([id, t, d, r, cap, held, left, st]) => `<tr><td>${id}</td><td><b>${t}</b></td><td>${d}</td><td>${r}</td><td style="text-align:center">${cap}</td><td style="text-align:center">${held}</td><td style="text-align:center"><b style="color:${left === 0 ? '#dc2626' : P}">${left}</b></td><td><span class="badge ${st === 'Mở bán' ? 'b-green' : 'b-red'}">${st}</span></td></tr>`).join('')}
</tbody></table></div>
<div class="note" style="margin-top:16px">Sức chứa được kiểm tra trong transaction khi giữ chỗ; lịch hết chỗ không thể nhận booking mới. Lịch đã có booking hoặc dời lịch không được xóa.</div>`)}`;

pages['19-admin-lich-trinh'] = `${head('Lịch trình')}${adminShell('itinerary', 'Quản lý lịch trình', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp sel" style="width:300px">Tour Sapa 3 ngày 2 đêm ▾</div><div class="mini">3 ngày · 8 hoạt động</div></div><button class="btn">+ Thêm lịch trình</button></div>
<div class="panel2">
  ${[['Ngày 1', 'Hà Nội – Sapa', [{ t: '06:00', n: 'Khởi hành từ Hà Nội', d: 'Tập trung tại điểm hẹn, xe giường nằm khởi hành đi Sapa.' }, { t: '12:00', n: 'Nghỉ trưa và ăn trưa', d: 'Nhà hàng địa phương trên tuyến.' }, { t: '15:00', n: 'Nhận phòng khách sạn', d: 'Khách sạn 3 sao tại trung tâm thị trấn Sapa.' }]],
     ['Ngày 2', 'Chinh phục Fansipan', [{ t: '07:00', n: 'Ăn sáng tại khách sạn', d: 'Buffet sáng.' }, { t: '08:30', n: 'Cáp treo Fansipan', d: 'Chinh phục đỉnh Fansipan, ngắm biển mây.' }, { t: '18:00', n: 'Tự do khám phá Sapa', d: 'Chợ đêm, quảng trường.' }]],
     ['Ngày 3', 'Sapa – Hà Nội', [{ t: '07:30', n: 'Tham quan bản Cát Cát', d: 'Tìm hiểu văn hóa bản địa.' }, { t: '14:00', n: 'Khởi hành về Hà Nội', d: 'Kết thúc chương trình.' }]]].map(([day, title, items]) => `
  <div style="margin-bottom:18px"><div style="display:flex;align-items:center;gap:10px;margin-bottom:10px"><span class="badge b-blue">${day}</span><b style="font-size:14.5px">${title}</b><a style="margin-left:auto;color:${P};font-size:12.5px;font-weight:700">Sửa</a></div>
  <div class="timeline">${items.map((x) => `<div class="tl"><b>${x.t} · ${x.n}</b><p>${x.d}</p></div>`).join('')}</div></div>`).join('')}
</div>`)}`;

pages['20-admin-dia-diem'] = `${head('Địa điểm')}${adminShell('location', 'Quản lý địa điểm', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp" style="width:260px">🔍 Tìm địa điểm…</div><div class="inp sel" style="width:160px">Loại ▾</div></div><button class="btn">+ Thêm địa điểm</button></div>
<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:18px">
${[['Sapa', 'Lào Cai', 'Điểm đến', 'sl3.jpg', 15], ['Hạ Long', 'Quảng Ninh', 'Điểm đến', 'hero-halong-1600.webp', 12], ['Đà Nẵng', 'Đà Nẵng', 'Điểm đến', 'sl3.jpg', 18], ['Phú Quốc', 'Kiên Giang', 'Điểm đến', 'sl4.jpg', 9], ['Hà Nội', 'Hà Nội', 'Điểm khởi hành', 'hero-halong-1600.webp', 24], ['Ninh Bình', 'Ninh Bình', 'Điểm đến', 'sl3.jpg', 7]].map(([n, p, t, img, c]) => `
<div class="panel" style="overflow:hidden"><img src="assets/${img}" style="height:150px;width:100%;object-fit:cover"><div style="padding:14px 15px"><div style="display:flex;justify-content:space-between;align-items:flex-start"><div><b style="font-size:15px">${n}</b><div class="mini">${p}</div></div><span class="badge ${t === 'Điểm đến' ? 'b-blue' : 'b-orange'}">${t}</span></div><div style="display:flex;justify-content:space-between;margin-top:12px;font-size:12.5px;color:${MUTED}"><span>${c} tour</span><span style="color:${P};font-weight:700">Sửa</span></div></div></div>`).join('')}
</div>`)}`;

pages['21-admin-gia'] = `${head('Combo giá')}${adminShell('price', 'Quản lý combo giá', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp" style="width:280px">🔍 Tìm theo mã tour…</div><div class="inp sel" style="width:170px">Hiệu lực ▾</div></div><button class="btn">+ Thêm bảng giá</button></div>
<div class="panel2" style="padding:0;overflow:hidden">
<table><thead><tr><th>Mã giá</th><th>Tour</th><th style="text-align:right">Người lớn</th><th style="text-align:right">Trẻ em</th><th style="text-align:right">Trẻ nhỏ</th><th style="text-align:right">Em bé</th><th style="text-align:right">Phụ thu phòng đơn</th><th>Hiệu lực</th></tr></thead>
<tbody>
${[['PRICE-001', 'Tour Sapa 3N2Đ', 3550000, 1775000, 0, 0, 500000, '01/11 – 31/12'],
   ['PRICE-002', 'Tour Hạ Long 2N1Đ', 2890000, 1445000, 0, 0, 450000, '01/11 – 31/12'],
   ['PRICE-003', 'Tour Đà Nẵng 4N3Đ', 4990000, 2495000, 500000, 0, 700000, '15/11 – 31/01'],
   ['PRICE-004', 'Tour Phú Quốc 3N2Đ', 6290000, 3145000, 0, 0, 900000, '01/12 – 28/02']
].map(([c, t, a, ch, td, inf, s, e]) => `<tr><td><b>${c}</b></td><td>${t}</td><td style="text-align:right">${money(a)}</td><td style="text-align:right">${money(ch)}</td><td style="text-align:right">${money(td)}</td><td style="text-align:right">${money(inf)}</td><td style="text-align:right">${money(s)}</td><td><span class="badge b-green">${e}</span></td></tr>`).join('')}
</tbody></table></div>
<div style="display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:18px">
  <div class="panel2"><h4>Quy tắc giá</h4><ul style="font-size:12.8px;color:#4b5563;line-height:1.9;padding-left:18px"><li>Giá là số nguyên VND, tối đa 9.999.999.999.</li><li>Giá người lớn phải lớn hơn 0.</li><li>Giá được chốt tại thời điểm tạo booking.</li><li>Thanh toán tính lại theo từng hành khách và nhóm tuổi.</li></ul></div>
  <div class="panel2"><h4>Lấy giá theo lô</h4><div class="mini" style="line-height:1.8">Tour và booking gọi <b>POST /internal/pricing/tours/price/quote</b> để lấy bảng giá và tính tổng tiền trong một lượt, giảm số lời gọi giữa các service.</div><div class="note" style="margin-top:10px">Pricing service là nguồn dữ liệu giá duy nhất; tour service lưu tham chiếu bảng giá, không lưu bản sao giá.</div></div>
</div>`)}`;

pages['22-admin-booking'] = `${head('Quản lý booking')}${adminShell('booking', 'Quản lý booking', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp" style="width:260px">🔍 Mã booking / khách hàng…</div><div class="inp sel" style="width:170px">Trạng thái ▾</div><div class="inp" style="width:180px">▦ 11/2026</div></div><button class="btn plain">Xuất Excel</button></div>
<div class="kpis" style="grid-template-columns:repeat(4,1fr)"><div class="kpi"><span>Tổng booking</span><b>128</b></div><div class="kpi"><span>Chờ thanh toán</span><b style="color:#c2410c">14</b></div><div class="kpi"><span>Đã xác nhận</span><b style="color:#15803d">102</b></div><div class="kpi"><span>Đã hủy / hết hạn</span><b style="color:#6b7280">12</b></div></div>
<div class="panel2" style="padding:0;overflow:hidden">
<table><thead><tr><th>Mã</th><th>Khách hàng</th><th>Tour</th><th>Ngày đi</th><th style="text-align:center">Khách</th><th style="text-align:right">Tổng tiền</th><th>Giữ chỗ</th><th>Trạng thái</th><th></th></tr></thead>
<tbody>
${[['#000123', 'Nguyễn Văn An', 'Sapa 3N2Đ', '20/12/2026', 3, 8875000, 'Còn 13:45', 'Chờ thanh toán'],
   ['#000122', 'Trần Thị Hà', 'Hạ Long 2N1Đ', '28/11/2026', 2, 5780000, 'Đã thanh toán', 'Đã xác nhận'],
   ['#000121', 'Lê Minh Long', 'Đà Nẵng 4N3Đ', '02/12/2026', 4, 19960000, 'Đã thanh toán', 'Đã xác nhận'],
   ['#000120', 'Phạm Thu Mai', 'Phú Quốc 3N2Đ', '15/12/2026', 2, 12580000, 'Hết hạn', 'Hết hạn'],
   ['#000119', 'Võ Hoàng Nam', 'Sapa 3N2Đ', '24/12/2026', 1, 3550000, 'Đã thanh toán', 'Đã xác nhận']
].map(([c, n, t, d, s, m, h, st]) => `<tr><td><b>${c}</b></td><td>${n}</td><td>${t}</td><td>${d}</td><td style="text-align:center">${s}</td><td style="text-align:right">${money(m)}</td><td class="mini">${h}</td><td><span class="badge ${st === 'Đã xác nhận' ? 'b-green' : st === 'Chờ thanh toán' ? 'b-orange' : 'b-gray'}">${st}</span></td><td style="color:${P};font-weight:700">Chi tiết</td></tr>`).join('')}
</tbody></table></div>`)}`;

pages['23-admin-hoa-don'] = `${head('Hóa đơn')}${adminShell('invoice', 'Quản lý hóa đơn', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp" style="width:260px">🔍 Mã hóa đơn / booking…</div><div class="inp sel" style="width:170px">Trạng thái ▾</div></div><button class="btn plain">Xuất báo cáo</button></div>
<div class="panel2" style="padding:0;overflow:hidden">
<table><thead><tr><th>Mã hóa đơn</th><th>Booking</th><th>Khách hàng</th><th>Ngày phát hành</th><th style="text-align:right">Số tiền</th><th>Phương thức</th><th>Trạng thái</th><th></th></tr></thead>
<tbody>
${[['HD-2026-000123', '#000123', 'Nguyễn Văn An', '18/11/2026', 8875000, 'VNPay', 'Chờ thanh toán'],
   ['HD-2026-000122', '#000122', 'Trần Thị Hà', '17/11/2026', 5780000, 'VNPay', 'Đã thanh toán'],
   ['HD-2026-000121', '#000121', 'Lê Minh Long', '16/11/2026', 19960000, 'MoMo', 'Đã thanh toán'],
   ['HD-2026-000120', '#000120', 'Phạm Thu Mai', '15/11/2026', 12580000, 'VNPay', 'Đã hoàn tiền'],
   ['HD-2026-000119', '#000119', 'Võ Hoàng Nam', '14/11/2026', 3550000, 'VNPay', 'Đã thanh toán']
].map(([i, b, n, d, m, p, st]) => `<tr><td><b>${i}</b></td><td>${b}</td><td>${n}</td><td>${d}</td><td style="text-align:right">${money(m)}</td><td>${p}</td><td><span class="badge ${st === 'Đã thanh toán' ? 'b-green' : st === 'Chờ thanh toán' ? 'b-orange' : 'b-gray'}">${st}</span></td><td style="color:${P};font-weight:700">Xem</td></tr>`).join('')}
</tbody></table></div>
<div class="note" style="margin-top:16px">Mỗi booking chỉ có một hóa đơn (ràng buộc <b>uk_invoice_booking</b>). Hóa đơn được tạo idempotent từ sự kiện <b>payment.settled.v1</b> qua inbox.</div>`)}`;

pages['24-admin-phan-quyen'] = `${head('Vai trò & quyền')}${adminShell('role', 'Vai trò và phân quyền', `
<div style="display:grid;grid-template-columns:330px 1fr;gap:18px">
 <div class="panel2"><h4>Vai trò <button class="btn sm">+ Thêm</button></h4>
  ${[['Quản trị viên', 'Toàn quyền hệ thống', 24, true], ['Nhân viên', 'Quản lý tour, booking', 16, false], ['Khách hàng', 'Đặt tour, xem lịch sử', 8, false]].map(([n, d, c, on]) => `<div style="border:1.5px solid ${on ? P : LINE};border-radius:10px;padding:12px;margin-bottom:10px;background:${on ? '#f4faf8' : '#fff'}"><div style="display:flex;justify-content:space-between"><b style="font-size:13.5px">${n}</b><span class="badge b-blue">${c} quyền</span></div><div class="mini" style="margin-top:4px">${d}</div></div>`).join('')}
 </div>
 <div class="panel2"><h4>Ma trận quyền · <span style="color:${P}">Quản trị viên</span></h4>
 <table style="font-size:12.5px"><thead><tr><th>Module</th><th>Xem</th><th>Tạo</th><th>Sửa</th><th>Xóa</th><th>Duyệt</th></tr></thead>
 <tbody>
 ${[['Tour', 1, 1, 1, 1, 1], ['Lịch khởi hành', 1, 1, 1, 1, 1], ['Lịch trình', 1, 1, 1, 1, 0], ['Địa điểm', 1, 1, 1, 1, 0], ['Giá', 1, 1, 1, 1, 0], ['Booking', 1, 1, 1, 0, 1], ['Hóa đơn', 1, 0, 0, 0, 1], ['Đối soát', 1, 1, 1, 0, 1], ['Người dùng', 1, 1, 1, 1, 0], ['Vai trò', 1, 1, 1, 1, 0], ['Đánh giá', 1, 0, 1, 1, 1]].map(([m, ...r]) => `<tr><td><b>${m}</b></td>${r.map(v => `<td>${v ? `<span style="color:${P};font-weight:800">✓</span>` : '<span style="color:#d1d5db">—</span>'}</td>`).join('')}</tr>`).join('')}
 </tbody></table>
 <div class="note" style="margin-top:14px">Mã quyền dùng tiền tố <b>PERM:&lt;CODE&gt;</b> trong JWT. Thay đổi quyền làm tăng <b>sessionEpoch</b> của tài khoản bị ảnh hưởng, khiến token cũ hết hiệu lực.</div>
 </div>
</div>`)}`;

pages['25-admin-danh-gia'] = `${head('Đánh giá')}${adminShell('review', 'Quản lý đánh giá', `
<div class="toolbar"><div style="display:flex;gap:10px"><div class="inp" style="width:260px">🔍 Tìm theo tour / khách…</div><div class="inp sel" style="width:170px">Trạng thái ▾</div><div class="inp sel" style="width:150px">Số sao ▾</div></div><div class="mini">128 đánh giá · 12 chờ duyệt</div></div>
<div class="panel2" style="padding:0;overflow:hidden">
<table><thead><tr><th>ID</th><th>Khách hàng</th><th>Tour</th><th>Đánh giá</th><th style="text-align:center">Sao</th><th>Ngày</th><th>Trạng thái</th><th>Thao tác</th></tr></thead>
<tbody>
${[['1', 'Nguyễn Văn An', 'Sapa 3N2Đ', 'Chuyến đi tuyệt vời, hướng dẫn viên vui tính…', 5, '20/11/2026', 'Đã duyệt'],
   ['2', 'Trần Thị Hà', 'Hạ Long 2N1Đ', 'Lịch trình hợp lý, đồ ăn ngon…', 5, '18/11/2026', 'Đã duyệt'],
   ['3', 'Lê Minh Long', 'Đà Nẵng 4N3Đ', 'Cáp treo hơi đông nhưng cảnh đẹp…', 4, '15/11/2026', 'Chờ duyệt'],
   ['4', 'Phạm Thu Mai', 'Phú Quốc 3N2Đ', 'Resort đẹp, dịch vụ tốt…', 5, '12/11/2026', 'Đã duyệt'],
   ['5', 'Võ Hoàng Nam', 'Sapa 3N2Đ', 'Thời tiết xấu, tour vẫn ổn…', 4, '10/11/2026', 'Đã ẩn']
].map(([id, n, t, c, s, d, st]) => `<tr><td>${id}</td><td>${n}</td><td>${t}</td><td style="max-width:290px">${c}</td><td style="text-align:center;color:${ACC}">${'★'.repeat(s)}${'☆'.repeat(5 - s)}</td><td>${d}</td><td><span class="badge ${st === 'Đã duyệt' ? 'b-green' : st === 'Chờ duyệt' ? 'b-orange' : 'b-gray'}">${st}</span></td><td style="color:${P};font-weight:700">Duyệt · Ẩn</td></tr>`).join('')}
</tbody></table></div>`)}`;

// Workflow diagrams (n8n-style / AI flow)
pages['26-n8n-email'] = `${head('n8n workflow')}
<div style="background:#f3f5f7;min-height:900px;font-family:'Segoe UI',Arial,sans-serif">
 <div style="background:#fff;border-bottom:1px solid ${LINE};height:60px;display:flex;align-items:center;padding:0 30px;gap:14px">
   <div style="font-weight:800;font-size:16px">n8n</div><div style="color:${MUTED};font-size:13px">SGU Tour · Workflow nhắc thanh toán & khởi hành</div>
   <div style="margin-left:auto;display:flex;gap:10px"><span class="badge b-green">Active</span><span class="badge b-gray">Schedule</span></div>
 </div>
 <div style="padding:30px 40px">
   <h2 style="font-size:18px;margin-bottom:6px">Luồng gửi email tự động</h2>
   <p class="mini" style="margin-bottom:26px">Định kỳ quét booking đến hạn, gọi API nội bộ của backend để tạo lượt nhắc. n8n không quyết định số ghế hay trạng thái thanh toán.</p>
   <div style="display:flex;align-items:center;gap:0;flex-wrap:nowrap">
     <div class="fnode" style="border-color:#f59e0b;background:#fffbeb">⏱ Schedule Trigger<br><span class="tagline">mỗi 15 phút</span></div>
     <div class="farrow">→</div>
     <div class="fnode">▤ HTTP Request<br><span class="tagline">GET /internal/booking/reminders</span></div>
     <div class="farrow">→</div>
     <div class="fnode">◍ IF còn hạn?<br><span class="tagline">expiresAt &gt; now</span></div>
     <div class="farrow">→</div>
     <div class="fnode" style="min-width:150px">✉ Gửi email<br><span class="tagline">SMTP / Notification</span></div>
     <div class="farrow">→</div>
     <div class="fnode" style="border-color:#16a34a">✓ Ghi nhận<br><span class="tagline">delivery status</span></div>
   </div>
   <div style="display:flex;gap:20px;margin-top:34px">
     <div class="panel2" style="flex:1"><h4>Nhánh nhắc thanh toán</h4>
       <div class="flowv">
         <div class="flowrow"><div class="fnode ext" style="min-width:150px">Booking PENDING</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Còn ≤ 7 ngày</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Hạn = min(đặt+15p, giờ đi)</div></div>
         <div class="flowrow"><div class="fnode ext" style="min-width:150px">Booking PENDING</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Còn &gt; 7 ngày</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Nhắc ngày đặt + 3</div></div>
       </div>
       <div class="note" style="margin-top:14px">Mốc nhắc thứ hai là <b>hạn − 1</b>; nếu trùng mốc thứ nhất thì gộp một email. Nội dung và thời điểm do backend kiểm tra.</div>
     </div>
     <div class="panel2" style="flex:1"><h4>Bảo đảm tin cậy</h4>
       <ul style="font-size:12.8px;color:#4b5563;line-height:1.9;padding-left:18px">
         <li>API nội bộ yêu cầu <b>X-Internal-Mail-Key</b>.</li>
         <li>Email xếp vào <b>mail_outbox</b> với khóa sự kiện duy nhất.</li>
         <li>Worker gửi có trạng thái PENDING / SENT / RETRY / FAILED.</li>
         <li>Không gửi lại vô hạn khi trạng thái không rõ; đối chiếu trước khi retry.</li>
       </ul>
     </div>
   </div>
   <div class="panel2" style="margin-top:20px"><h4>Ví dụ nội dung email</h4>
     <div style="background:#f8fafc;border:1px solid ${LINE};border-radius:10px;padding:16px;font-size:13px;line-height:1.8">
       <b>Chủ đề:</b> [SGU Tour] Nhắc thanh toán booking #SGUTOUR-000123<br>
       <span style="color:${MUTED}">Kính gửi Nguyễn Văn An,</span><br>
       Booking của bạn cho tour <b>Sapa 3 ngày 2 đêm</b> khởi hành <b>20/12/2026</b> đang chờ thanh toán.<br>
       Vui lòng hoàn tất trước <b>08:00 ngày 14/12/2026</b> để giữ chỗ. Sau thời hạn, hệ thống tự động trả chỗ.
     </div>
   </div>
 </div>
</div></body></html>`;

pages['27-ai-flow'] = `${head('Luồng AI')}
<div style="background:#f3f5f7;min-height:900px;padding:30px 40px">
  <h2 style="font-size:20px">Luồng xử lý trợ lý AI có kiểm soát</h2>
  <p class="mini" style="margin:6px 0 24px">Spring AI + Groq; backend kiểm tra kế hoạch, dữ liệu tour/booking được lấy qua API nội bộ, không truy vấn SQL trực tiếp.</p>
  <div style="display:flex;align-items:center;gap:10px;margin-bottom:26px">
    <div class="fnode ext">Khách đặt câu hỏi</div><div class="farrow">→</div>
    <div class="fnode">API Gateway<br><span class="tagline">rate limit / JWT</span></div><div class="farrow">→</div>
    <div class="fnode">AI Service<br><span class="tagline">/ai/chat</span></div><div class="farrow">→</div>
    <div class="fnode" style="border-color:#f59e0b">Guard nội dung<br><span class="tagline">chặn prompt injection</span></div><div class="farrow">→</div>
    <div class="fnode">Lập kế hoạch<br><span class="tagline">JSON có kiểu</span></div>
  </div>
  <div style="display:grid;grid-template-columns:1fr 1fr;gap:20px">
    <div class="panel2"><h4>Nhánh tư vấn tour</h4>
      <div class="flowv">
        <div class="flowrow"><div class="fnode" style="min-width:150px">Tiêu chí tìm kiếm</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Tour API</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Pricing API</div></div>
        <div class="flowrow"><div class="fnode" style="min-width:150px">Dữ liệu có nguồn</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Groq model</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Câu trả lời</div></div>
      </div>
      <div class="note" style="margin-top:14px">Giá, lịch và chỗ trống do backend cung cấp; mô hình không tự suy ra giá hay tình trạng chỗ.</div>
    </div>
    <div class="panel2"><h4>Nhánh tra cứu booking</h4>
      <div class="flowv">
        <div class="flowrow"><div class="fnode" style="min-width:150px">JWT chính chủ</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Booking API</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Kiểm tra quyền</div></div>
        <div class="flowrow"><div class="fnode" style="min-width:150px">Chỉ booking của mình</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Ngữ cảnh giới hạn</div><div class="farrow">→</div><div class="fnode" style="min-width:150px">Trả lời</div></div>
      </div>
      <div class="note" style="margin-top:14px">Không đưa booking của người khác vào ngữ cảnh mô hình; không cho phép đặt tour, thu tiền hay gửi mail.</div>
    </div>
  </div>
  <div class="panel2" style="margin-top:20px"><h4>Chính sách an toàn</h4>
    <div style="display:grid;grid-template-columns:repeat(4,1fr);gap:12px;font-size:12.5px;color:#4b5563">
      <div class="chip" style="padding:10px">Không chạy mã / SQL</div><div class="chip" style="padding:10px">Không lộ secret</div><div class="chip" style="padding:10px">Giới hạn kích thước input</div><div class="chip" style="padding:10px">Phiên RAM có TTL</div>
    </div>
    <div class="mini" style="margin-top:12px">Phiên hội thoại mặc định 30 phút, tối đa 100 phiên; mất phiên khi restart là chấp nhận được với vai trò tư vấn.</div>
  </div>
</div></body></html>`;

for (const [name, html] of Object.entries(pages)) {
  // HTML lives in html/, the real brand assets live one level up in assets/.
  fs.writeFileSync(path.join(outDir, name + '.html'), html.replaceAll('"assets/', '"../assets/'), 'utf8');
}
console.log('Wrote ' + Object.keys(pages).length + ' pages to ' + outDir);