// Avisos push de CooRuta. Cada función escucha un cambio en Firestore y avisa por FCM a quien
// corresponde. Los textos y las claves son los mismos que los avisos locales de la app
// (DetectorAvisos, AvisosViewModel): el teléfono no muestra dos veces el mismo aviso.
// Solo envía avisos; no cambia reservas, asientos ni viajes.
const { setGlobalOptions } = require("firebase-functions/v2");
const { onDocumentWritten, onDocumentUpdated, onDocumentCreated } = require("firebase-functions/v2/firestore");
const { logger } = require("firebase-functions");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAuth } = require("firebase-admin/auth");

initializeApp();
const db = getFirestore();

// Misma región que la base de datos (southamerica-west1); pocas instancias para un piloto.
setGlobalOptions({ region: "southamerica-west1", maxInstances: 5 });

const TOKEN_INVALIDO = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
]);

/** Envía un aviso de datos a todos los teléfonos de [uid] y borra los tokens que ya no sirven. */
async function avisar(uid, clave, titulo, texto) {
  if (!uid) return;
  const tokens = await db.collection("usuarios").doc(uid).collection("tokens").get();
  if (tokens.empty) return;
  const ids = tokens.docs.map((d) => d.id);
  const r = await getMessaging().sendEachForMulticast({
    tokens: ids,
    data: { uid, clave, titulo, texto },
    android: { priority: "high", ttl: 6 * 60 * 60 * 1000 },
  });
  const borrar = [];
  r.responses.forEach((resp, i) => {
    if (!resp.success && TOKEN_INVALIDO.has(resp.error?.code)) borrar.push(tokens.docs[i].ref.delete());
  });
  await Promise.all(borrar);
  // Sin datos personales en los registros: solo cantidades.
  logger.info("aviso", { enviados: r.successCount, fallidos: r.failureCount, borrados: borrar.length });
}

const nombre = (v, porDefecto) => (typeof v === "string" && v.trim() ? v : porDefecto);
const lugar = (m) => (m && typeof m.nombre === "string" ? m.nombre : "");

// Reservas: nueva solicitud, cancelación de una aceptada (conductor); aceptada, invitada, rechazada (pasajero).
exports.avisosReservas = onDocumentWritten("reservas/{id}", async (event) => {
  const antes = event.data?.before?.exists ? event.data.before.data() : null;
  const s = event.data?.after?.exists ? event.data.after.data() : null;
  if (!s || antes?.estado === s.estado) return;
  const id = event.params.id;
  const pasajero = nombre(s.nombrePasajero, "Un pasajero");
  const conductor = nombre(s.nombreConductor, "El conductor");
  switch (s.estado) {
    case "pendiente":
      return avisar(s.conductorId, id, "Nueva solicitud de asiento",
        `${pasajero} quiere viajar contigo (${s.fecha}, ${s.horaSalida}).`);
    case "cancelada":
      if (antes?.estado !== "aceptada") return;
      if (s.motivo === "acuerdo_terminado") return; // se avisa una vez al terminar el acuerdo
      if (s.motivo === "no_se_presento") return;    // lo marcó el propio conductor (marcarNoSePresento)
      if (s.motivo === "ruta_eliminada") return;    // el conductor borró la ruta (eliminarRutaForzado)
      if (s.motivo === "cuenta_eliminada") return;  // se avisa en eliminarCuentaCompleta
      return avisar(s.conductorId, id, "Se liberó un asiento", `${pasajero} canceló su reserva del ${s.fecha}.`);
    case "aceptada":
      if (s.habitualId) return; // parte de un acuerdo habitual: se avisa una sola vez (avisosHabituales)
      return avisar(s.pasajeroId, id, "¡Asiento confirmado!",
        `${conductor} te espera en ${lugar(s.subida)} a las ${s.horaSalida}.`);
    case "invitada":
      return avisar(s.pasajeroId, id, "Te invitan de nuevo",
        `${conductor} te invita otra vez a su viaje del ${s.fecha}. Entra a Solicitudes para aceptar.`);
    case "rechazada":
      return avisar(s.pasajeroId, id, "Solicitud rechazada", `${conductor} no puede llevarte esta vez. Busca otra ruta.`);
  }
});

/** Reservas aceptadas de una ruta en una fecha. */
async function aceptadas(rutaId, fecha) {
  const q = await db.collection("reservas")
    .where("rutaId", "==", rutaId).where("fecha", "==", fecha).where("estado", "==", "aceptada").get();
  return q.docs;
}

// Viaje: «sale en 5 minutos» y «en camino» a cada pasajero aceptado de esa salida.
exports.avisosViaje = onDocumentUpdated("viajes/{vid}", async (event) => {
  const antes = event.data.before.data();
  const v = event.data.after.data();
  if (antes.estado === v.estado) return;
  const vid = event.params.vid;
  const reservas = await aceptadas(v.rutaId, v.fecha);
  await Promise.all(reservas.map((d) => {
    const s = d.data();
    const conductor = nombre(s.nombreConductor, "Tu conductor");
    if (v.estado === "saliendo")
      return avisar(s.pasajeroId, `${vid}_sale`, "Tu conductor sale en 5 minutos",
        `${conductor} inicia su recorrido en 5 minutos. Ve a ${lugar(s.subida)}.`);
    if (v.estado === "en_curso")
      return avisar(s.pasajeroId, `${vid}_curso`, "Tu conductor está en camino", `${conductor} ya va hacia ${lugar(s.subida)}.`);
    return null;
  }));
});

// Chat: mensaje nuevo a la otra persona de la reserva.
exports.avisosChat = onDocumentCreated("reservas/{rid}/mensajes/{mid}", async (event) => {
  const m = event.data?.data();
  if (!m) return;
  const rid = event.params.rid;
  const r = (await db.collection("reservas").doc(rid).get()).data();
  if (!r) return;
  const alConductor = m.autorId === r.pasajeroId;
  const destino = alConductor ? r.conductorId : r.pasajeroId;
  const otro = alConductor ? nombre(r.nombrePasajero, "Pasajero") : nombre(r.nombreConductor, "Conductor");
  return avisar(destino, `${rid}_msg`, `Mensaje de ${otro}`, String(m.texto ?? ""));
});

// Días libres del conductor: avisa a los pasajeros aceptados de las fechas recién marcadas.
exports.avisosDiasLibres = onDocumentUpdated("rutas/{rutaId}", async (event) => {
  const antes = new Set(event.data.before.data().fechasLibres ?? []);
  const nuevas = (event.data.after.data().fechasLibres ?? []).filter((f) => !antes.has(f));
  if (nuevas.length === 0) return;
  const rutaId = event.params.rutaId;
  for (const fecha of nuevas) {
    const reservas = await aceptadas(rutaId, fecha);
    await Promise.all(reservas.map((d) => {
      const s = d.data();
      return avisar(s.pasajeroId, `${d.id}_libre`, "Tu conductor no viajará",
        `${nombre(s.nombreConductor, "El conductor")} no hará el viaje del ${fechaLegible(fecha)}. Busca otra ruta para ese día.`);
    }));
  }
});

/** "2026-10-29" → "jueves 29", como en la app. */
function fechaLegible(f) {
  const d = new Date(`${f}T12:00:00-05:00`);
  if (isNaN(d)) return f;
  return new Intl.DateTimeFormat("es-PE", { weekday: "long", day: "numeric", timeZone: "America/Lima" }).format(d).replace(",", "");
}

// ---------------------------------------------------------------- proximidad («a 5 minutos») y llegada

