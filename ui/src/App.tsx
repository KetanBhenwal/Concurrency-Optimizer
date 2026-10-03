import { useState } from 'react';
import {
  Activity,
  AlertTriangle,
  ArrowDownToLine,
  ArrowRight,
  Check,
  CheckCircle2,
  CircleHelp,
  Clock3,
  Copy,
  ExternalLink,
  HeartPulse,
  LoaderCircle,
  LockKeyhole,
  Plus,
  RotateCw,
  Send,
  ShieldCheck,
  SquareTerminal,
  TicketCheck,
  X,
  Zap,
} from 'lucide-react';

type Seat = { seat: string; status: string };
type Counts = { total: number; available: number; held: number; confirmed: number };
type Show = {
  id: string;
  name: string;
  price_paise: number;
  per_user_limit: number;
  counts: Counts;
  seats: Seat[];
};
type Reservation = {
  reservation_id: string;
  show_id: string;
  user_id: string;
  seats: string[];
  amount_paise: number;
  status: string;
};
type ApiResult = { ok: boolean; status: number; data: unknown; requestId: string; duration: number };
type EventItem = { label: string; status: number; requestId: string; at: string };

const starterSeats = Array.from({ length: 24 }, (_, index) => {
  const row = String.fromCharCode(65 + Math.floor(index / 8));
  return `${row}${String(index % 8 + 1).padStart(2, '0')}`;
}).join(', ');

function newKey() {
  return `ui-${crypto.randomUUID()}`;
}

function parseSeats(value: string) {
  return value.split(/[\s,]+/).map((seat) => seat.trim()).filter(Boolean);
}

function formatPaise(paise: number) {
  return `₹${(paise / 100).toFixed(2)}`;
}

