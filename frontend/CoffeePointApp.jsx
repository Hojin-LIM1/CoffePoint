import { useState, useEffect, useCallback } from "react";

const API = "http://localhost:8080/api";

const TABS = [
  { id: "menu", label: "메뉴", icon: "☕" },
  { id: "order", label: "주문하기", icon: "🛒" },
  { id: "popular", label: "인기메뉴", icon: "🔥" },
  { id: "point", label: "포인트", icon: "💰" },
  { id: "inventory", label: "재고", icon: "📦" },
  { id: "analytics", label: "분석", icon: "📊" },
];

async function api(path, options = {}) {
  try {
    const res = await fetch(`${API}${path}`, {
      headers: { "Content-Type": "application/json" },
      ...options,
    });
    const data = await res.json().catch(() => null);
    return { ok: res.ok, status: res.status, data };
  } catch (e) {
    return { ok: false, status: 0, data: null, error: e.message };
  }
}

export default function CoffeePointApp() {
  const [tab, setTab] = useState("menu");
  const [userId, setUserId] = useState(1);
  const [toast, setToast] = useState(null);
  const [balance, setBalance] = useState(null);

  const showToast = useCallback((msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3000);
  }, []);

  const fetchBalance = useCallback(async () => {
    const res = await api(`/points/${userId}`);
    if (res.ok) setBalance(res.data.balance);
  }, [userId]);

  useEffect(() => { fetchBalance(); }, [fetchBalance]);

  return (
    <div style={S.app}>
      <header style={S.header}>
        <div style={S.headerInner}>
          <div>
            <h1 style={S.logo}>☕ 커피포인트 <span style={S.version}>v2</span></h1>
            <p style={S.subtitle}>Kafka · Outbox · FIFO Inventory</p>
          </div>
          <div style={S.userArea}>
            <select value={userId} onChange={e => setUserId(Number(e.target.value))} style={S.userSelect}>
              <option value={1}>👤 홍길동</option>
              <option value={2}>👤 김영희</option>
            </select>
            <div style={S.balanceBadge}>{balance !== null ? `${balance.toLocaleString()}P` : "---"}</div>
          </div>
        </div>
      </header>

      <nav style={S.nav}>
        {TABS.map(t => (
          <button key={t.id} onClick={() => setTab(t.id)}
            style={{ ...S.navBtn, ...(tab === t.id ? S.navActive : {}) }}>
            <span style={{ fontSize: 15 }}>{t.icon}</span>
            <span>{t.label}</span>
          </button>
        ))}
      </nav>

      <main style={S.main}>
        {tab === "menu" && <MenuList />}
        {tab === "order" && <OrderPanel userId={userId} onOrder={fetchBalance} showToast={showToast} />}
        {tab === "popular" && <PopularMenus />}
        {tab === "point" && <PointPanel userId={userId} balance={balance} onCharge={fetchBalance} showToast={showToast} />}
        {tab === "inventory" && <InventoryPanel showToast={showToast} />}
        {tab === "analytics" && <AnalyticsDashboard />}
      </main>

      {toast && (
        <div style={{ ...S.toast, ...(toast.type === "error" ? S.toastErr : {}) }}>
          {toast.type === "error" ? "❌ " : "✅ "}{toast.msg}
        </div>
      )}
    </div>
  );
}

