import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { Client } from "@stomp/stompjs";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "./style.css";

const API = "http://localhost:8080";

function MapView({ locations, requests }) {
  useEffect(() => {
    const el = document.getElementById("cras-map");
    if (!el) return;

    const map = L.map(el).setView([30.3165, 78.0322], 11);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: "&copy; OpenStreetMap contributors"
    }).addTo(map);

    locations.forEach(l => {
      if (l.latitude && l.longitude) {
        L.marker([l.latitude, l.longitude])
          .addTo(map)
          .bindPopup(`<b>${l.name}</b><br/>Risk level: ${l.riskLevel}/10`);
      }
    });

    requests.forEach(r => {
      const lat = r.location?.latitude;
      const lng = r.location?.longitude;
      if (lat && lng) {
        L.circleMarker([lat, lng], { radius: 8 })
          .addTo(map)
          .bindPopup(
            `<b>Request #${r.id}</b><br/>${r.resource?.name}<br/>Priority: ${r.priorityScore}`
          );
      }
    });

    return () => map.remove();
  }, [locations, requests]);

  return <div id="cras-map" className="map"></div>;
}

function Login({ onLogin }) {
  const [email, setEmail] = useState("user@cras.local");
  const [password, setPassword] = useState("User@123");
  const [message, setMessage] = useState("");

  async function submit(e) {
    e.preventDefault();
    const res = await fetch(`${API}/api/auth/login`, {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({email, password})
    });

    if (!res.ok) {
      setMessage("Login failed. Check email/password.");
      return;
    }

    onLogin(await res.json());
  }

  return (
    <div className="login">
      <div className="login-card">
        <h1>CRAS</h1>
        <p>Community Resource Allocation System</p>
        <form onSubmit={submit}>
          <input value={email} onChange={e => setEmail(e.target.value)} placeholder="Email"/>
          <input type="password" value={password}
                 onChange={e => setPassword(e.target.value)} placeholder="Password"/>
          <button>Login</button>
        </form>
        <div className="demo-box">
          <b>Demo accounts</b>
          <span>User: user@cras.local / User@123</span>
          <span>Manager: manager@cras.local / Manager@123</span>
          <span>Admin: admin@cras.local / Admin@123</span>
        </div>
        <small>{message}</small>
      </div>
    </div>
  );
}

function Stat({ label, value }) {
  return <div className="stat"><b>{value}</b><span>{label}</span></div>;
}