function App() {
  const [apiBase, setApiBase] = useState(localStorage.getItem('seat-ui-api') ?? '');
  const [userId, setUserId] = useState('user-01');
  const [showName, setShowName] = useState('Friday night');
  const [seatInput, setSeatInput] = useState(starterSeats);
  const [pricePaise, setPricePaise] = useState('25000');
  const [userLimit, setUserLimit] = useState('4');
  const [showId, setShowId] = useState(localStorage.getItem('seat-ui-show') ?? '');
  const [show, setShow] = useState<Show | null>(null);
  const [selectedSeats, setSelectedSeats] = useState<string[]>([]);
  const [reserveSeatsInput, setReserveSeatsInput] = useState('');
  const [idempotencyKey, setIdempotencyKey] = useState(newKey);
  const [injectIdentity, setInjectIdentity] = useState(false);
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [events, setEvents] = useState<EventItem[]>([]);
  const [latestResponse, setLatestResponse] = useState<unknown>(null);
  const [metricsText, setMetricsText] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('Create a show or load one by ID to begin.');
  const [burstSize, setBurstSize] = useState('30');

  const apiUrl = (path: string) => `${apiBase.trim().replace(/\/$/, '')}${path}`;

  async function callApi(path: string, init: RequestInit = {}, inspect = true): Promise<ApiResult> {
    const requestId = `ui-${crypto.randomUUID()}`;
    const started = performance.now();
    let responseStatus = 0;
    let data: unknown;
    try {
      const response = await fetch(apiUrl(path), {
        ...init,
        headers: {
          'X-Request-ID': requestId,
          ...(init.headers ?? {}),
        },
      });
      responseStatus = response.status;
      const contentType = response.headers.get('content-type') ?? '';
      data = contentType.includes('json') ? await response.json() : await response.text();
      const duration = Math.round(performance.now() - started);
      const responseId = response.headers.get('x-request-id') ?? requestId;
      if (inspect) {
        setLatestResponse({
          request: { method: init.method ?? 'GET', path, request_id: requestId },
          status: response.status,
          response_request_id: responseId,
          duration_ms: duration,
          body: data,
        });
      }
      setEvents((current) => [{ label: `${init.method ?? 'GET'} ${path}`, status: response.status, requestId: responseId, at: new Date().toLocaleTimeString() }, ...current].slice(0, 60));
      return { ok: response.ok, status: response.status, data, requestId: responseId, duration };
    } catch (error) {
      const duration = Math.round(performance.now() - started);
      data = { error: error instanceof Error ? error.message : 'Network request failed' };
      if (inspect) setLatestResponse({ request: { method: init.method ?? 'GET', path, request_id: requestId }, status: 'network error', duration_ms: duration, body: data });
      setEvents((current) => [{ label: `${init.method ?? 'GET'} ${path}`, status: 0, requestId, at: new Date().toLocaleTimeString() }, ...current].slice(0, 60));
      return { ok: false, status: responseStatus, data, requestId, duration };
    }
  }

  async function refreshShow(id = showId, announce = true, inspect = true) {
    if (!id.trim()) {
      setNotice('Enter a show ID or create a show first.');
      return null;
    }
    const result = await callApi(`/shows/${encodeURIComponent(id.trim())}`, {}, inspect);
    if (result.ok && typeof result.data === 'object' && result.data !== null && 'seats' in result.data) {
      const nextShow = result.data as Show;
      setShow(nextShow);
      setShowId(nextShow.id);
      localStorage.setItem('seat-ui-show', nextShow.id);
      setReserveSeatsInput((current) => current || (nextShow.seats.find((seat) => seat.status.toLowerCase() === 'available')?.seat ?? ''));
      const counts = nextShow.counts;
      const reconciles = counts.available + counts.held + counts.confirmed === counts.total;
      if (announce) setNotice(reconciles ? `State refreshed · ${counts.confirmed} confirmed of ${counts.total}` : 'Reconciliation failed: seat counts do not add up.');
      return nextShow;
    }
    if (announce) setNotice('Could not load this show. Check the response inspector for the API error.');
    return null;
  }

  async function createShow(event: React.FormEvent) {
    event.preventDefault();
    const seats = parseSeats(seatInput);
    if (!seats.length) {
      setNotice('Add at least one seat.');
      return;
    }
    setBusy(true);
    const result = await callApi('/shows', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: showName, seats, price_paise: Number(pricePaise), per_user_limit: Number(userLimit) }),
    });
    if (result.ok && typeof result.data === 'object' && result.data !== null && 'id' in result.data) {
      const created = result.data as Show;
      setShowId(created.id);
      localStorage.setItem('seat-ui-show', created.id);
      setShow(created);
      setSelectedSeats([]);
      setReserveSeatsInput('');
      setReservations([]);
      setNotice(`Show created · ${created.id}`);
    } else {
      setNotice('Show creation failed. See the response inspector.');
    }
    setBusy(false);
  }

  async function reserve(seats = parseSeats(reserveSeatsInput), key = idempotencyKey, user = userId, refreshAfter = true) {
    if (!showId.trim()) {
      setNotice('Load or create a show before reserving.');
      return null;
    }
    const headers: Record<string, string> = { 'Idempotency-Key': key, 'Content-Type': 'application/json' };
    if (user.trim()) headers.Authorization = `Bearer ${user.trim()}`;
    const result = await callApi(`/shows/${encodeURIComponent(showId)}/reserve`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ seats, ...(injectIdentity ? { user_id: 'spoofed-body-user' } : {}) }),
    });
    if (result.ok && typeof result.data === 'object' && result.data !== null && 'reservation_id' in result.data) {
      const reservation = result.data as Reservation;
      setReservations((current) => [reservation, ...current.filter((item) => item.reservation_id !== reservation.reservation_id)].slice(0, 20));
      setNotice(`${result.status === 200 ? 'Idempotent replay' : 'Reservation confirmed'} · ${reservation.reservation_id}`);
    } else {
      const code = (result.data as { error?: { code?: string } })?.error?.code;
      setNotice(`${result.status || 'Network'}${code ? ` · ${code}` : ''} · request ${result.requestId}`);
    }
    if (refreshAfter) await refreshShow(showId, false, false);
    return result;
  }

  async function cancelReservation(reservation: Reservation, user = userId) {
    const headers: Record<string, string> = {};
    if (user.trim()) headers.Authorization = `Bearer ${user.trim()}`;
    const result = await callApi(`/reservations/${encodeURIComponent(reservation.reservation_id)}/cancel`, { method: 'POST', headers });
    if (result.ok) {
      setReservations((current) => current.map((item) => item.reservation_id === reservation.reservation_id ? { ...item, status: 'cancelled' } : item));
      setNotice(`Cancellation returned ${result.status} · ${reservation.reservation_id}`);
    } else {
      setNotice(`Cancellation returned ${result.status || 'network error'} · inspect response`);
    }
    await refreshShow(showId, false, false);
    return result;
  }

  async function runHotSeatRace() {
    const target = show?.seats.find((seat) => seat.status.toLowerCase() === 'available')?.seat;
    if (!target) return setNotice('No available seat to use for the hot-seat race.');
    const total = Math.max(2, Math.min(250, Number(burstSize) || 30));
    setBusy(true);
    setNotice(`Sending ${total} concurrent users to ${target}...`);
    const results = await Promise.all(Array.from({ length: total }, (_, index) => reserve([target], `hot-${crypto.randomUUID()}`, `hot-user-${index}-${crypto.randomUUID()}`, false)));
    const created = results.filter((result) => result?.status === 201).length;
    const conflicts = results.filter((result) => result?.status === 409).length;
    const unexpected = results.filter((result) => !result || (result.status !== 201 && result.status !== 409)).length;
    await refreshShow();
    setNotice(`Hot seat ${target}: ${created} created · ${conflicts} conflicts · ${unexpected} other. Expected 1 / ${total - 1} / 0.`);
    setBusy(false);
  }

  async function runUserLimitRace() {
    const open = show?.seats.filter((seat) => seat.status.toLowerCase() === 'available') ?? [];
    const total = Math.min(10, open.length);
    if (total < 2) return setNotice('Need at least two available seats for the per-user race.');
    const sharedUser = `limit-user-${crypto.randomUUID()}`;
    setBusy(true);
    setNotice(`Sending ${total} concurrent seat requests as ${sharedUser}...`);
    const results = await Promise.all(open.slice(0, total).map((seat, index) => reserve([seat.seat], `limit-${index}-${crypto.randomUUID()}`, sharedUser, false)));
    const created = results.filter((result) => result?.status === 201).length;
    const conflicts = results.filter((result) => result?.status === 409).length;
    await refreshShow();
    setNotice(`Per-user race: ${created} created · ${conflicts} conflicts · configured limit ${show?.per_user_limit}.`);
    setBusy(false);
  }

  async function runIdempotencyRace() {
    const target = show?.seats.find((seat) => seat.status.toLowerCase() === 'available')?.seat;
    if (!target) return setNotice('No available seat to use for the idempotency race.');
    const total = Math.max(2, Math.min(100, Number(burstSize) || 10));
    const sharedKey = newKey();
    const sharedUser = `idem-user-${crypto.randomUUID()}`;
    setBusy(true);
    setNotice(`Sending ${total} identical requests with one idempotency key...`);
    const results = await Promise.all(Array.from({ length: total }, () => reserve([target], sharedKey, sharedUser, false)));
    const created = results.filter((result) => result?.status === 201).length;
    const replayed = results.filter((result) => result?.status === 200).length;
    const ids = results.map((result) => (result?.data as Reservation | undefined)?.reservation_id).filter(Boolean);
    const uniqueIds = new Set(ids).size;
    await refreshShow();
    setNotice(`Idempotency race: ${created} created · ${replayed} replays · ${uniqueIds} reservation ID(s). Expected 1 / ${total - 1} / 1.`);
    setBusy(false);
  }

  async function runCancellationRace() {
    const active = reservations.find((reservation) => reservation.status.toLowerCase() === 'confirmed');
    if (!active) return setNotice('Create a reservation in this session first to run a cancellation race.');
    const otherUser = `race-user-${crypto.randomUUID()}`;
    setBusy(true);
    setNotice(`Racing cancellation against a new reservation for ${active.seats.join(', ')}...`);
    const [cancelResult, reserveResult] = await Promise.all([
      cancelReservation(active, active.user_id),
      reserve(active.seats, newKey(), otherUser),
    ]);
    const state = await refreshShow();
    const finalSeats = state?.seats.filter((seat) => active.seats.includes(seat.seat)) ?? [];
    const reconciles = state ? state.counts.available + state.counts.held + state.counts.confirmed === state.counts.total : false;
    setNotice(`Cancel ${cancelResult?.status ?? 'error'} · reserve ${reserveResult?.status ?? 'error'} · ${finalSeats.map((seat) => `${seat.seat}:${seat.status}`).join(', ')} · reconciliation ${reconciles ? 'PASS' : 'FAIL'}.`);
    setBusy(false);
  }

  async function checkHealth(path: string) {
    const result = await callApi(path);
    setNotice(`${path}: ${result.status || 'network error'} · ${result.duration} ms`);
  }

  async function loadMetrics() {
    const result = await callApi('/metrics');
    if (result.ok && typeof result.data === 'string') setMetricsText(result.data);
    else setMetricsText('Metrics request failed. See response inspector.');
  }

  function saveApiBase(value: string) {
    setApiBase(value);
    localStorage.setItem('seat-ui-api', value);
  }

  function toggleSeat(seat: string) {
    setSelectedSeats((current) => current.includes(seat) ? current.filter((item) => item !== seat) : [...current, seat]);
    setReserveSeatsInput((current) => {
      const items = parseSeats(current);
      return items.includes(seat) ? items.filter((item) => item !== seat).join(', ') : [...items, seat].join(', ');
    });
  }

  const metrics = metricsText.split('\n').filter((line) => /reservations_(confirmed|declined|idempotent)|seats_available|http_server_requests|jvm_memory_used/.test(line) && !line.startsWith('#')).slice(0, 16);
  const reconciliation = show ? show.counts.available + show.counts.held + show.counts.confirmed === show.counts.total : null;
  const seatRows = show ? [...new Set(show.seats.map((seat) => seat.seat.match(/^[A-Za-z]+/)?.[0] ?? 'Other'))] : [];

  return (
    <div className="app-shell">
      <aside className="rail">
        <div className="brand-mark"><TicketCheck size={20} strokeWidth={2.3} /></div>
        <div className="rail-rule" />
        <a className="rail-link active" href="#show" aria-label="Show setup" title="Show setup"><Activity size={18} /></a>
        <a className="rail-link" href="#reservation" aria-label="Reservation" title="Reservation"><LockKeyhole size={18} /></a>
        <a className="rail-link" href="#load-lab" aria-label="Load lab" title="Load lab"><Zap size={18} /></a>
        <a className="rail-link" href="#diagnostics" aria-label="Diagnostics" title="Diagnostics"><HeartPulse size={18} /></a>
        <div className="rail-bottom"><CircleHelp size={17} /></div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="topbar-title"><span className="eyebrow">SEAT RESERVATION SERVICE</span><h1>Test console<span className="title-dot">.</span></h1></div>
          <div className="connection-control">
            <span className="connection-dot" />
            <label htmlFor="api-base">API</label>
            <input id="api-base" value={apiBase} onChange={(event) => saveApiBase(event.target.value)} placeholder="Local proxy :8080" aria-label="API base URL" />
            <button className="icon-button" onClick={() => refreshShow()} title="Refresh show state" aria-label="Refresh show state"><RotateCw size={16} /></button>
          </div>
        </header>

        <div className="workspace">
          <section className="primary-column">
            <section className="panel show-panel" id="show">
              <div className="panel-heading">
                <div><span className="section-index">01 / INVENTORY</span><h2>Show setup</h2></div>
                <span className={`state-pill ${show ? 'connected' : ''}`}><span />{show ? 'Show loaded' : 'No show selected'}</span>
              </div>

              <div className="show-id-row">
                <div className="field grow"><label htmlFor="show-id">SHOW ID</label><input id="show-id" value={showId} onChange={(event) => setShowId(event.target.value)} placeholder="Create a show or paste its UUID" /></div>
                <button className="button button-quiet load-button" onClick={() => refreshShow()}><ArrowDownToLine size={15} /> Load</button>
              </div>

              <form onSubmit={createShow} className="create-form">
                <div className="form-grid">
                  <div className="field"><label htmlFor="show-name">SHOW NAME</label><input id="show-name" value={showName} onChange={(event) => setShowName(event.target.value)} /></div>
                  <div className="field"><label htmlFor="price">PRICE · PAISE</label><input id="price" inputMode="numeric" type="number" min="0" value={pricePaise} onChange={(event) => setPricePaise(event.target.value)} /></div>
                  <div className="field"><label htmlFor="limit">PER-USER LIMIT</label><input id="limit" inputMode="numeric" type="number" min="1" value={userLimit} onChange={(event) => setUserLimit(event.target.value)} /></div>
                </div>
                <div className="field seat-definition"><label htmlFor="seat-definitions">SEAT INVENTORY <span>comma or space separated</span></label><textarea id="seat-definitions" rows={2} value={seatInput} onChange={(event) => setSeatInput(event.target.value)} /></div>
                <button className="button button-primary" type="submit" disabled={busy}><Plus size={16} /> Create show</button>
              </form>

              <div className="show-summary">
                <div><span className="eyebrow">ACTIVE SHOW</span><strong>{show?.name ?? 'Waiting for a show'}</strong><small>{show ? `${formatPaise(show.price_paise)} per seat · limit ${show.per_user_limit}` : 'Create one above, or load by ID.'}</small></div>
                {show && <div className={`reconcile-chip ${reconciliation ? 'pass' : 'fail'}`}><span>{reconciliation ? <Check size={13} /> : <X size={13} />}</span>{reconciliation ? 'Counts reconcile' : 'Count mismatch'}</div>}
              </div>
            </section>

            <section className="panel inventory-panel">
              <div className="panel-heading inventory-heading">
                <div><span className="section-index">02 / LIVE STATE</span><h2>Seat inventory</h2></div>
                {show && <button className="icon-button" onClick={() => refreshShow()} title="Reload inventory" aria-label="Reload inventory"><RotateCw size={16} /></button>}
              </div>
              {show ? <>
                <div className="count-strip">
                  <div><span>TOTAL</span><strong>{show.counts.total}</strong></div>
                  <div><span>AVAILABLE</span><strong className="count-green">{show.counts.available}</strong></div>
                  <div><span>HELD</span><strong>{show.counts.held}</strong></div>
                  <div><span>CONFIRMED</span><strong className="count-coral">{show.counts.confirmed}</strong></div>
                  <div className="count-equation">{show.counts.available} + {show.counts.held} + {show.counts.confirmed} = {show.counts.total}</div>
                </div>
                <div className="seat-map">
                  {seatRows.map((row) => <div className="seat-row" key={row}><span className="row-label">{row}</span><div className="seat-list">
                    {show.seats.filter((seat) => (seat.seat.match(/^[A-Za-z]+/)?.[0] ?? 'Other') === row).map((seat) => {
                      const status = seat.status.toLowerCase();
                      const selected = selectedSeats.includes(seat.seat);
                      return <button key={seat.seat} className={`seat seat-${status} ${selected ? 'seat-selected' : ''}`} onClick={() => toggleSeat(seat.seat)} title={`${seat.seat} · ${status}`} aria-label={`${seat.seat}, ${status}${selected ? ', selected' : ''}`}><span>{seat.seat.replace(row, '')}</span></button>;
                    })}
                  </div></div>)}
                </div>
                <div className="legend"><span><i className="legend-available" />Available</span><span><i className="legend-confirmed" />Confirmed</span><span><i className="legend-selected" />Request seats</span><small>Click a seat to add or remove it from the request.</small></div>
              </> : <div className="empty-state"><div className="empty-icon"><TicketCheck size={22} /></div><strong>Inventory appears here</strong><span>Seat state is always read from the service.</span></div>}
            </section>

            <section className="panel reservation-panel" id="reservation">
              <div className="panel-heading">
                <div><span className="section-index">03 / TRANSACTION</span><h2>Reservation request</h2></div>
                <span className="method-tag">POST <b>/reserve</b></span>
              </div>
              <div className="form-grid request-grid">
                <div className="field"><label htmlFor="user-id">BEARER USER TOKEN <span>blank to test 401</span></label><input id="user-id" value={userId} onChange={(event) => setUserId(event.target.value)} placeholder="user-01" /></div>
                <div className="field"><label htmlFor="idempotency-key">IDEMPOTENCY KEY</label><div className="input-with-action"><input id="idempotency-key" value={idempotencyKey} onChange={(event) => setIdempotencyKey(event.target.value)} /><button className="mini-action" onClick={() => setIdempotencyKey(newKey())} title="Generate a new key" aria-label="Generate a new key"><RotateCw size={14} /></button></div></div>
              </div>
              <div className="field request-seat-field"><label htmlFor="request-seats">REQUESTED SEATS <span>edit freely to probe unavailable or invalid seats</span></label><input id="request-seats" value={reserveSeatsInput} onChange={(event) => setReserveSeatsInput(event.target.value)} placeholder="A01, A02" /></div>
              <label className="check-row"><input type="checkbox" checked={injectIdentity} onChange={(event) => setInjectIdentity(event.target.checked)} /><span>Include a spoofed <code>user_id</code> in the body to verify token-derived identity</span></label>
              <div className="request-actions"><button className="button button-primary" onClick={() => reserve()} disabled={busy}><Send size={15} /> Reserve seats</button><button className="button button-quiet" onClick={() => reserve()} disabled={busy}><RotateCw size={15} /> Retry same key</button><button className="button button-quiet" onClick={() => setIdempotencyKey(newKey())}><Plus size={15} /> New key</button></div>
              {reservations.length > 0 && <div className="reservation-list"><div className="list-heading">SESSION RESERVATIONS <span>{reservations.length}</span></div>{reservations.map((reservation) => <div className="reservation-item" key={reservation.reservation_id}><div className="reservation-icon"><TicketCheck size={15} /></div><div className="reservation-info"><strong>{reservation.seats.join(', ')}</strong><small>{reservation.user_id} · {reservation.reservation_id.slice(0, 8)}… · {formatPaise(reservation.amount_paise)}</small></div><span className={`reservation-status ${reservation.status.toLowerCase()}`}>{reservation.status}</span><button className="text-action" onClick={() => cancelReservation(reservation)} disabled={busy || reservation.status.toLowerCase() === 'cancelled'}>Cancel</button></div>)}</div>}
            </section>

            <section className="panel load-panel" id="load-lab">
              <div className="panel-heading">
                <div><span className="section-index">04 / CONTENTION</span><h2>Concurrency lab</h2></div>
                <span className="lab-mark"><Zap size={14} /> REAL REQUESTS</span>
              </div>
              <p className="panel-copy">Each probe sends concurrent HTTP requests to the live service, then reloads the authoritative seat state.</p>
              <div className="burst-settings"><div className="field"><label htmlFor="burst-size">REQUEST COUNT</label><input id="burst-size" type="number" min="2" max="250" value={burstSize} onChange={(event) => setBurstSize(event.target.value)} /></div><span>Browser runner capped at 250 requests per race.</span></div>
              <div className="probe-grid">
                <button className="probe-button" onClick={runHotSeatRace} disabled={busy || !show}><span className="probe-number">A</span><span><strong>Hot-seat contention</strong><small>Unique users · one seat · expect 1 win</small></span><ArrowRight size={15} /></button>
                <button className="probe-button" onClick={runUserLimitRace} disabled={busy || !show}><span className="probe-number">B</span><span><strong>Per-user limit</strong><small>One user · different seats · expect limit wins</small></span><ArrowRight size={15} /></button>
                <button className="probe-button" onClick={runIdempotencyRace} disabled={busy || !show}><span className="probe-number">C</span><span><strong>Idempotency race</strong><small>Same user, key and body · expect one ID</small></span><ArrowRight size={15} /></button>
                <button className="probe-button" onClick={runCancellationRace} disabled={busy || !show}><span className="probe-number">D</span><span><strong>Cancel + reserve race</strong><small>Competing ownership · verify final state</small></span><ArrowRight size={15} /></button>
              </div>
              {busy && <div className="busy-line"><LoaderCircle size={14} /> Running requests and syncing inventory…</div>}
              <div className="manual-checks"><span>Manual probes</span><p>Select multiple seats and include an unavailable one to test all-or-nothing rollback. Retry a key with changed seats for <code>409 IDEMPOTENCY_KEY_REUSED</code>. Change the token before cancellation to test ownership.</p></div>
            </section>
          </section>

          <aside className="secondary-column">
            <section className="diagnostics-panel" id="diagnostics">
              <div className="panel-heading">
                <div><span className="section-index">05 / OPERATIONS</span><h2>Service checks</h2></div>
                <ShieldCheck size={18} className="heading-icon" />
              </div>
              <div className="health-actions">
                <button onClick={() => checkHealth('/health/live')}><span className="health-icon live"><HeartPulse size={16} /></span><span><strong>Liveness</strong><small>/health/live</small></span><ArrowRight size={14} /></button>
                <button onClick={() => checkHealth('/health/ready')}><span className="health-icon ready"><CheckCircle2 size={16} /></span><span><strong>Readiness</strong><small>/health/ready · DB ping</small></span><ArrowRight size={14} /></button>
                <button onClick={loadMetrics}><span className="health-icon metrics-icon"><Activity size={16} /></span><span><strong>Prometheus metrics</strong><small>/metrics</small></span><ArrowRight size={14} /></button>
              </div>
              <div className="metrics-preview"><div className="list-heading">SELECTED SERIES <span>{metrics.length}</span></div>{metrics.length ? metrics.map((line, index) => <code key={`${line}-${index}`}>{line}</code>) : <div className="metrics-empty">Load metrics to inspect reservation counters, decline reasons and seat gauges.</div>}</div>
              {metrics.length > 0 && <div className="metric-note"><AlertTriangle size={13} /> Counters are process-local; compare with show state after a probe.</div>}
            </section>

            <section className="response-panel">
              <div className="panel-heading response-heading"><div><span className="section-index">06 / TRACE</span><h2>Response inspector</h2></div><SquareTerminal size={18} className="heading-icon" /></div>
              <div className="notice-line"><span className="notice-mark"><Activity size={13} /></span><span>{notice}</span></div>
              <div className="response-meta"><span>LAST RESPONSE</span>{Boolean(latestResponse) && <button className="copy-button" onClick={() => navigator.clipboard?.writeText(JSON.stringify(latestResponse, null, 2))} title="Copy response JSON" aria-label="Copy response JSON"><Copy size={13} /></button>}</div>
              <pre className="response-json">{latestResponse ? JSON.stringify(latestResponse, null, 2) : 'Responses include HTTP status, latency, request ID and error contract.'}</pre>
              <div className="activity-heading"><span>RECENT REQUESTS</span><span>{events.length}</span></div>
              <div className="event-list">{events.length ? events.slice(0, 8).map((event, index) => <div className="event-row" key={`${event.requestId}-${index}`}><span className={`event-status ${event.status >= 200 && event.status < 300 ? 'success' : event.status === 0 ? 'network' : 'failure'}`}>{event.status || 'ERR'}</span><span className="event-label" title={event.label}>{event.label}</span><span className="event-time">{event.at}</span></div>) : <div className="events-empty"><Clock3 size={14} /> API activity will appear here.</div>}</div>
            </section>

            <section className="scope-note">
              <div className="scope-icon"><CircleHelp size={16} /></div>
              <div><strong>Test scope</strong><p>This console drives the HTTP contract. Database outage, process restart persistence, Docker logs and the 20k load run remain environment-level checks.</p><code className="scope-command">./scripts/burst.sh http://localhost:8080</code></div>
            </section>
          </aside>
        </div>
        <footer className="footer"><span>LOCAL TEST SURFACE</span><span><i /> Transaction-backed state · Integer paise · Request IDs enabled</span><a href="http://localhost:8080/health/live" target="_blank" rel="noreferrer">Service :8080 <ExternalLink size={12} /></a></footer>
      </main>
    </div>
  );
}

export default App;