// ============================================================
// 메뉴 목록
// ============================================================
function MenuList() {
  const [menus, setMenus] = useState([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => { api("/menus").then(r => { if (r.ok) setMenus(r.data); setLoading(false); }); }, []);
  if (loading) return <Loader />;
  return (
    <div>
      <Title icon="☕" title="메뉴 목록" sub="Caffeine 캐시 (TTL 10분)" />
      <div style={S.grid}>{menus.map(m => (
        <div key={m.id} style={S.card}>
          <div style={S.emoji}>{EMOJIS[m.id % 5]}</div>
          <h3 style={S.cardTitle}>{m.name}</h3>
          <p style={S.price}>{m.price.toLocaleString()}원</p>
        </div>
      ))}</div>
    </div>
  );
}

// ============================================================
// 주문하기 (재고 실시간 표시)
// ============================================================
function OrderPanel({ userId, onOrder, showToast }) {
  const [menus, setMenus] = useState([]);
  const [selected, setSelected] = useState(null);
  const [ordering, setOrdering] = useState(false);
  const [inv, setInv] = useState({});

  const loadData = useCallback(async () => {
    const r = await api("/menus");
    if (!r.ok) return;
    setMenus(r.data);
    const stock = {};
    for (const m of r.data) {
      const ir = await api(`/inventory/${m.id}`);
      if (ir.ok) stock[m.id] = ir.data.availableQuantity;
    }
    setInv(stock);
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  const handleOrder = async () => {
    if (!selected) return;
    setOrdering(true);
    const res = await api("/orders", { method: "POST", body: JSON.stringify({ userId, menuId: selected.id }) });
    setOrdering(false);
    if (res.ok) {
      showToast(`${selected.name} 주문 완료! 잔액: ${res.data.remainBalance.toLocaleString()}P`);
      onOrder();
      const ir = await api(`/inventory/${selected.id}`);
      if (ir.ok) setInv(p => ({ ...p, [selected.id]: ir.data.availableQuantity }));
    } else {
      const code = res.data?.code;
      if (code === "INVENTORY_001") showToast("재고가 부족합니다", "error");
      else if (code === "ORDER_001") showToast("포인트가 부족합니다", "error");
      else showToast(res.data?.message || "주문 실패", "error");
    }
  };

  return (
    <div>
      <Title icon="🛒" title="주문하기" sub="비관적 락(재고) + 낙관적 락(포인트) + Outbox" />
      <div style={S.grid}>{menus.map(m => {
        const stock = inv[m.id];
        const out = stock !== undefined && stock <= 0;
        return (
          <div key={m.id} onClick={() => !out && setSelected(m)}
            style={{ ...S.card, ...S.selectable, ...(selected?.id === m.id ? S.selected : {}), ...(out ? S.disabled : {}) }}>
            <div style={S.emoji}>{EMOJIS[m.id % 5]}</div>
            <h3 style={S.cardTitle}>{m.name}</h3>
            <p style={S.price}>{m.price.toLocaleString()}원</p>
            <span style={{ ...S.stockBadge, ...(out ? S.stockOut : {}) }}>
              {out ? "품절" : `재고 ${stock ?? "..."}개`}
            </span>
          </div>
        );
      })}</div>
      {selected && (
        <div style={S.orderBar}>
          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <span style={{ fontSize: 28 }}>{EMOJIS[selected.id % 5]}</span>
            <div>
              <strong>{selected.name}</strong>
              <p style={{ margin: 0, color: "#8b6f47", fontSize: 14 }}>{selected.price.toLocaleString()}P 차감</p>
            </div>
          </div>
          <button onClick={handleOrder} disabled={ordering} style={S.orderBtn}>
            {ordering ? "주문 중..." : "주문하기"}
          </button>
        </div>
      )}
    </div>
  );
}

// ============================================================
// 인기 메뉴
// ============================================================
function PopularMenus() {
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => { api("/menus/popular").then(r => { if (r.ok) setList(r.data); setLoading(false); }); }, []);
  if (loading) return <Loader />;
  const medals = ["🥇", "🥈", "🥉"];
  return (
    <div>
      <Title icon="🔥" title="인기 메뉴 TOP 3" sub="Redis ZSET + SETNX Stampede 방어" />
      {list.length === 0 ? <Empty msg="아직 주문 데이터가 없습니다" /> : (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {list.map((m, i) => (
            <div key={m.id} style={S.popCard}>
              <span style={{ fontSize: 32 }}>{medals[i] || `${i+1}`}</span>
              <div style={{ flex: 1 }}>
                <h3 style={{ margin: 0, fontSize: 17, fontWeight: 700 }}>{m.name}</h3>
                <p style={{ margin: 0, fontSize: 14, color: "#8b7355" }}>{m.price.toLocaleString()}원</p>
              </div>
              <div style={{ textAlign: "center" }}>
                <span style={{ display: "block", fontSize: 24, fontWeight: 800, color: "#d4a574" }}>{m.orderCount}</span>
                <span style={{ fontSize: 11, color: "#8b7355" }}>주문</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ============================================================
// 포인트 충전
// ============================================================
function PointPanel({ userId, balance, onCharge, showToast }) {
  const [amount, setAmount] = useState("");
  const [charging, setCharging] = useState(false);
  const presets = [1000, 5000, 10000, 50000, 100000];

  const handleCharge = async () => {
    const val = parseInt(amount);
    if (!val || val < 1000) { showToast("최소 1,000원 이상 충전해주세요", "error"); return; }
    setCharging(true);
    const res = await api(`/points/${userId}/charge`, { method: "PATCH", body: JSON.stringify({ amount: val }) });
    setCharging(false);
    if (res.ok) { showToast(`${val.toLocaleString()}P 충전! 잔액: ${res.data.balance.toLocaleString()}P`); setAmount(""); onCharge(); }
    else showToast(res.data?.message || "충전 실패", "error");
  };

  return (
    <div>
      <Title icon="💰" title="포인트 충전" sub="@Version 낙관적 락 + @Retryable 재시도" />
      <div style={S.pointCard}>
        <p style={{ margin: 0, fontSize: 14, opacity: 0.7 }}>현재 잔액</p>
        <p style={{ margin: "8px 0 0", fontSize: 42, fontWeight: 800 }}>
          {balance !== null ? balance.toLocaleString() : "---"}<span style={{ fontSize: 20, opacity: 0.6, marginLeft: 4 }}>P</span>
        </p>
      </div>
      <div style={S.chargeBox}>
        <div style={S.presetGrid}>
          {presets.map(p => (
            <button key={p} onClick={() => setAmount(String(p))}
              style={{ ...S.presetBtn, ...(String(p) === amount ? S.presetActive : {}) }}>
              {p >= 10000 ? `${p/10000}만` : p.toLocaleString()}원
            </button>
          ))}
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <input type="number" placeholder="직접 입력 (최소 1,000원)" value={amount}
            onChange={e => setAmount(e.target.value)} style={S.input} />
          <button onClick={handleCharge} disabled={charging} style={S.chargeBtn}>
            {charging ? "충전 중..." : "충전하기"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// 재고 관리 (v2 신규)
// ============================================================
function InventoryPanel({ showToast }) {
  const [menus, setMenus] = useState([]);
  const [stocks, setStocks] = useState({});
  const [details, setDetails] = useState(null);
  const [loading, setLoading] = useState(true);

  const loadAll = useCallback(async () => {
    const mr = await api("/menus");
    if (!mr.ok) return;
    setMenus(mr.data);
    const s = {};
    for (const m of mr.data) {
      const ir = await api(`/inventory/${m.id}`);
      if (ir.ok) s[m.id] = ir.data.availableQuantity;
    }
    setStocks(s);
    setLoading(false);
  }, []);

  useEffect(() => { loadAll(); }, [loadAll]);

  const showDetail = async (menuId) => {
    const r = await api(`/inventory/${menuId}/detail`);
    if (r.ok) setDetails({ menuId, data: r.data });
  };

  const addStock = async (menuId) => {
    const today = new Date().toISOString().split("T")[0];
    const exp = new Date(Date.now() + 30 * 86400000).toISOString().split("T")[0];
    const res = await api("/inventory", {
      method: "POST",
      body: JSON.stringify({ menuId, quantity: 50, receivedDate: today, expirationDate: exp }),
    });
    if (res.ok) {
      showToast("50개 입고 완료!");
      loadAll();
    } else showToast(res.data?.message || "입고 실패", "error");
  };

  if (loading) return <Loader />;

  return (
    <div>
      <Title icon="📦" title="재고 관리" sub="비관적 락(SELECT FOR UPDATE) + FIFO 차감" />

      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        {menus.map(m => {
          const qty = stocks[m.id] ?? 0;
          const pct = Math.min(qty, 100);
          return (
            <div key={m.id} style={S.invRow}>
              <div style={{ flex: 1 }}>
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                  <span style={{ fontWeight: 700 }}>{EMOJIS[m.id % 5]} {m.name}</span>
                  <span style={{ fontWeight: 700, color: qty <= 10 ? "#c62828" : "#2e7d32" }}>{qty}개</span>
                </div>
                <div style={S.barTrack}>
                  <div style={{ ...S.barFill, width: `${pct}%`, background: qty <= 10 ? "#ef5350" : qty <= 30 ? "#ffa726" : "#66bb6a" }} />
                </div>
              </div>
              <div style={{ display: "flex", gap: 6, marginLeft: 12 }}>
                <button onClick={() => showDetail(m.id)} style={S.smallBtn}>상세</button>
                <button onClick={() => addStock(m.id)} style={{ ...S.smallBtn, ...S.smallBtnPrimary }}>+50 입고</button>
              </div>
            </div>
          );
        })}
      </div>

      {details && (
        <div style={S.detailBox}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
            <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700 }}>📋 재고 상세 (FIFO — 유통기한순)</h3>
            <button onClick={() => setDetails(null)} style={{ ...S.smallBtn, padding: "4px 12px" }}>닫기</button>
          </div>
          {details.data.length === 0 ? <Empty msg="재고 없음" /> : (
            <table style={S.table}>
              <thead>
                <tr style={S.tableHead}>
                  <th style={S.th}>입고일</th><th style={S.th}>유통기한</th>
                  <th style={S.th}>수량</th><th style={S.th}>상태</th>
                </tr>
              </thead>
              <tbody>
                {details.data.map(d => {
                  const expDate = new Date(d.expirationDate);
                  const daysLeft = Math.ceil((expDate - new Date()) / 86400000);
                  return (
                    <tr key={d.id} style={S.tableRow}>
                      <td style={S.td}>{d.receivedDate}</td>
                      <td style={S.td}>
                        {d.expirationDate}
                        {daysLeft <= 7 && <span style={S.expWarn}> (D-{daysLeft})</span>}
                      </td>
                      <td style={S.td}>{d.quantity}개</td>
                      <td style={S.td}>
                        <span style={{ ...S.statusBadge, ...(d.status === "AVAILABLE" ? S.statusOk : S.statusDead) }}>
                          {d.status === "AVAILABLE" ? "사용가능" : "폐기"}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}

// ============================================================
// 분석 대시보드 (v2 Kafka Consumer 집계)
// ============================================================
function AnalyticsDashboard() {
  const [popular, setPopular] = useState([]);
  const [hourly, setHourly] = useState([]);
  const [daily, setDaily] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const today = new Date().toISOString().split("T")[0];
    const weekAgo = new Date(Date.now() - 7 * 86400000).toISOString().split("T")[0];
    Promise.all([
      api(`/analytics/popular-menus?from=${weekAgo}&to=${today}`),
      api(`/analytics/hourly?from=${weekAgo}&to=${today}`),
      api(`/analytics/daily-revenue?from=${weekAgo}&to=${today}`),
    ]).then(([p, h, d]) => {
      if (p.ok) setPopular(p.data);
      if (h.ok) setHourly(h.data);
      if (d.ok) setDaily(d.data);
      setLoading(false);
    });
  }, []);

  if (loading) return <Loader />;
  const maxH = Math.max(...hourly.map(h => h.orderCount), 1);
  const maxD = Math.max(...daily.map(d => d.revenue), 1);
  const maxP = Math.max(...popular.map(p => p.totalRevenue), 1);

  return (
    <div>
      <Title icon="📊" title="데이터 분석" sub="Kafka Consumer → order_analytics 집계 (최근 7일)" />

      <div style={S.aCard}>
        <h3 style={S.aTitle}>🏆 메뉴별 매출</h3>
        {popular.length === 0 ? <Empty msg="분석 데이터가 없습니다" /> :
          popular.map(m => (
            <div key={m.menuId} style={S.barRow}>
              <span style={S.barLabel}>{m.menuName}</span>
              <div style={S.barTrack}>
                <div style={{ ...S.barFill, width: `${(m.totalRevenue / maxP) * 100}%` }} />
              </div>
              <span style={S.barVal}>{m.totalRevenue.toLocaleString()}원</span>
            </div>
          ))}
      </div>

      <div style={S.aCard}>
        <h3 style={S.aTitle}>⏰ 시간대별 주문</h3>
        {hourly.length === 0 ? <Empty msg="분석 데이터가 없습니다" /> : (
          <div style={S.chartArea}>
            {hourly.map(h => (
              <div key={h.hour} style={S.chartCol}>
                <div style={S.chartBarWrap}>
                  <div style={{ ...S.chartBar, height: `${(h.orderCount / maxH) * 100}%` }} />
                </div>
                <span style={S.chartLabel}>{h.hour}시</span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div style={S.aCard}>
        <h3 style={S.aTitle}>📈 일별 매출 추이</h3>
        {daily.length === 0 ? <Empty msg="분석 데이터가 없습니다" /> :
          daily.map(d => (
            <div key={d.date} style={S.barRow}>
              <span style={S.barLabel}>{d.date.slice(5)}</span>
              <div style={S.barTrack}>
                <div style={{ ...S.barFill, ...S.barBlue, width: `${(d.revenue / maxD) * 100}%` }} />
              </div>
              <span style={S.barVal}>{d.revenue.toLocaleString()}원</span>
            </div>
          ))}
      </div>
    </div>
  );
}

// ============================================================
// Shared
// ============================================================
const EMOJIS = ["☕", "🥛", "🍵", "🧋", "🍫"];
function Title({ icon, title, sub }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 24 }}>
      <span style={{ fontSize: 28 }}>{icon}</span>
      <div>
        <h2 style={{ margin: 0, fontSize: 22, fontWeight: 800 }}>{title}</h2>
        <p style={{ margin: 0, fontSize: 12, color: "#8b7355", fontFamily: "monospace" }}>{sub}</p>
      </div>
    </div>
  );
}
function Loader() { return <div style={{ textAlign: "center", padding: 60, color: "#8b7355", fontSize: 18 }}>☕ 로딩 중...</div>; }
function Empty({ msg }) { return <p style={{ textAlign: "center", padding: 40, color: "#8b7355" }}>{msg}</p>; }

// ============================================================
// Styles
// ============================================================
const S = {
  app: { fontFamily: "'Pretendard','Apple SD Gothic Neo',sans-serif", background: "#faf6f1", minHeight: "100vh", color: "#3d2c1e" },
  header: { background: "linear-gradient(135deg,#3d2c1e,#5c3d2e)", color: "#fff", padding: "20px 0", boxShadow: "0 4px 20px rgba(61,44,30,.3)" },
  headerInner: { maxWidth: 940, margin: "0 auto", padding: "0 20px", display: "flex", justifyContent: "space-between", alignItems: "center" },
  logo: { margin: 0, fontSize: 28, fontWeight: 800, letterSpacing: -1 },
  version: { fontSize: 13, background: "#d4a574", color: "#3d2c1e", padding: "2px 8px", borderRadius: 6, marginLeft: 8, fontWeight: 700, verticalAlign: "middle" },
  subtitle: { margin: 0, fontSize: 11, opacity: 0.5, letterSpacing: 1.5, fontFamily: "monospace" },
  userArea: { display: "flex", alignItems: "center", gap: 12 },
  userSelect: { padding: "8px 12px", borderRadius: 8, border: "1px solid rgba(255,255,255,.2)", background: "rgba(255,255,255,.1)", color: "#fff", fontSize: 14 },
  balanceBadge: { background: "#d4a574", color: "#3d2c1e", padding: "8px 16px", borderRadius: 20, fontWeight: 700, fontSize: 15 },
  nav: { maxWidth: 940, margin: "0 auto", padding: "14px 20px 0", display: "flex", gap: 4, overflowX: "auto" },
  navBtn: { padding: "9px 14px", borderRadius: 10, border: "none", background: "transparent", color: "#8b7355", fontSize: 13, fontWeight: 600, cursor: "pointer", display: "flex", alignItems: "center", gap: 5, whiteSpace: "nowrap", transition: "all .2s" },
  navActive: { background: "#3d2c1e", color: "#fff", boxShadow: "0 2px 8px rgba(61,44,30,.3)" },
  main: { maxWidth: 940, margin: "0 auto", padding: "20px 20px 40px" },
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(155px,1fr))", gap: 12 },
  card: { background: "#fff", borderRadius: 14, padding: 18, textAlign: "center", boxShadow: "0 2px 10px rgba(61,44,30,.05)", border: "2px solid transparent", transition: "all .2s" },
  selectable: { cursor: "pointer" },
  selected: { border: "2px solid #d4a574", boxShadow: "0 4px 18px rgba(212,165,116,.3)", transform: "translateY(-2px)" },
  disabled: { opacity: 0.45, cursor: "not-allowed" },
  emoji: { fontSize: 34, marginBottom: 6 },
  cardTitle: { margin: "0 0 4px", fontSize: 15, fontWeight: 700 },
  price: { margin: 0, fontSize: 15, color: "#d4a574", fontWeight: 700 },
  stockBadge: { display: "inline-block", marginTop: 8, padding: "3px 10px", borderRadius: 10, fontSize: 11, fontWeight: 600, background: "#e8f5e9", color: "#2e7d32" },
  stockOut: { background: "#ffebee", color: "#c62828" },
  orderBar: { marginTop: 20, padding: 18, borderRadius: 14, background: "#fff", boxShadow: "0 4px 16px rgba(61,44,30,.07)", display: "flex", justifyContent: "space-between", alignItems: "center" },
  orderBtn: { padding: "12px 30px", borderRadius: 12, border: "none", background: "#3d2c1e", color: "#fff", fontSize: 15, fontWeight: 700, cursor: "pointer" },
  popCard: { background: "#fff", borderRadius: 14, padding: "14px 18px", display: "flex", alignItems: "center", gap: 14, boxShadow: "0 2px 10px rgba(61,44,30,.05)" },
  pointCard: { background: "linear-gradient(135deg,#3d2c1e,#5c3d2e)", borderRadius: 18, padding: 30, textAlign: "center", color: "#fff", marginBottom: 20 },
  chargeBox: { background: "#fff", borderRadius: 14, padding: 22, boxShadow: "0 2px 10px rgba(61,44,30,.05)" },
  presetGrid: { display: "grid", gridTemplateColumns: "repeat(5,1fr)", gap: 8, marginBottom: 14 },
  presetBtn: { padding: "10px 0", borderRadius: 10, border: "2px solid #e8ddd0", background: "#fff", fontSize: 14, fontWeight: 600, cursor: "pointer", color: "#5c3d2e" },
  presetActive: { border: "2px solid #d4a574", background: "#fef7ef", color: "#3d2c1e" },
  input: { flex: 1, padding: "12px 16px", borderRadius: 12, border: "2px solid #e8ddd0", fontSize: 15, outline: "none", background: "#faf6f1" },
  chargeBtn: { padding: "12px 26px", borderRadius: 12, border: "none", background: "#d4a574", color: "#fff", fontSize: 15, fontWeight: 700, cursor: "pointer" },
  // Inventory
  invRow: { background: "#fff", borderRadius: 14, padding: "14px 18px", display: "flex", alignItems: "center", boxShadow: "0 2px 10px rgba(61,44,30,.05)" },
  smallBtn: { padding: "6px 14px", borderRadius: 8, border: "1px solid #e8ddd0", background: "#fff", fontSize: 12, fontWeight: 600, cursor: "pointer", color: "#5c3d2e" },
  smallBtnPrimary: { background: "#3d2c1e", color: "#fff", border: "none" },
  detailBox: { marginTop: 20, background: "#fff", borderRadius: 14, padding: 20, boxShadow: "0 2px 10px rgba(61,44,30,.05)" },
  table: { width: "100%", borderCollapse: "collapse" },
  tableHead: { borderBottom: "2px solid #e8ddd0" },
  th: { padding: "8px 12px", textAlign: "left", fontSize: 13, fontWeight: 700, color: "#8b7355" },
  tableRow: { borderBottom: "1px solid #f0e8df" },
  td: { padding: "10px 12px", fontSize: 14 },
  expWarn: { color: "#c62828", fontSize: 12, fontWeight: 700 },
  statusBadge: { padding: "3px 10px", borderRadius: 8, fontSize: 11, fontWeight: 700 },
  statusOk: { background: "#e8f5e9", color: "#2e7d32" },
  statusDead: { background: "#ffebee", color: "#c62828" },
  // Analytics
  aCard: { background: "#fff", borderRadius: 14, padding: 22, marginBottom: 14, boxShadow: "0 2px 10px rgba(61,44,30,.05)" },
  aTitle: { margin: "0 0 16px", fontSize: 16, fontWeight: 700 },
  barRow: { display: "flex", alignItems: "center", gap: 12, marginBottom: 10 },
  barLabel: { width: 80, fontSize: 13, fontWeight: 600, textAlign: "right" },
  barTrack: { flex: 1, height: 22, background: "#f5efe8", borderRadius: 11, overflow: "hidden" },
  barFill: { height: "100%", background: "linear-gradient(90deg,#d4a574,#c4915e)", borderRadius: 11, transition: "width .6s", minWidth: 3 },
  barBlue: { background: "linear-gradient(90deg,#7daecc,#5a93b5)" },
  barVal: { width: 90, fontSize: 13, fontWeight: 600, textAlign: "right", color: "#8b7355" },
  chartArea: { display: "flex", alignItems: "flex-end", gap: 4, height: 130, padding: "0 4px" },
  chartCol: { flex: 1, display: "flex", flexDirection: "column", alignItems: "center" },
  chartBarWrap: { width: "100%", height: 100, display: "flex", alignItems: "flex-end" },
  chartBar: { width: "100%", background: "linear-gradient(180deg,#d4a574,#e8c9a0)", borderRadius: "4px 4px 0 0", transition: "height .6s", minHeight: 2 },
  chartLabel: { fontSize: 10, color: "#8b7355", marginTop: 4 },
  toast: { position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: "#3d2c1e", color: "#fff", padding: "14px 28px", borderRadius: 14, fontSize: 15, fontWeight: 600, boxShadow: "0 8px 30px rgba(0,0,0,.2)", zIndex: 1000 },
  toastErr: { background: "#c62828" },
};