const { GoogleAuth } = require("google-auth-library");
const authGoogle = new GoogleAuth({ scopes: "https://www.googleapis.com/auth/cloud-platform" });

const AVISO_SEG = 5 * 60 + 30;        // «a 5 minutos» (con medio minuto de margen)
const CADA_MS = 45_000;               // como mucho un cálculo por viaje cada 45 s
const RADIO_CALCULO_M = 6_000;        // más lejos que esto no puede estar a 5 min en ciudad
const PARADA_SEG = 60;                // tiempo estimado en cada recogida anterior

function metros(a, b) {
  const R = 6371000, rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
const punto = (m) => (m && typeof m.latitud === "number" && typeof m.longitud === "number" ? { lat: m.latitud, lng: m.longitud } : null);
const latLng = (p) => ({ location: { latLng: { latitude: p.lat, longitude: p.lng } } });

/**
 * Segundos hasta cada recogida pendiente, en el orden que conviene (Routes API con orden optimizado
 * y destino privado de la ruta). null si la API no responde.
 */
async function segundosHasta(origen, paradas, destino) {
  const token = await authGoogle.getAccessToken();
  const cuerpo = {
    origin: latLng(origen),
    destination: latLng(destino ?? paradas[paradas.length - 1].p),
    intermediates: (destino ? paradas : paradas.slice(0, -1)).map((x) => latLng(x.p)),
    travelMode: "DRIVE",
    routingPreference: "TRAFFIC_AWARE",
    optimizeWaypointOrder: true,
  };
  const r = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      "X-Goog-User-Project": process.env.GCLOUD_PROJECT,
      "X-Goog-FieldMask": "routes.legs.duration,routes.optimizedIntermediateWaypointIndex",
    },
    body: JSON.stringify(cuerpo),
  });
  if (!r.ok) { logger.warn("Routes API", { estado: r.status }); return null; }
  const ruta = (await r.json()).routes?.[0];
  if (!ruta?.legs) return null;
  const intermedias = destino ? paradas : paradas.slice(0, -1);
  const orden = ruta.optimizedIntermediateWaypointIndex?.length === intermedias.length
    ? ruta.optimizedIntermediateWaypointIndex : intermedias.map((_, i) => i);
  const resultado = new Map();
  let acumulado = 0;
  orden.forEach((original, k) => {
    acumulado += parseFloat(ruta.legs[k]?.duration ?? "0") + (k > 0 ? PARADA_SEG : 0);
    resultado.set(intermedias[original].id, acumulado);
  });
  if (!destino) { // la última parada fue el destino de la consulta
    const ultima = paradas[paradas.length - 1];
    acumulado += parseFloat(ruta.legs[orden.length]?.duration ?? "0") + (orden.length > 0 ? PARADA_SEG : 0);
    resultado.set(ultima.id, acumulado);
  }
  return resultado;
}

// Cada ubicación nueva del conductor: estima la llegada a cada recogida pendiente y avisa una sola
// vez «tu conductor está a 5 minutos». Guarda las estimaciones en viajes/{vid}/eta (solo servidor).
exports.avisosProximidad = onDocumentWritten("viajes/{vid}/ubicacion/{doc}", async (event) => {
  const u = event.data?.after?.exists ? event.data.after.data() : null;
  if (!u || u.activa !== true || typeof u.latitud !== "number") return;
  const vid = event.params.vid;
  const viajeRef = db.collection("viajes").doc(vid);
  const v = (await viajeRef.get()).data();
  if (!v || !["saliendo", "en_curso"].includes(v.estado)) return;

  // Como mucho un cálculo cada CADA_MS por viaje (transacción: dos ejecuciones no calculan a la vez).
  const control = viajeRef.collection("eta").doc("_control");
  const ahora = Date.now();
  const toca = await db.runTransaction(async (tx) => {
    const c = await tx.get(control);
    if (c.exists && ahora - (c.data().ultimoMs ?? 0) < CADA_MS) return false;
    tx.set(control, { ultimoMs: ahora });
    return true;
  });
  if (!toca) return;

  const origen = { lat: u.latitud, lng: u.longitud };
  const reservas = await aceptadas(v.rutaId, v.fecha);
  const pendientes = [];
  for (const d of reservas) {
    const parada = (await viajeRef.collection("paradas").doc(d.id).get()).data();
    if (parada && ["llegue", "a_bordo"].includes(parada.estado)) continue;
    const p = punto(d.data().subida);
    if (p) pendientes.push({ id: d.id, p, s: d.data() });
  }
  if (pendientes.length === 0) return;
  const avisados = new Set((await viajeRef.collection("eta").where("avisado5", "==", true).get()).docs.map((d) => d.id));
  if (!pendientes.some((x) => !avisados.has(x.id) && metros(origen, x.p) <= RADIO_CALCULO_M)) return;

  const privada = (await db.collection("rutasPrivadas").doc(v.rutaId).get()).data();
  let segundos = null;
  try {
    segundos = await segundosHasta(origen, pendientes.slice(0, 25), punto(privada?.destino));
  } catch (e) {
    logger.warn("Routes API no disponible", { error: String(e?.message ?? e) });
  }
  // Sin Routes API: solo línea recta a ~20 km/h y sin tener en cuenta el orden (conservador).
  if (!segundos) segundos = new Map(pendientes.map((x) => [x.id, metros(origen, x.p) / (20 / 3.6)]));

  await Promise.all(pendientes.map(async (x) => {
    const seg = segundos.get(x.id);
    if (seg == null) return;
    const ref = viajeRef.collection("eta").doc(x.id);
    await ref.set({ llegaEnMs: ahora + Math.round(seg * 1000), calculadoMs: ahora }, { merge: true });
    if (seg > AVISO_SEG || avisados.has(x.id)) return;
    // Marca primero (transacción) y solo después avisa: nunca dos avisos para la misma recogida.
    const marcar = await db.runTransaction(async (tx) => {
      const e = await tx.get(ref);
      if (e.exists && e.data().avisado5 === true) return false;
      tx.set(ref, { avisado5: true }, { merge: true });
      return true;
    });
    if (!marcar) return;
    const min = Math.max(1, Math.round(seg / 60));
    await avisar(x.s.pasajeroId, `${vid}_${x.id}_5min`, "Tu conductor está a 5 minutos",
      `${nombre(x.s.nombreConductor, "Tu conductor")} llegará a ${lugar(x.s.subida)} en unos ${min} min.`);
  }));
});

// «Llegué»: el conductor marcó que está en el punto de encuentro.
exports.avisosLlegada = onDocumentWritten("viajes/{vid}/paradas/{rid}", async (event) => {
  const antes = event.data?.before?.exists ? event.data.before.data() : null;
  const p = event.data?.after?.exists ? event.data.after.data() : null;
  if (!p || p.estado !== "llegue" || antes?.estado === "llegue") return;
  const { vid, rid } = event.params;
  const s = (await db.collection("reservas").doc(rid).get()).data();
  if (!s || s.estado !== "aceptada") return;
  return avisar(s.pasajeroId, `${vid}_${rid}_llegue`, "Tu conductor llegó",
    `${nombre(s.nombreConductor, "Tu conductor")} te espera en ${lugar(s.subida)}.`);
});