function App() {
  const [session, setSession] = useState(() => ({
    token: localStorage.getItem("cras_token"),
    role: localStorage.getItem("cras_role"),
    name: localStorage.getItem("cras_name")
  }));

  const [resources, setResources] = useState([]);
  const [requests, setRequests] = useState([]);
  const [locations, setLocations] = useState([]);
  const [dashboard, setDashboard] = useState({});
  const [audit, setAudit] = useState([]);
  const [allocations, setAllocations] = useState([]);
  const [users, setUsers] = useState([]);
  const [adminSummary, setAdminSummary] = useState({});
  const [message, setMessage] = useState("");
  const [tab, setTab] = useState("dashboard");
  const [selectedResource, setSelectedResource] = useState("");
  const [preemptId, setPreemptId] = useState("");

  const [form, setForm] = useState({
    resourceId: "",
    quantity: 10,
    severity: 5,
    affectedPeople: 10,
    scarcity: 5,
    locationId: ""
  });

  const headers = useMemo(() => ({
    Authorization: `Bearer ${session.token}`
  }), [session.token]);

  async function api(path, options = {}) {
    const res = await fetch(`${API}${path}`, {
      ...options,
      headers: {
        ...headers,
        ...(options.headers || {})
      }
    });

    if (res.status === 401 || res.status === 403) {
      throw new Error("Unauthorized");
    }

    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; }
    catch { data = text; }

    if (!res.ok) throw new Error(typeof data === "string" ? data : "Request failed");
    return data;
  }

  async function load() {
    try {
      const [r, q, loc] = await Promise.all([
        api("/api/resources"),
        api("/api/requests"),
        api("/api/locations")
      ]);
      setResources(r);
      setRequests(q);
      setLocations(loc);

      if (session.role !== "COMMUNITY_USER") {
        setDashboard(await api("/api/manager/dashboard"));
      }

      if (session.role === "ADMIN") {
        const [a, al, u, s] = await Promise.all([
          api("/api/admin/audit"),
          api("/api/admin/allocations"),
          api("/api/admin/users"),
          api("/api/admin/summary")
        ]);
        setAudit(a);
        setAllocations(al);
        setUsers(u);
        setAdminSummary(s);
      }
    } catch (e) {
      setMessage(e.message);
    }
  }

  useEffect(() => {
    if (!session.token) return;
    load();

    const client = new Client({
      brokerURL: "ws://localhost:8080/ws",
      reconnectDelay: 3000,
      onConnect: () => {
        client.subscribe("/topic/cras", () => load());
      }
    });
    client.activate();

    return () => client.deactivate();
  }, [session.token]);

  function saveSession(data) {
    localStorage.setItem("cras_token", data.accessToken);
    localStorage.setItem("cras_role", data.role);
    localStorage.setItem("cras_name", data.name);
    setSession({token: data.accessToken, role: data.role, name: data.name});
  }

  function logout() {
    localStorage.clear();
    setSession({token: null, role: null, name: null});
  }

  async function submitRequest(e) {
    e.preventDefault();
    try {
      await api("/api/requests", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          ...form,
          resourceId: Number(form.resourceId),
          locationId: form.locationId ? Number(form.locationId) : null
        })
      });
      setMessage("Request submitted and added to the priority queue.");
      await load();
    } catch (e) { setMessage(e.message); }
  }

  async function dispatch(resourceId) {
    try {
      const result = await api(`/api/manager/dispatch/${resourceId}`, {method: "POST"});
      setMessage(`Scheduler result: ${result.result}`);
      await load();
    } catch (e) { setMessage(e.message); }
  }

  async function allocate(id) {
    try {
      await api(`/api/manager/allocate/${id}`, {method: "POST"});
      setMessage(`Request #${id} allocated.`);
      await load();
    } catch (e) { setMessage(e.message); }
  }

  async function preempt() {
    if (!preemptId) return;
    try {
      await api(`/api/manager/preempt/${preemptId}`, {method: "POST"});
      setMessage(`Preemption executed for critical request #${preemptId}.`);
      setPreemptId("");
      await load();
    } catch (e) { setMessage(e.message); }
  }

  if (!session.token) return <Login onLogin={saveSession}/>;

  const isManager = session.role === "RESOURCE_MANAGER" || session.role === "ADMIN";
  const isAdmin = session.role === "ADMIN";

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>CRAS</h1>
          <span>Smart Community Resource Allocation System</span>
        </div>
        <div className="userbox">
          <span>{session.name} · {session.role}</span>
          <button className="danger" onClick={logout}>Logout</button>
        </div>
      </header>

      <nav className="tabs">
        <button onClick={() => setTab("dashboard")}>Dashboard</button>
        <button onClick={() => setTab("requests")}>Requests</button>
        {isManager && <button onClick={() => setTab("os")}>OS Scheduler</button>}
        <button onClick={() => setTab("map")}>GIS Map</button>
        {isAdmin && <button onClick={() => setTab("admin")}>Admin & Audit</button>}
      </nav>

      <div className="message">{message}</div>

      {tab === "dashboard" && (
        <>
          <section className="stats">
            <Stat label="Resources" value={dashboard.resources ?? resources.length}/>
            <Stat label="Pending" value={dashboard.pendingRequests ?? requests.length}/>
            <Stat label="Allocated" value={dashboard.allocatedRequests ?? 0}/>
            <Stat label="Preempted" value={dashboard.preemptedRequests ?? 0}/>
            <Stat label="Completed" value={dashboard.completedRequests ?? 0}/>
          </section>

          <section className="card">
            <h2>Resource Inventory</h2>
            <div className="inventory">
              {resources.map(r => (
                <div className="inventory-card" key={r.id}>
                  <h3>{r.name}</h3>
                  <b>{r.availableQuantity}</b>
                  <span>{r.unit} available</span>
                  {isManager && (
                    <button onClick={() => dispatch(r.id)}>Dispatch Queue</button>
                  )}
                </div>
              ))}
            </div>
          </section>

          {isManager && (
            <section className="card">
              <h2>OS + DBMS Status</h2>
              <div className="two-col">
                <div>
                  <p><b>Scheduling:</b> Dynamic Priority + Aging</p>
                  <p><b>Allocation:</b> Transactional</p>
                  <p><b>Concurrency:</b> Pessimistic row lock</p>
                </div>
                <div>
                  <p><b>Preemption:</b> Lower-priority allocations can be reclaimed</p>
                  <p><b>AI:</b> Intentionally disabled for this phase</p>
                  <p><b>Real-time:</b> WebSocket events enabled</p>
                </div>
              </div>
            </section>
          )}
        </>
      )}

      {tab === "requests" && (
        <div className="two-col">
          <section className="card">
            <h2>Submit Emergency Request</h2>
            <form onSubmit={submitRequest}>
              <label>Resource</label>
              <select required value={form.resourceId}
                      onChange={e => setForm({...form, resourceId: e.target.value})}>
                <option value="">Select resource</option>
                {resources.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
              </select>

              <label>Location</label>
              <select value={form.locationId}
                      onChange={e => setForm({...form, locationId: e.target.value})}>
                <option value="">Use my location</option>
                {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
              </select>

              <label>Quantity</label>
              <input type="number" min="1" value={form.quantity}
                onChange={e => setForm({...form, quantity: Number(e.target.value)})}/>

              <label>Severity (1-10)</label>
              <input type="number" min="1" max="10" value={form.severity}
                onChange={e => setForm({...form, severity: Number(e.target.value)})}/>

              <label>People affected</label>
              <input type="number" min="0" value={form.affectedPeople}
                onChange={e => setForm({...form, affectedPeople: Number(e.target.value)})}/>

              <label>Scarcity (1-10)</label>
              <input type="number" min="1" max="10" value={form.scarcity}
                onChange={e => setForm({...form, scarcity: Number(e.target.value)})}/>

              <button>Submit Request</button>
            </form>
          </section>

          <section className="card">
            <h2>Current Priority Queue</h2>
            <QueueTable requests={requests} onAllocate={isManager ? allocate : null}/>
          </section>
        </div>
      )}

      {tab === "os" && isManager && (
        <>
          <section className="card">
            <h2>OS Scheduling Simulator</h2>
            <p className="muted">
              Requests behave like processes. The scheduler chooses the highest
              priority request; aging increases waiting requests to reduce starvation.
            </p>

            <div className="scheduler-controls">
              <select value={selectedResource}
                      onChange={e => setSelectedResource(e.target.value)}>
                <option value="">All resources</option>
                {resources.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
              </select>

              {selectedResource && (
                <button onClick={() => dispatch(Number(selectedResource))}>
                  Run Scheduler
                </button>
              )}
            </div>

            <QueueTable requests={
              selectedResource
                ? requests.filter(r => String(r.resource?.id) === String(selectedResource))
                : requests
            } onAllocate={allocate} detailed/>
          </section>

          <section className="card">
            <h2>Preemption Simulator</h2>
            <p className="muted">
              Enter a critical pending request ID. The backend searches active
              lower-priority allocations, releases inventory, and retries allocation.
            </p>
            <div className="inline-form">
              <input value={preemptId} onChange={e => setPreemptId(e.target.value)}
                     placeholder="Critical request ID"/>
              <button onClick={preempt}>Run Preemption</button>
            </div>
          </section>
        </>
      )}

      {tab === "map" && (
        <section className="card">
          <h2>GIS Resource & Emergency Map</h2>
          <p className="muted">
            Blue/standard markers represent registered locations; request markers
            show pending emergency demand.
          </p>
          <MapView locations={locations} requests={requests}/>
        </section>
      )}

      {tab === "admin" && isAdmin && (
        <>
          <section className="stats">
            <Stat label="Users" value={adminSummary.users ?? users.length}/>
            <Stat label="Allocations" value={adminSummary.allocations ?? allocations.length}/>
            <Stat label="Audit Events" value={adminSummary.auditEvents ?? audit.length}/>
          </section>

          <section className="card">
            <h2>Audit Log</h2>
            <table>
              <thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Details</th></tr></thead>
              <tbody>
                {audit.map(x => (
                  <tr key={x.id}>
                    <td>{x.createdAt}</td>
                    <td>{x.actorEmail}</td>
                    <td>{x.action}</td>
                    <td>{x.details}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className="card">
            <h2>Allocation History</h2>
            <table>
              <thead><tr><th>ID</th><th>Request</th><th>Resource</th><th>Quantity</th><th>Status</th></tr></thead>
              <tbody>
                {allocations.map(x => (
                  <tr key={x.id}>
                    <td>{x.id}</td>
                    <td>#{x.request?.id}</td>
                    <td>{x.resource?.name}</td>
                    <td>{x.quantity}</td>
                    <td>{x.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className="card">
            <h2>Users</h2>
            <table>
              <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Location</th></tr></thead>
              <tbody>
                {users.map(u => (
                  <tr key={u.id}>
                    <td>{u.name}</td>
                    <td>{u.email}</td>
                    <td>{u.role}</td>
                    <td>{u.location?.name ?? "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </>
      )}
    </div>
  );
}

function QueueTable({ requests, onAllocate, detailed = false }) {
  return (
    <table>
      <thead>
        <tr>
          <th>ID</th><th>Resource</th><th>Qty</th><th>Severity</th>
          <th>People</th><th>Scarcity</th><th>Priority</th>
          {detailed && <th>Location</th>}
          <th>Status</th>
          {onAllocate && <th>Action</th>}
        </tr>
      </thead>
      <tbody>
        {requests.map(r => (
          <tr key={r.id}>
            <td>#{r.id}</td>
            <td>{r.resource?.name}</td>
            <td>{r.quantity}</td>
            <td>{r.severity}</td>
            <td>{r.affectedPeople}</td>
            <td>{r.scarcity}</td>
            <td><b className={r.priorityScore >= 80 ? "critical" : ""}>{r.priorityScore}</b></td>
            {detailed && <td>{r.location?.name ?? "-"}</td>}
            <td>{r.status}</td>
            {onAllocate && <td><button onClick={() => onAllocate(r.id)}>Allocate</button></td>}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

createRoot(document.getElementById("root")).render(<App />);