// ---------------------------------------------------------------- acuerdo habitual (etapa 3a)
// `habituales/{rutaId}_{pasajeroId}`: el pasajero pide viajar ciertos días de la semana; el conductor
// ve el desvío y acepta. Al aceptar se crean las reservas de lo que queda de esta semana (la tarea
// del domingo crea las siguientes). Todo se escribe aquí, con las mismas comprobaciones que las
// reglas exigen a la app: el Admin SDK no pasa por las reglas.

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { FieldValue, FieldPath } = require("firebase-admin/firestore");

const DIAS = ["", "lun", "mar", "mié", "jue", "vie", "sáb", "dom"];
const textoDias = (d) => {
  const s = [...d].sort((a, b) => a - b);
  if (s.join() === "1,2,3,4,5") return "lun a vie";
  if (s.length === 7) return "todos los días";
  return s.map((x) => DIAS[x]).join(", ");
};
/** "los lun a vie", "todos los días". */
const losDias = (d) => { const t = textoDias(d); return t === "todos los días" ? t : `los ${t}`; };
/** Fecha de hoy en Lima, "yyyy-MM-dd" (como la app y las reglas). */
const hoyLima = () => new Intl.DateTimeFormat("en-CA", { timeZone: "America/Lima" }).format(new Date());
/** 1 = lunes … 7 = domingo (igual que DayOfWeek y `dias` de la ruta). */
const diaDe = (f) => { const g = new Date(`${f}T12:00:00Z`).getUTCDay(); return g === 0 ? 7 : g; };
const sumar = (f, n) => { const d = new Date(`${f}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
/** Misma fórmula que la app y las reglas: km × tarifa, redondeo a 50 céntimos, mínimo S/ 2. */
const pasajeDe = (km, tarifa) => { const b = Math.floor((Math.round(km * tarifa) + 25) / 50) * 50; return b < 200 ? 200 : b; };
const sumarMinutos = (hhmm, min) => {
  const [h, m] = String(hhmm).split(":").map(Number);
  const t = ((h * 60 + m + Math.round(min)) % 1440 + 1440) % 1440;
  return `${String(Math.floor(t / 60)).padStart(2, "0")}:${String(t % 60).padStart(2, "0")}`;
};

function lugarValido(l) {
  return l && typeof l.nombre === "string" && l.nombre.length <= 120
    && typeof l.latitud === "number" && Math.abs(l.latitud) <= 90
    && typeof l.longitud === "number" && Math.abs(l.longitud) <= 180;
}
const exigirSesion = (req) => { if (!req.auth) throw new HttpsError("unauthenticated", "Inicia sesión con Google."); return req.auth.uid; };

/** Fechas de lo que queda de esta semana (hasta el domingo) en que este acuerdo viaja. */
function fechasDeEstaSemana(h, ruta) {
  const hoy = hoyLima();
  return fechasEntre(h, ruta, hoy, sumar(hoy, 7 - diaDe(hoy)));
}

/**
 * Fechas entre [inicio] y [fin] (incluidas) en que el acuerdo viaja: sus días, días de la ruta, no
 * marcadas «no viajo», desde que empezó y antes de que termine (`hasta`, si se terminó).
 */
function fechasEntre(h, ruta, inicio, fin) {
  const desde = h.desde > inicio ? h.desde : inicio;
  const diasRuta = Array.isArray(ruta.dias) ? ruta.dias : [];
  const libres = new Set(ruta.fechasLibres ?? []);
  const fechas = [];
  for (let f = desde; f <= fin; f = sumar(f, 1)) {
    const d = diaDe(f);
    if (h.hasta && f >= h.hasta) break;
    if (h.dias.includes(d) && (diasRuta.length === 0 || diasRuta.includes(d)) && !libres.has(f)) fechas.push(f);
  }
  return fechas;
}

/** Índice del punto del recorrido más cercano a p: ordena paradas a lo largo de la ruta. */
function posicionEn(recorrido, p) {
  let mejor = 0, dmin = Infinity;
  recorrido.forEach((q, i) => { const d = metros({ lat: q.lat, lng: q.lng }, p); if (d < dmin) { dmin = d; mejor = i; } });
  return mejor;
}

/**
 * Recorrido origen → destino privados pasando por [paradas] en el orden del recorrido del conductor
 * (así cada subida va antes de su bajada). Devuelve segundos, metros y segundos acumulados por parada.
 */
async function recorridoCon(privada, paradas) {
  const origen = punto(privada.origen), destino = punto(privada.destino);
  if (!origen || !destino) throw new HttpsError("failed-precondition", "Tu ruta no tiene origen o destino guardados. Vuelve a publicarla.");
  const linea = (privada.recorrido ?? []).filter((q) => typeof q.lat === "number");
  const orden = paradas.map((x, i) => ({ ...x, pos: linea.length ? posicionEn(linea, x.p) : i, i }))
    .sort((a, b) => a.pos - b.pos || a.i - b.i).slice(0, 25);
  const token = await authGoogle.getAccessToken();
  const r = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      "X-Goog-User-Project": process.env.GCLOUD_PROJECT,
      "X-Goog-FieldMask": "routes.duration,routes.distanceMeters,routes.legs.duration",
    },
    body: JSON.stringify({
      origin: latLng(origen), destination: latLng(destino),
      intermediates: orden.map((x) => latLng(x.p)), travelMode: "DRIVE", routingPreference: "TRAFFIC_AWARE",
    }),
  });
  if (!r.ok) { logger.warn("Routes API", { estado: r.status }); throw new HttpsError("unavailable", "No pudimos calcular la ruta. Intenta de nuevo."); }
  const ruta = (await r.json()).routes?.[0];
  if (!ruta) throw new HttpsError("unavailable", "No pudimos calcular la ruta. Intenta de nuevo.");
  const hasta = new Map();
  let acumulado = 0;
  orden.forEach((x, k) => { acumulado += parseFloat(ruta.legs?.[k]?.duration ?? "0"); hasta.set(x.clave, acumulado); });
  return { segundos: parseFloat(ruta.duration ?? "0"), metros: ruta.distanceMeters ?? 0, hasta };
}

/** Subidas y bajadas de los acuerdos aceptados de la ruta que comparten algún día con [dias]. */
async function paradasAceptadas(rutaId, dias, excepto) {
  const q = await db.collection("habituales").where("rutaId", "==", rutaId).where("estado", "==", "aceptado").get();
  return q.docs.filter((d) => d.id !== excepto && (d.data().dias ?? []).some((x) => dias.includes(x)))
    .flatMap((d) => [
      { clave: `${d.id}_s`, p: punto(d.data().subida) },
      { clave: `${d.id}_b`, p: punto(d.data().bajada) },
    ]).filter((x) => x.p);
}

/** Días de [h] sin cupo y fechas de esta semana sin asiento libre. */
function sinCupo(h, ruta, existentes) {
  const asientos = ruta.asientos ?? 0;
  const porDia = ruta.habitualesPorDia ?? {};
  const ocupados = ruta.ocupadosPorFecha ?? {};
  const dias = h.dias.filter((d) => (porDia[String(d)] ?? 0) + 1 > asientos);
  const fechas = fechasDeEstaSemana(h, ruta)
    .filter((f) => existentes.get(f) !== "aceptada" && (ocupados[f] ?? 0) + 1 > asientos);
  return { dias, fechas };
}

// Pasajero: pide viajar habitualmente en una ruta (o vuelve a pedirlo tras un rechazo o cancelación).
exports.solicitarHabitual = onCall(async (req) => {
  const uid = exigirSesion(req);
  const { rutaId, dias, subida, bajada, kmCompartidos, mensaje = "" } = req.data ?? {};
  if (typeof rutaId !== "string" || !rutaId) throw new HttpsError("invalid-argument", "Falta la ruta.");
  const lista = [...new Set(Array.isArray(dias) ? dias : [])].filter((d) => Number.isInteger(d) && d >= 1 && d <= 7).sort((a, b) => a - b);
  if (lista.length === 0) throw new HttpsError("invalid-argument", "Elige al menos un día.");
  if (!lugarValido(subida) || !lugarValido(bajada)) throw new HttpsError("invalid-argument", "Faltan los puntos de subida y bajada.");
  if (typeof kmCompartidos !== "number" || kmCompartidos <= 0 || kmCompartidos > 200) throw new HttpsError("invalid-argument", "Distancia no válida.");
  if (typeof mensaje !== "string" || mensaje.length > 500) throw new HttpsError("invalid-argument", "El mensaje es muy largo.");

  const ruta = (await db.collection("rutas").doc(rutaId).get()).data();
  if (!ruta || ruta.estado !== "activa") throw new HttpsError("failed-precondition", "Esta ruta ya no está disponible.");
  if (ruta.conductorId === uid) throw new HttpsError("failed-precondition", "No puedes pedir asiento en tu propia ruta.");
  // Comunidades separadas: la del pasajero sale de su correo verificado.
  const correo = String(req.auth.token.email ?? "").toLowerCase();
  const comunidad = correo.endsWith("@tecsup.edu.pe") && req.auth.token.email_verified === true ? "tecsup" : "externa";
  if (ruta.comunidad !== comunidad) throw new HttpsError("permission-denied", "Esta ruta no está disponible para tu comunidad.");
  const diasRuta = Array.isArray(ruta.dias) ? ruta.dias : [];
  const fuera = lista.filter((d) => diasRuta.length && !diasRuta.includes(d));
  if (fuera.length) throw new HttpsError("failed-precondition", `El conductor no viaja ${losDias(fuera)}.`);

  const id = `${rutaId}_${uid}`;
  const ref = db.collection("habituales").doc(id);
  const previo = (await ref.get()).data();
  if (previo?.estado === "pendiente") throw new HttpsError("already-exists", "Ya enviaste esta solicitud: espera la respuesta del conductor.");
  if (previo?.estado === "aceptado") throw new HttpsError("already-exists", "Ya viajas habitualmente con este conductor.");

  // Hora de recogida estimada: salida de la ruta + tiempo hasta la subida (con los acuerdos que ya tiene).
  let horaRecogida = ruta.horaSalida ?? "";
  try {
    const privada = (await db.collection("rutasPrivadas").doc(rutaId).get()).data();
    if (privada) {
      const otras = await paradasAceptadas(rutaId, lista, id);
      const r = await recorridoCon(privada, [...otras, { clave: "nueva_s", p: { lat: subida.latitud, lng: subida.longitud } },
        { clave: "nueva_b", p: { lat: bajada.latitud, lng: bajada.longitud } }]);
      horaRecogida = sumarMinutos(ruta.horaSalida, (r.hasta.get("nueva_s") ?? 0) / 60);
    }
  } catch (e) {
    logger.warn("Hora de recogida sin estimar", { error: String(e?.message ?? e) });
  }

  const tarifa = ruta.tarifaKmCentimos ?? 25;
  const datos = {
    rutaId, conductorId: ruta.conductorId, pasajeroId: uid,
    nombrePasajero: nombre(req.auth.token.name, "Pasajero"), nombreConductor: nombre(ruta.nombre, "Conductor"),
    dias: lista,
    subida: { nombre: subida.nombre, latitud: subida.latitud, longitud: subida.longitud },
    bajada: { nombre: bajada.nombre, latitud: bajada.latitud, longitud: bajada.longitud },
    kmCompartidos, tarifaKmCentimos: tarifa, pasajeCentimos: pasajeDe(kmCompartidos, tarifa),
    horaSalida: ruta.horaSalida ?? "", horaRecogida, desde: hoyLima(),
    mensaje: mensaje.trim(), estado: "pendiente",
    creadoEn: previo ? previo.creadoEn : FieldValue.serverTimestamp(), actualizadoEn: FieldValue.serverTimestamp(),
  };
  await ref.set(datos);
  return { horaRecogida, pasajeCentimos: datos.pasajeCentimos };
});

// Pasajero: retira una solicitud que el conductor aún no respondió.
exports.cancelarHabitual = onCall(async (req) => {
  const uid = exigirSesion(req);
  const ref = db.collection("habituales").doc(String(req.data?.id ?? ""));
  await db.runTransaction(async (tx) => {
    const h = (await tx.get(ref)).data();
    if (!h || h.pasajeroId !== uid) throw new HttpsError("permission-denied", "Esta solicitud no es tuya.");
    if (h.estado !== "pendiente") throw new HttpsError("failed-precondition", "Esta solicitud ya fue respondida.");
    tx.update(ref, { estado: "cancelado", actualizadoEn: FieldValue.serverTimestamp() });
  });
  return { ok: true };
});

// Conductor: cuánto cambia su recorrido con este pasajero (contando a los que ya lleva esos días) y si hay cupo.
exports.desvioHabitual = onCall(async (req) => {
  const uid = exigirSesion(req);
  const id = String(req.data?.id ?? "");
  const h = (await db.collection("habituales").doc(id).get()).data();
  if (!h || h.conductorId !== uid) throw new HttpsError("permission-denied", "Esta solicitud no es para tu ruta.");
  const ruta = (await db.collection("rutas").doc(h.rutaId).get()).data();
  const privada = (await db.collection("rutasPrivadas").doc(h.rutaId).get()).data();
  if (!ruta || !privada) throw new HttpsError("failed-precondition", "No encontramos tu ruta.");
  const otras = await paradasAceptadas(h.rutaId, h.dias, id);
  const nuevas = [{ clave: "nueva_s", p: punto(h.subida) }, { clave: "nueva_b", p: punto(h.bajada) }];
  const [base, con] = await Promise.all([recorridoCon(privada, otras), recorridoCon(privada, [...otras, ...nuevas])]);
  const existentes = new Map();
  for (const f of fechasDeEstaSemana(h, ruta)) {
    const r = (await db.collection("reservas").doc(`${h.rutaId}_${h.pasajeroId}_${f}`).get()).data();
    if (r) existentes.set(f, r.estado);
  }
  const falta = sinCupo(h, ruta, existentes);
  return {
    segundosExtra: Math.max(0, Math.round(con.segundos - base.segundos)),
    metrosExtra: Math.max(0, Math.round(con.metros - base.metros)),
    minutosTotal: Math.round(con.segundos / 60),
    pasajerosEsosDias: otras.length / 2,
    horaRecogida: sumarMinutos(ruta.horaSalida, (con.hasta.get("nueva_s") ?? 0) / 60),
    diasSinCupo: falta.dias.map((d) => DIAS[d]),
    fechasSinCupo: falta.fechas,
  };
});

// Conductor: acepta (crea las reservas de lo que queda de la semana) o rechaza. Todo en una transacción.
exports.responderHabitual = onCall(async (req) => {
  const uid = exigirSesion(req);
  const id = String(req.data?.id ?? "");
  const aceptar = req.data?.aceptar === true;
  const ref = db.collection("habituales").doc(id);
  return db.runTransaction(async (tx) => {
    const h = (await tx.get(ref)).data();
    if (!h || h.conductorId !== uid) throw new HttpsError("permission-denied", "Esta solicitud no es para tu ruta.");
    if (h.estado !== "pendiente") throw new HttpsError("failed-precondition", "Esta solicitud ya fue respondida.");
    if (!aceptar) {
      tx.update(ref, { estado: "rechazado", respondidoEn: FieldValue.serverTimestamp(), actualizadoEn: FieldValue.serverTimestamp() });
      return { fechas: [] };
    }
    const rutaRef = db.collection("rutas").doc(h.rutaId);
    const ruta = (await tx.get(rutaRef)).data();
    if (!ruta || ruta.conductorId !== uid) throw new HttpsError("failed-precondition", "La ruta ya no existe.");
    if (ruta.estado !== "activa") throw new HttpsError("failed-precondition", "Reactiva la ruta para aceptar pasajeros.");
    const hoy = hoyLima();
    const fechas = fechasDeEstaSemana(h, ruta);
    // Lecturas antes de escribir: reservas de esas fechas y el viaje de hoy (si ya terminó, hoy no cuenta).
    const viajeHoy = fechas.includes(hoy) ? (await tx.get(db.collection("viajes").doc(`${h.rutaId}_${hoy}`))).data() : null;
    const existentes = new Map();
    const refs = new Map();
    for (const f of fechas) {
      const rr = db.collection("reservas").doc(`${h.rutaId}_${h.pasajeroId}_${f}`);
      refs.set(f, rr);
      const d = (await tx.get(rr)).data();
      if (d) existentes.set(f, d.estado);
    }
    const falta = sinCupo(h, ruta, existentes);
    if (falta.dias.length) throw new HttpsError("failed-precondition", `No te quedan asientos ${losDias(falta.dias)}.`);
    if (falta.fechas.length) throw new HttpsError("failed-precondition", `No te quedan asientos el ${falta.fechas.join(", ")}.`);

    const nuevas = fechas.filter((f) => existentes.get(f) !== "aceptada" && !(f === hoy && viajeHoy?.estado === "finalizado"));
    const cambiosRuta = {};
    h.dias.forEach((d) => { cambiosRuta[`habitualesPorDia.${d}`] = FieldValue.increment(1); });
    nuevas.forEach((f) => { cambiosRuta[`ocupadosPorFecha.${f}`] = FieldValue.increment(1); });
    if (nuevas.length) {
      const ultima = nuevas[nuevas.length - 1];
      if (ultima > (ruta.ultimaFechaAceptada ?? "")) cambiosRuta.ultimaFechaAceptada = ultima;
    }
    tx.update(rutaRef, cambiosRuta);
    for (const f of nuevas) {
      tx.set(refs.get(f), {
        rutaId: h.rutaId, conductorId: h.conductorId, pasajeroId: h.pasajeroId,
        nombrePasajero: h.nombrePasajero, nombreConductor: h.nombreConductor,
        fecha: f, horaSalida: ruta.horaSalida ?? h.horaSalida, horaRecogida: h.horaRecogida,
        subida: h.subida, bajada: h.bajada, kmCompartidos: h.kmCompartidos, pasajeCentimos: h.pasajeCentimos,
        mensaje: "", estado: "aceptada", habitualId: id,
        creadaEn: FieldValue.serverTimestamp(), respondidaEn: FieldValue.serverTimestamp(),
      });
    }
    tx.update(ref, { estado: "aceptado", respondidoEn: FieldValue.serverTimestamp(), actualizadoEn: FieldValue.serverTimestamp() });
    return { fechas: nuevas };
  });
});

// Avisos del acuerdo habitual (las reservas que crea no avisan una por una: ver avisosReservas).
exports.avisosHabituales = onDocumentWritten("habituales/{id}", async (event) => {
  const antes = event.data?.before?.exists ? event.data.before.data() : null;
  const h = event.data?.after?.exists ? event.data.after.data() : null;
  if (!h || antes?.estado === h.estado) return;
  const id = event.params.id;
  const dias = losDias(h.dias ?? []);
  if (h.estado === "pendiente")
    return avisar(h.conductorId, `${id}_hab`, "Solicitud para viajar habitualmente",
      `${nombre(h.nombrePasajero, "Un pasajero")} quiere viajar contigo ${dias}.`);
  if (h.estado === "aceptado")
    return avisar(h.pasajeroId, `${id}_hab`, "¡Ya viajas habitualmente!",
      `${nombre(h.nombreConductor, "El conductor")} te lleva ${dias}. Te recoge en ${lugar(h.subida)} hacia las ${h.horaRecogida}.`);
  if (h.estado === "rechazado")
    return avisar(h.pasajeroId, `${id}_hab`, "Solicitud habitual rechazada",
      `${nombre(h.nombreConductor, "El conductor")} no puede llevarte esos días. Busca otra ruta.`);
  if (h.estado === "terminado") {
    if (h.motivo === "cuenta_eliminada") return; // se avisa en eliminarCuentaCompleta
    const porConductor = h.terminadoPor === h.conductorId;
    return avisar(porConductor ? h.pasajeroId : h.conductorId, `${id}_hab`, "Viaje habitual terminado",
      porConductor
        ? `${nombre(h.nombreConductor, "El conductor")} ya no te llevará habitualmente desde el ${fechaLegible(h.hasta)}.`
        : `${nombre(h.nombrePasajero, "El pasajero")} ya no viajará contigo habitualmente desde el ${fechaLegible(h.hasta)}.`);
  }
});

// ---------------------------------------------------------------- acuerdo habitual (etapa 3b)

const { onSchedule } = require("firebase-functions/v2/scheduler");

/**
 * Crea, en una transacción, las reservas de [h] para [fechas] que todavía no existen. Una reserva que
 * ya existe (aceptada, cancelada por el pasajero, etc.) no se toca: así nunca se reactiva un día
 * cancelado ni se cuenta dos veces. Sin asiento libre esa fecha, no se crea y se informa.
 */
async function generarReservas(hid, inicio, fin) {
  const ref = db.collection("habituales").doc(hid);
  return db.runTransaction(async (tx) => {
    const h = (await tx.get(ref)).data();
    if (!h || h.estado !== "aceptado") return { creadas: [], sinCupo: [] };
    const rutaRef = db.collection("rutas").doc(h.rutaId);
    const ruta = (await tx.get(rutaRef)).data();
    if (!ruta || ruta.estado !== "activa") return { creadas: [], sinCupo: [], pausada: true };
    const fechas = fechasEntre(h, ruta, inicio, fin);
    const refs = fechas.map((f) => db.collection("reservas").doc(`${h.rutaId}_${h.pasajeroId}_${f}`));
    const existen = await Promise.all(refs.map((r) => tx.get(r)));
    const ocupados = { ...(ruta.ocupadosPorFecha ?? {}) };
    const creadas = [], sinCupo = [];
    fechas.forEach((f, i) => {
      if (existen[i].exists) return;
      if ((ocupados[f] ?? 0) + 1 > (ruta.asientos ?? 0)) { sinCupo.push(f); return; }
      ocupados[f] = (ocupados[f] ?? 0) + 1;
      creadas.push(f);
      tx.set(refs[i], {
        rutaId: h.rutaId, conductorId: h.conductorId, pasajeroId: h.pasajeroId,
        nombrePasajero: h.nombrePasajero, nombreConductor: h.nombreConductor,
        fecha: f, horaSalida: ruta.horaSalida ?? h.horaSalida, horaRecogida: h.horaRecogida,
        subida: h.subida, bajada: h.bajada, kmCompartidos: h.kmCompartidos, pasajeCentimos: h.pasajeCentimos,
        mensaje: "", estado: "aceptada", habitualId: hid,
        creadaEn: FieldValue.serverTimestamp(), respondidaEn: FieldValue.serverTimestamp(),
      });
    });
    // Cada fecha sin asiento se avisa una sola vez (la tarea corre a diario).
    const avisadas = new Set(h.sinCupoAvisado ?? []);
    const nuevasSinCupo = sinCupo.filter((f) => !avisadas.has(f));
    if (nuevasSinCupo.length) tx.update(ref, { sinCupoAvisado: FieldValue.arrayUnion(...nuevasSinCupo) });
    if (creadas.length) {
      const cambios = {};
      creadas.forEach((f) => { cambios[`ocupadosPorFecha.${f}`] = FieldValue.increment(1); });
      const ultima = creadas[creadas.length - 1];
      if (ultima > (ruta.ultimaFechaAceptada ?? "")) cambios.ultimaFechaAceptada = ultima;
      tx.update(rutaRef, cambios);
    }
    return { creadas, sinCupo: nuevasSinCupo, h };
  });
}

// Todos los días a las 6 p. m. (Lima): reservas de los próximos 7 días de cada acuerdo activo.
// Correr a diario recupera un día fallido; el resumen «tus viajes de esta semana» sale los domingos.
// Para probar: Cloud Scheduler → firebase-schedule-generarSemana… → «Forzar ejecución».
exports.generarSemana = onSchedule({ schedule: "0 18 * * *", timeZone: "America/Lima", region: "us-central1" }, async () => {
  const hoy = hoyLima();
  const inicio = sumar(hoy, 1), fin = sumar(hoy, 7);
  const domingo = diaDe(hoy) === 7;
  const activos = await db.collection("habituales").where("estado", "==", "aceptado").get();
  let total = 0, faltas = 0;
  for (const d of activos.docs) {
    let r;
    try { r = await generarReservas(d.id, inicio, fin); } catch (e) {
      logger.error("generarSemana", { error: String(e?.message ?? e) }); continue;
    }
    total += r.creadas.length; faltas += r.sinCupo.length;
    const h = r.h;
    if (!h) continue;
    const conductor = nombre(h.nombreConductor, "Tu conductor");
    for (const f of r.sinCupo) {
      await avisar(h.pasajeroId, `${d.id}_${f}_sincupo`, "Sin asiento ese día",
        `${conductor} no tiene asiento libre el ${fechaLegible(f)}. Busca otra ruta para ese día.`);
      await avisar(h.conductorId, `${d.id}_${f}_sincupo`, "Un habitual se quedó sin asiento",
        `${nombre(h.nombrePasajero, "Tu pasajero")} no tiene asiento el ${fechaLegible(f)}: tu ruta está llena ese día.`);
    }
    if (domingo && r.creadas.length)
      await avisar(h.pasajeroId, `${d.id}_semana_${inicio}`, "Tus viajes de esta semana",
        `Tienes asiento con ${conductor} ${r.creadas.length === 1 ? "1 día" : `${r.creadas.length} días`}. ¿Cambia algo? Cancela un día en Solicitudes.`);
  }
  logger.info("generarSemana", { acuerdos: activos.size, creadas: total, sinCupo: faltas });
});

// Pasajero o conductor: termina el acuerdo desde [desde] (hoy por defecto). Cancela sus reservas desde
// esa fecha (no la de hoy si el viaje ya salió), libera los asientos y el cupo semanal.
exports.terminarHabitual = onCall(async (req) => {
  const uid = exigirSesion(req);
  const id = String(req.data?.id ?? "");
  const hoy = hoyLima();
  let desde = typeof req.data?.desde === "string" && /^\d{4}-\d{2}-\d{2}$/.test(req.data.desde) ? req.data.desde : hoy;
  if (desde < hoy) desde = hoy;
  const ref = db.collection("habituales").doc(id);
  return db.runTransaction(async (tx) => {
    const h = (await tx.get(ref)).data();
    if (!h || (h.pasajeroId !== uid && h.conductorId !== uid)) throw new HttpsError("permission-denied", "Este acuerdo no es tuyo.");
    if (h.estado !== "aceptado") throw new HttpsError("failed-precondition", "Este acuerdo ya no está activo.");
    const rutaRef = db.collection("rutas").doc(h.rutaId);
    const ruta = (await tx.get(rutaRef)).data();
    const reservas = await tx.get(db.collection("reservas").where("habitualId", "==", id).where("estado", "==", "aceptada"));
    const viajeHoy = (await tx.get(db.collection("viajes").doc(`${h.rutaId}_${hoy}`))).data();
    const yaSalio = ["en_curso", "finalizado"].includes(viajeHoy?.estado);
    const cancelar = reservas.docs.filter((d) => {
      const f = d.data().fecha;
      return f >= desde && !(f === hoy && yaSalio);
    });
    const cambios = {};
    cancelar.forEach((d) => {
      cambios[`ocupadosPorFecha.${d.data().fecha}`] = FieldValue.increment(-1);
      tx.update(d.ref, { estado: "cancelada", motivo: "acuerdo_terminado", actualizadaEn: FieldValue.serverTimestamp() });
    });
    (h.dias ?? []).forEach((d) => {
      if ((ruta?.habitualesPorDia?.[String(d)] ?? 0) > 0) cambios[`habitualesPorDia.${d}`] = FieldValue.increment(-1);
    });
    if (ruta && Object.keys(cambios).length) tx.update(rutaRef, cambios);
    tx.update(ref, { estado: "terminado", hasta: desde, terminadoPor: uid, actualizadoEn: FieldValue.serverTimestamp() });
    return { canceladas: cancelar.length };
  });
});

// ---------------------------------------------------------------- «No se presentó» (etapa 4)
// El conductor marcó «Llegué» y esperó. La espera corre desde lo más tarde entre la hora acordada
// de recogida y su llegada; pasados 5 minutos puede marcarlo. Se cancela solo la reserva de hoy
// (un acuerdo habitual sigue vigente), se libera el asiento y se avisa al pasajero.
const ESPERA_MS = 5 * 60 * 1000;
/** "yyyy-MM-dd" + "HH:mm" en Lima (UTC−5, sin horario de verano) → milisegundos; NaN si no hay hora. */
const msLima = (f, h) => (/^\d{2}:\d{2}$/.test(h ?? "") ? Date.parse(`${f}T${h}:00-05:00`) : NaN);

exports.marcarNoSePresento = onCall(async (req) => {
  const uid = exigirSesion(req);
  const vid = String(req.data?.viajeId ?? ""), rid = String(req.data?.reservaId ?? "");
  if (!vid || !rid) throw new HttpsError("invalid-argument", "Faltan datos del viaje.");
  const viajeRef = db.collection("viajes").doc(vid);
  const paradaRef = viajeRef.collection("paradas").doc(rid);
  const reservaRef = db.collection("reservas").doc(rid);
  const s = await db.runTransaction(async (tx) => {
    const [v, p, r] = (await Promise.all([tx.get(viajeRef), tx.get(paradaRef), tx.get(reservaRef)])).map((d) => d.data());
    if (!v || v.conductorId !== uid) throw new HttpsError("permission-denied", "Este viaje no es tuyo.");
    if (v.estado !== "en_curso") throw new HttpsError("failed-precondition", "El recorrido no está en curso.");
    if (!r || r.rutaId !== v.rutaId || r.fecha !== v.fecha) throw new HttpsError("not-found", "No encontramos esta reserva.");
    if (r.estado !== "aceptada") throw new HttpsError("failed-precondition", "Esta reserva ya no está activa.");
    if (p?.estado !== "llegue") throw new HttpsError("failed-precondition", "Primero marca «Llegué» en su punto.");
    const llegada = p.actualizadoEn?.toMillis?.() ?? NaN;
    const marcas = [llegada, msLima(r.fecha, r.horaRecogida || r.horaSalida)].filter(Number.isFinite);
    if (!marcas.length) throw new HttpsError("failed-precondition", "No sabemos desde cuándo esperas. Intenta de nuevo.");
    const falta = Math.max(...marcas) + ESPERA_MS - Date.now();
    if (falta > 0) throw new HttpsError("failed-precondition", `Espera ${Math.ceil(falta / 60000)} min más antes de marcarlo.`);
    const rutaRef = db.collection("rutas").doc(r.rutaId);
    const ruta = (await tx.get(rutaRef)).data();
    tx.update(paradaRef, { estado: "no_se_presento", actualizadoEn: FieldValue.serverTimestamp() });
    tx.update(reservaRef, { estado: "cancelada", motivo: "no_se_presento", actualizadaEn: FieldValue.serverTimestamp() });
    if ((ruta?.ocupadosPorFecha?.[r.fecha] ?? 0) > 0) {
      tx.update(rutaRef, { [`ocupadosPorFecha.${r.fecha}`]: FieldValue.increment(-1) });
    }
    return r;
  });
  await avisar(s.pasajeroId, `${vid}_${rid}_nosepresento`, "No te encontramos",
    `${nombre(s.nombreConductor, "Tu conductor")} te esperó 5 minutos en ${lugar(s.subida)} y siguió su ruta. Tu reserva de hoy se canceló.`);
  return { ok: true };
});

// ---------------------------------------------------------------- grupo del conductor (invitaciones)
// El conductor comparte por WhatsApp un enlace de un solo uso (7 días). Quien lo abre entra a su
// grupo: ve sus rutas privadas en Buscar y puede pedirle asiento. Solo estas funciones escriben
// `invitacionesGrupo` y `miembrosGrupo`; el conductor o el integrante pueden borrar la membresía.
const DOMINIO_ENLACES = "https://cooruta-36603.web.app";
const VIGENCIA_INVITACION_MS = 7 * 24 * 60 * 60 * 1000;
const LETRAS_CODIGO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sin 0/O ni 1/I
const MAX_INVITACIONES_DIA = 20;
const { randomInt } = require("crypto");
const codigoNuevo = () => Array.from({ length: 8 }, () => LETRAS_CODIGO[randomInt(LETRAS_CODIGO.length)]).join("");
const nombreDe = async (uid) => {
  const p = (await db.collection("perfilesPublicos").doc(uid).get()).data();
  return nombre(p?.nombre, "");
};

exports.crearInvitacionGrupo = onCall(async (req) => {
  const uid = exigirSesion(req);
  const rutas = await db.collection("rutas").where("conductorId", "==", uid).limit(1).get();
  if (rutas.empty) throw new HttpsError("failed-precondition", "Publica una ruta antes de invitar a tu grupo.");
  // Sin índice compuesto: se cuentan aquí las del último día.
  const desde = Date.now() - 24 * 60 * 60 * 1000;
  const mias = await db.collection("invitacionesGrupo").where("conductorId", "==", uid).get();
  if (mias.docs.filter((d) => (d.data().creadaEn?.toMillis?.() ?? 0) >= desde).length >= MAX_INVITACIONES_DIA) throw new HttpsError("resource-exhausted", "Ya creaste muchas invitaciones hoy. Intenta mañana.");
  const conductor = await nombreDe(uid);
  for (let i = 0; i < 5; i++) {
    const codigo = codigoNuevo();
    const ref = db.collection("invitacionesGrupo").doc(codigo);
    try {
      await ref.create({
        conductorId: uid, nombreConductor: conductor, creadaEn: FieldValue.serverTimestamp(),
        venceEn: new Date(Date.now() + VIGENCIA_INVITACION_MS), usada: false,
      });
      return { codigo, enlace: `${DOMINIO_ENLACES}/i/${codigo}` };
    } catch (e) {
      if (e?.code !== 6) throw e; // 6 = ALREADY_EXISTS: se prueba otro código
    }
  }
  throw new HttpsError("internal", "No se pudo crear la invitación. Intenta de nuevo.");
});

exports.aceptarInvitacionGrupo = onCall(async (req) => {
  const uid = exigirSesion(req);
  const codigo = String(req.data?.codigo ?? "").trim().toUpperCase();
  if (!/^[A-Z0-9]{8}$/.test(codigo)) throw new HttpsError("invalid-argument", "El enlace de invitación no es válido.");
  const ref = db.collection("invitacionesGrupo").doc(codigo);
  const yo = await nombreDe(uid);
  const r = await db.runTransaction(async (tx) => {
    const inv = (await tx.get(ref)).data();
    if (!inv) throw new HttpsError("not-found", "Esta invitación no existe. Pide un enlace nuevo.");
    if (inv.conductorId === uid) throw new HttpsError("failed-precondition", "Es tu propia invitación: compártela con quien quieras sumar a tu grupo.");
    const miembroRef = db.collection("miembrosGrupo").doc(`${inv.conductorId}_${uid}`);
    if ((await tx.get(miembroRef)).exists) return { conductorId: inv.conductorId, nombreConductor: inv.nombreConductor, yaEra: true };
    if (inv.usada) throw new HttpsError("failed-precondition", "Este enlace ya se usó. Pide uno nuevo al conductor.");
    if (inv.venceEn?.toMillis?.() < Date.now()) throw new HttpsError("failed-precondition", "Este enlace venció. Pide uno nuevo al conductor.");
    tx.set(miembroRef, { conductorId: inv.conductorId, miembroId: uid, nombre: yo, unidoEn: FieldValue.serverTimestamp(), codigo });
    tx.update(ref, { usada: true, usadaPor: uid, usadaEn: FieldValue.serverTimestamp() });
    return { conductorId: inv.conductorId, nombreConductor: inv.nombreConductor, yaEra: false };
  });
  if (!r.yaEra) {
    await avisar(r.conductorId, `grupo_${uid}`, "Nuevo integrante en tu grupo",
      `${nombre(yo, "Alguien")} se unió con tu invitación. Ya ve tus rutas privadas.`);
  }
  return { nombreConductor: nombre(r.nombreConductor, "el conductor"), yaEra: r.yaEra };
});

// ---------------------------------------------------------------- eliminar ruta «de todos modos»
// El conductor borra una ruta aunque tenga pasajeros o acuerdos habituales. Las reglas no lo
// permiten desde la app (quedarían asientos sin ruta), así que se hace aquí: termina los acuerdos,
// cancela las reservas de hoy en adelante (motivo `ruta_eliminada`), avisa una vez a cada pasajero
// y borra la ruta. No se puede con un viaje en curso.
exports.eliminarRutaForzado = onCall(async (req) => {
  const uid = exigirSesion(req);
  const rutaId = String(req.data?.rutaId ?? "");
  if (!rutaId) throw new HttpsError("invalid-argument", "Falta la ruta.");
  const rutaRef = db.collection("rutas").doc(rutaId);
  const ruta = (await rutaRef.get()).data();
  if (!ruta) return { canceladas: 0 }; // ya no existe
  if (ruta.conductorId !== uid) throw new HttpsError("permission-denied", "Esta ruta no es tuya.");
  const hoy = hoyLima();
  const viajeHoy = (await db.collection("viajes").doc(`${rutaId}_${hoy}`).get()).data();
  if (viajeHoy?.estado === "en_curso") throw new HttpsError("failed-precondition", "Tienes este viaje en curso. Finalízalo antes de eliminar la ruta.");
  return borrarRutaConAvisos(uid, rutaId, rutaRef, ruta, hoy);
});

/** Termina acuerdos, cancela reservas futuras (avisando una vez a cada pasajero) y borra la ruta. */
async function borrarRutaConAvisos(uid, rutaId, rutaRef, ruta, hoy, porCuenta = false) {
  const habituales = await db.collection("habituales").where("rutaId", "==", rutaId).get();
  const reservas = await db.collection("reservas").where("rutaId", "==", rutaId).get();
  const afectadas = reservas.docs.filter((d) => {
    const r = d.data();
    return ["pendiente", "aceptada", "invitada"].includes(r.estado) && r.fecha >= hoy;
  });
  const lote = db.batch();
  const vigentes = habituales.docs.filter((d) => ["pendiente", "aceptado"].includes(d.data().estado));
  // Quien tenía acuerdo habitual ya recibe «Viaje habitual terminado» (avisosHabituales).
  const conHabitual = new Set(vigentes.map((d) => d.data().pasajeroId));
  vigentes.forEach((d) => {
    lote.update(d.ref, { estado: "terminado", hasta: hoy, terminadoPor: uid, actualizadoEn: FieldValue.serverTimestamp(),
      ...(porCuenta ? { motivo: "cuenta_eliminada" } : {}) });
  });
  afectadas.forEach((d) => lote.update(d.ref, {
    estado: "cancelada", motivo: porCuenta ? "cuenta_eliminada" : "ruta_eliminada", actualizadaEn: FieldValue.serverTimestamp(),
  }));
  lote.delete(db.collection("rutasPrivadas").doc(rutaId));
  lote.delete(rutaRef);
  await lote.commit();

  // Un aviso por pasajero (aunque tenga varias fechas).
  const conductor = nombre(ruta.nombre, "El conductor");
  const pasajeros = new Map();
  afectadas.forEach((d) => {
    const r = d.data();
    if ((porCuenta || !conHabitual.has(r.pasajeroId)) && !pasajeros.has(r.pasajeroId)) pasajeros.set(r.pasajeroId, r.fecha);
  });
  if (porCuenta) {
    vigentes.forEach((d) => { if (!pasajeros.has(d.data().pasajeroId)) pasajeros.set(d.data().pasajeroId, hoy); });
    await Promise.all([...pasajeros.keys()].map((p) =>
      avisar(p, `cuenta_eliminada_${uid}`, "El conductor eliminó su cuenta",
        `${conductor} eliminó su cuenta de CooRuta. Tus viajes con él se cancelaron. Busca otro conductor en Buscar.`)));
    return { canceladas: afectadas.length };
  }
  await Promise.all([...pasajeros.entries()].map(([p, fecha]) =>
    avisar(p, `ruta_eliminada_${rutaId}`, "Se canceló tu viaje",
      `${conductor} eliminó su ruta. Tu viaje del ${fecha} se canceló. Busca otro conductor en Buscar.`)));
  return { canceladas: afectadas.length };
}

// Eliminar cuenta: borra TODO lo del usuario con permisos de servidor (sin los bloqueos de las
// reglas) y al final su usuario de Auth. Sus rutas se borran avisando a los pasajeros; sus reservas
// futuras se cancelan (el conductor recibe el aviso) y luego se borran. Sin excepciones (también con viajes en curso).
exports.eliminarCuentaCompleta = onCall({ timeoutSeconds: 120 }, async (req) => {
  const uid = exigirSesion(req);
  const hoy = hoyLima();
  // Sin excepciones: aunque tenga viajes en curso o pasajeros, se borra todo y se avisa a los afectados.
  const rutas = await db.collection("rutas").where("conductorId", "==", uid).get();
  for (const r of rutas.docs) {
    await borrarRutaConAvisos(uid, r.id, r.ref, r.data(), hoy, true);
    // Viajes de la ruta (con ubicación, paradas y pasajeros en vivo).
    const viajes = await db.collection("viajes").where(FieldPath.documentId(), ">=", `${r.id}_`)
      .where(FieldPath.documentId(), "<", `${r.id}_\uf8ff`).get();
    for (const v of viajes.docs) await db.recursiveDelete(v.ref);
  }
  // Rutas privadas sueltas (sin documento público).
  const privadas = await db.collection("rutasPrivadas").where("conductorId", "==", uid).get().catch(() => null);

  const como = async (col, campo) => (await db.collection(col).where(campo, "==", uid).get()).docs;
  // Como pasajero: termina acuerdos y cancela reservas vigentes (dispara los avisos al conductor).
  const misHabituales = [...await como("habituales", "pasajeroId"), ...await como("habituales", "conductorId")];
  const misReservas = [...await como("reservas", "pasajeroId"), ...await como("reservas", "conductorId")];
  const lote1 = db.batch();
  misHabituales.filter((d) => ["pendiente", "aceptado"].includes(d.data().estado)).forEach((d) =>
    lote1.update(d.ref, { estado: "terminado", hasta: hoy, terminadoPor: uid, motivo: "cuenta_eliminada", actualizadoEn: FieldValue.serverTimestamp() }));
  const vigentes = misReservas.filter((d) => ["pendiente", "aceptada", "invitada"].includes(d.data().estado) && d.data().fecha >= hoy);
  vigentes.forEach((d) => lote1.update(d.ref, { estado: "cancelada", motivo: "cuenta_eliminada", actualizadaEn: FieldValue.serverTimestamp() }));
  await lote1.commit();
  // Avisa una vez a cada conductor con el que tenía viajes o acuerdos.
  const conductores = new Map();
  [...vigentes, ...misHabituales.filter((d) => ["pendiente", "aceptado"].includes(d.data().estado))].forEach((d) => {
    const x = d.data();
    if (x.pasajeroId === uid && x.conductorId && !conductores.has(x.conductorId)) conductores.set(x.conductorId, nombre(x.nombrePasajero, "Un pasajero"));
  });
  await Promise.all([...conductores.entries()].map(([c, quien]) =>
    avisar(c, `cuenta_eliminada_${uid}`, "Un pasajero eliminó su cuenta",
      `${quien} eliminó su cuenta de CooRuta. Sus viajes contigo se cancelaron.`)));

  // Borrado de todos los registros.
  const docs = [
    ...misHabituales, ...misReservas, ...(privadas?.docs ?? []),
    ...await como("busquedas", "pasajeroId"), ...await como("pedidos", "pasajeroId"),
    ...await como("calificaciones", "autorId"), ...await como("miembrosGrupo", "miembroId"),
    ...await como("miembrosGrupo", "conductorId"),
  ];
  const refs = [...new Map(docs.map((d) => [d.ref.path, d.ref])).values()];
  for (const id of ["perfilesPublicos", "miembrosEmpresa", "invitacionesEmpresa", "rutasGrupo"]) refs.push(db.collection(id).doc(uid));
  for (let i = 0; i < refs.length; i += 400) {
    const lote = db.batch();
    refs.slice(i, i + 400).forEach((r) => lote.delete(r));
    await lote.commit();
  }
  await db.recursiveDelete(db.collection("usuarios").doc(uid)); // perfil privado, conductores, tokens
  await getAuth().deleteUser(uid).catch((e) => { if (e.code !== "auth/user-not-found") throw e; });
  return { ok: true };
});
