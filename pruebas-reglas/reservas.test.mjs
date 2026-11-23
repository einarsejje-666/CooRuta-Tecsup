// Pruebas de firestore.rules con el emulador (proyecto demo-*, nunca producción).
// Uso: cd pruebas-reglas && npm install && npm test   (requiere Java 11+ y Node 18+)
// El emulador usa firebase.json y firestore.rules de la raíz del proyecto.
// Cada db(uid) es un cliente propio autenticado y SUJETO A LAS REGLAS (no Admin SDK).
// withSecurityRulesDisabled solo se usa para sembrar datos y leer resultados.
import { test, before, beforeEach, after } from 'node:test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, query, where, getDocs,
         runTransaction, writeBatch, serverTimestamp, increment } from 'firebase/firestore';

let env;
const RUTA = 'ruta1', COND = 'conductor', PAS = 'pasajero', OTRO = 'tercero';
const hoy = () => new Date(Date.now() - 5 * 3600e3).toISOString().slice(0, 10); // Lima
const FUT = '2099-01-01', PAS_F = '2000-01-01';
const idRes = (uid = PAS, f = hoy(), r = RUTA) => `${r}_${uid}_${f}`;
const db = (uid) => env.authenticatedContext(uid).firestore();

const nueva = (o = {}) => ({
  rutaId: RUTA, conductorId: COND, pasajeroId: PAS, nombrePasajero: 'P', nombreConductor: 'C',
  fecha: hoy(), horaSalida: '07:30', subida: { nombre: 'a', latitud: -16.4, longitud: -71.5 },
  bajada: { nombre: 'b', latitud: -16.41, longitud: -71.52 }, kmCompartidos: 10, pasajeCentimos: 250,
  mensaje: '', estado: 'pendiente', creadaEn: serverTimestamp(), ...o,
});
const base = (o = {}) => ({ ...nueva(), creadaEn: new Date(), ...o });
const rutaPublica = (o = {}) => ({ conductorId: COND, nombre: 'C', origen: {}, destino: {},
  recorrido: [{ lat: -16.40, lng: -71.50 }, { lat: -16.41, lng: -71.52 }], dias: [1, 2], horaSalida: '07:30',
  minutoSalida: 450, asientos: 1, tarifaKmCentimos: 25, estado: 'activa', creadaEn: serverTimestamp(),
  tramoPublico: 'automatico', ...o });

async function sembrar(reservas = {}, ruta = {}, otros = {}) {
  await env.withSecurityRulesDisabled(async (c) => {
    const f = c.firestore();
    await setDoc(doc(f, 'rutas', RUTA), { conductorId: COND, estado: 'activa', horaSalida: '07:30',
      asientos: 1, tarifaKmCentimos: 25, ocupadosPorFecha: {}, ...ruta });
    for (const [id, d] of Object.entries(reservas)) await setDoc(doc(f, 'reservas', id), d);
    for (const [ruta2, d] of Object.entries(otros)) await setDoc(doc(f, ...ruta2.split('/')), d);
  });
}
const leer = async (ruta2) => { let d; await env.withSecurityRulesDisabled(async (c) => {
  d = (await getDoc(doc(c.firestore(), ...ruta2.split('/')))).data(); }); return d; };
const ocupadosEn = async (f = hoy()) => (await leer(`rutas/${RUTA}`)).ocupadosPorFecha?.[f] ?? 0;
const estadoDe = async (id) => (await leer(`reservas/${id}`)).estado;

before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-cooruta',
    firestore: { rules: readFileSync(fileURLToPath(new URL('../firestore.rules', import.meta.url)), 'utf8') } });
});
beforeEach(async () => { await env.clearFirestore(); });
after(async () => { await env.cleanup(); });

// Barrera: en el primer intento ambos clientes leen antes de que alguno escriba (fuerza el conflicto).
function barrera(n) {
  let llegados = 0, abrir; const p = new Promise((r) => (abrir = r));
  return async () => { if (++llegados >= n) abrir(); await p; };
}
// Igual que RutasRepositorio.responderSolicitud (aceptar). sinValidar: cliente que omite las comprobaciones.
async function aceptar(f, id, fecha = hoy(), sinValidar = false, esperar = null) {
  let intento = 0;
  return runTransaction(f, async (tx) => {
    const ref = doc(f, 'reservas', id), rr = doc(f, 'rutas', RUTA);
    const r = await tx.get(ref), ru = await tx.get(rr);
    if (esperar && intento++ === 0) await esperar();
    if (!sinValidar && r.data().estado !== 'pendiente') throw new Error('ya respondida');
    const occ = ru.data().ocupadosPorFecha?.[fecha] ?? 0;
    if (!sinValidar && ru.data().asientos - occ <= 0) throw new Error('sin asientos');
    const u = ru.data().ultimaFechaAceptada ?? '';
    tx.update(rr, { [`ocupadosPorFecha.${fecha}`]: increment(1), ultimaReserva: id, ultimaFechaAceptada: fecha > u ? fecha : u });
    tx.update(ref, { estado: 'aceptada', respondidaEn: serverTimestamp() });
  });
}
// Igual que RutasRepositorio.cancelarEnTransaccion.
async function cancelar(f, id, esperar = null) {
  let intento = 0;
  return runTransaction(f, async (tx) => {
    const ref = doc(f, 'reservas', id); const r = (await tx.get(ref)).data();
    const rr = doc(f, 'rutas', r.rutaId); const ru = r.estado === 'aceptada' ? await tx.get(rr) : null;
    if (esperar && intento++ === 0) await esperar();
    if (r.estado === 'cancelada') return; // idempotente
    if (r.estado === 'pendiente') { tx.update(ref, { estado: 'cancelada', actualizadaEn: serverTimestamp() }); return; }
    if (r.estado !== 'aceptada') throw new Error('cambió');
    if ((ru.data().ocupadosPorFecha?.[r.fecha] ?? 0) <= 0) throw new Error('sin registro');
    tx.update(rr, { [`ocupadosPorFecha.${r.fecha}`]: increment(-1), ultimaReserva: id });
    tx.update(ref, { estado: 'cancelada', actualizadaEn: serverTimestamp() });
  });
}
const aceptadaHoy = (o = {}) => base({ estado: 'aceptada', ...o });

// ---------------------------------------------------------------- creación
test('pasajero crea solicitud válida', async () => {
  await sembrar();
  await assertSucceeds(setDoc(doc(db(PAS), 'reservas', idRes()), nueva()));
});
test('id distinto del canónico (duplicado) se rechaza', async () => {
  await sembrar();
  await assertFails(setDoc(doc(db(PAS), 'reservas', 'otro-id'), nueva()));
});
test('no puede falsificar pasajero, conductor, estado, fecha, precio, hora ni campos extra', async () => {
  await sembrar();
  const p = db(PAS);
  await assertFails(setDoc(doc(p, 'reservas', idRes(OTRO)), nueva({ pasajeroId: OTRO })));
  await assertFails(setDoc(doc(p, 'reservas', idRes()), nueva({ conductorId: OTRO })));
  await assertFails(setDoc(doc(p, 'reservas', idRes()), nueva({ estado: 'aceptada' })));
  await assertFails(setDoc(doc(p, 'reservas', idRes(PAS, PAS_F)), nueva({ fecha: PAS_F })));
  await assertFails(setDoc(doc(p, 'reservas', idRes(PAS, '2099-1-1')), nueva({ fecha: '2099-1-1' })));
  await assertFails(setDoc(doc(p, 'reservas', idRes()), nueva({ pasajeCentimos: 0 })));
  await assertFails(setDoc(doc(p, 'reservas', idRes()), nueva({ horaSalida: '09:00' })));
  await assertFails(setDoc(doc(p, 'reservas', idRes()), nueva({ extra: 1 })));
});
test('tarifa: mínimo S/ 2 y redondeo a 50 céntimos', async () => {
  await sembrar();
  await assertSucceeds(setDoc(doc(db(PAS), 'reservas', idRes()), nueva({ kmCompartidos: 3, pasajeCentimos: 200 })));
  await env.clearFirestore(); await sembrar();
  await assertFails(setDoc(doc(db(PAS), 'reservas', idRes()), nueva({ kmCompartidos: 3, pasajeCentimos: 100 })));
  await env.clearFirestore(); await sembrar();
  await assertSucceeds(setDoc(doc(db(PAS), 'reservas', idRes()), nueva({ kmCompartidos: 13, pasajeCentimos: 350 }))); // 325 → 350
});
test('ruta pausada o propia no admite solicitudes', async () => {
  await sembrar({}, { estado: 'pausada' });
  await assertFails(setDoc(doc(db(PAS), 'reservas', idRes()), nueva()));
  await sembrar();
  await assertFails(setDoc(doc(db(COND), 'reservas', idRes(COND)), nueva({ pasajeroId: COND })));
});
test('fecha futura se puede pedir y aceptar; pasada no se acepta ni se reabre', async () => {
  await sembrar({ [idRes(OTRO, PAS_F)]: base({ pasajeroId: OTRO, fecha: PAS_F }),
                  [idRes(PAS, PAS_F, 'r2')]: base({ rutaId: 'r2', fecha: PAS_F, estado: 'rechazada' }) }, { asientos: 3 });
  await assertSucceeds(setDoc(doc(db(PAS), 'reservas', idRes(PAS, FUT)), nueva({ fecha: FUT })));
  await assertSucceeds(aceptar(db(COND), idRes(PAS, FUT), FUT));
  await assertFails(aceptar(db(COND), idRes(OTRO, PAS_F), PAS_F, true));
  await assertFails(updateDoc(doc(db(PAS), 'reservas', idRes(PAS, PAS_F, 'r2')), { estado: 'pendiente', actualizadaEn: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(db(OTRO), 'reservas', idRes(OTRO, PAS_F)), { estado: 'cancelada', actualizadaEn: serverTimestamp() }));
});

// ---------------------------------------------------------------- lectura de reservas
test('solo pasajero y conductor leen la reserva; un tercero no lee ni consulta', async () => {
  await sembrar({ [idRes()]: base() });
  await assertSucceeds(getDoc(doc(db(PAS), 'reservas', idRes())));
  await assertSucceeds(getDoc(doc(db(COND), 'reservas', idRes())));
  await assertFails(getDoc(doc(db(OTRO), 'reservas', idRes())));
  await assertFails(getDocs(collection(db(OTRO), 'reservas')));
  await assertFails(getDocs(query(collection(db(OTRO), 'reservas'), where('rutaId', '==', RUTA))));
  await assertSucceeds(getDocs(query(collection(db(PAS), 'reservas'), where('pasajeroId', '==', PAS))));
  await assertSucceeds(getDocs(query(collection(db(COND), 'reservas'), where('conductorId', '==', COND))));
  await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(), 'reservas')));
});

// ---------------------------------------------------------------- transiciones
test('pasajero cancela pendiente; no puede aceptarse a sí mismo', async () => {
  await sembrar({ [idRes()]: base() });
  const p = db(PAS);
  await assertFails(updateDoc(doc(p, 'reservas', idRes()), { estado: 'aceptada', actualizadaEn: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(p, 'reservas', idRes()), { estado: 'cancelada', actualizadaEn: serverTimestamp() }));
});
test('conductor no reactiva una cancelada por el pasajero; sí reconsidera un rechazo', async () => {
  await sembrar({ [idRes()]: base({ estado: 'cancelada' }), [idRes(OTRO)]: base({ pasajeroId: OTRO, estado: 'rechazada' }) });
  const c = db(COND);
  await assertFails(updateDoc(doc(c, 'reservas', idRes()), { estado: 'pendiente', actualizadaEn: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(c, 'reservas', idRes(OTRO)), { estado: 'pendiente', actualizadaEn: serverTimestamp() }));
});
test('tercero no modifica; nadie cambia precio ni participantes', async () => {
  await sembrar({ [idRes()]: base() });
  await assertFails(updateDoc(doc(db(OTRO), 'reservas', idRes()), { estado: 'cancelada', actualizadaEn: serverTimestamp() }));
  await assertFails(updateDoc(doc(db(PAS), 'reservas', idRes()), { pasajeCentimos: 200 }));
  await assertFails(updateDoc(doc(db(COND), 'reservas', idRes()), { pasajeroId: COND }));
});
test('ninguna reserva se borra (ni pendiente ni aceptada)', async () => {
  await sembrar({ [idRes()]: base(), [idRes(OTRO)]: aceptadaHoy({ pasajeroId: OTRO }) });
  await assertFails(deleteDoc(doc(db(PAS), 'reservas', idRes())));
  await assertFails(deleteDoc(doc(db(OTRO), 'reservas', idRes(OTRO))));
  await assertFails(deleteDoc(doc(db(COND), 'reservas', idRes(OTRO))));
});

// ---------------------------------------------------------------- aceptar: ocupación ligada
test('aceptar sin sumar ocupación, sumando 2 o sin ligar la reserva se rechaza', async () => {
  await sembrar({ [idRes()]: base() }, { asientos: 3 });
  const c = db(COND);
  await assertFails(updateDoc(doc(c, 'reservas', idRes()), { estado: 'aceptada' }));
  await assertFails(runTransaction(c, async (tx) => {
    tx.update(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(2), ultimaReserva: idRes(), ultimaFechaAceptada: hoy() });
    tx.update(doc(c, 'reservas', idRes()), { estado: 'aceptada' });
  }));
  await assertFails(runTransaction(c, async (tx) => {
    tx.update(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(1), ultimaFechaAceptada: hoy() });
    tx.update(doc(c, 'reservas', idRes()), { estado: 'aceptada' });
  }));
});
test('aceptar sumando en otra fecha se rechaza', async () => {
  await sembrar({ [idRes()]: base() }, { asientos: 3 });
  const c = db(COND);
  await assertFails(runTransaction(c, async (tx) => {
    tx.update(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(1), [`ocupadosPorFecha.${FUT}`]: increment(1),
      ultimaReserva: idRes(), ultimaFechaAceptada: FUT });
    tx.update(doc(c, 'reservas', idRes()), { estado: 'aceptada' });
  }));
});
test('no se acepta por encima de la capacidad', async () => {
  await sembrar({ [idRes()]: base() }, { asientos: 1, ocupadosPorFecha: { [hoy()]: 1 } });
  await assertFails(aceptar(db(COND), idRes(), hoy(), true));
});
test('dos aceptaciones concurrentes al último asiento: como máximo una', async () => {
  await sembrar({ [idRes()]: base(), [idRes(OTRO)]: base({ pasajeroId: OTRO }) }, { asientos: 1 });
  const b = barrera(2);
  const r = await Promise.allSettled([aceptar(db(COND), idRes(), hoy(), false, b), aceptar(db(COND), idRes(OTRO), hoy(), false, b)]);
  assertEq(r.filter((x) => x.status === 'fulfilled').length, 1);
  assertEq(await ocupadosEn(), 1);
});
test('último asiento con cliente que omite validaciones: las reglas frenan la segunda', async () => {
  await sembrar({ [idRes()]: base(), [idRes(OTRO)]: base({ pasajeroId: OTRO }) }, { asientos: 1 });
  const b = barrera(2);
  await Promise.allSettled([aceptar(db(COND), idRes(), hoy(), true, b), aceptar(db(COND), idRes(OTRO), hoy(), true, b)]);
  assertEq(await ocupadosEn(), 1);
});
test('doble toque en aceptar no suma dos veces', async () => {
  await sembrar({ [idRes()]: base() }, { asientos: 3 });
  const b = barrera(2);
  await Promise.allSettled([aceptar(db(COND), idRes(), hoy(), false, b), aceptar(db(COND), idRes(), hoy(), false, b)]);
  assertEq(await ocupadosEn(), 1);
});
test('aceptar frente a cancelar: estado final coherente', async () => {
  await sembrar({ [idRes()]: base() }, { asientos: 3 });
  const b = barrera(2);
  await Promise.allSettled([aceptar(db(COND), idRes(), hoy(), false, b), cancelar(db(PAS), idRes(), b)]);
  const est = await estadoDe(idRes()), occ = await ocupadosEn();
  // Si ganó aceptar y luego se reintentó cancelar, la cancelación de la aceptada libera el asiento.
  if (!((est === 'aceptada' && occ === 1) || (est === 'cancelada' && occ === 0)))
    throw new Error(`incoherente: ${est} / ${occ}`);
});

// ---------------------------------------------------------------- cancelar aceptada
test('cancelar una aceptada libera exactamente un asiento y conserva el registro', async () => {
  await sembrar({ [idRes()]: aceptadaHoy() }, { asientos: 2, ocupadosPorFecha: { [hoy()]: 2, [FUT]: 1 } });
  await assertSucceeds(cancelar(db(PAS), idRes()));
  assertEq(await estadoDe(idRes()), 'cancelada');
  assertEq(await ocupadosEn(), 1);
  assertEq(await ocupadosEn(FUT), 1);
});
test('repetir la cancelación no vuelve a restar (app y cliente malicioso)', async () => {
  await sembrar({ [idRes()]: aceptadaHoy() }, { asientos: 2, ocupadosPorFecha: { [hoy()]: 2 } });
  const p = db(PAS);
  await assertSucceeds(cancelar(p, idRes()));
  await assertSucceeds(cancelar(p, idRes())); // no-op
  await assertFails(runTransaction(p, async (tx) => {
    tx.update(doc(p, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(-1), ultimaReserva: idRes() });
  }));
  assertEq(await ocupadosEn(), 1);
});
test('doble toque concurrente en cancelar aceptada resta una sola vez', async () => {
  await sembrar({ [idRes()]: aceptadaHoy() }, { asientos: 2, ocupadosPorFecha: { [hoy()]: 2 } });
  const b = barrera(2);
  await Promise.allSettled([cancelar(db(PAS), idRes(), b), cancelar(db(PAS), idRes(), b)]);
  assertEq(await ocupadosEn(), 1);
});
test('cancelar aceptada sin restar, restando 2, o restando sin cambiar la reserva se rechaza', async () => {
  await sembrar({ [idRes()]: aceptadaHoy() }, { asientos: 3, ocupadosPorFecha: { [hoy()]: 3 } });
  const p = db(PAS);
  await assertFails(updateDoc(doc(p, 'reservas', idRes()), { estado: 'cancelada', actualizadaEn: serverTimestamp() }));
  await assertFails(runTransaction(p, async (tx) => {
    tx.update(doc(p, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(-2), ultimaReserva: idRes() });
    tx.update(doc(p, 'reservas', idRes()), { estado: 'cancelada', actualizadaEn: serverTimestamp() });
  }));
  await assertFails(updateDoc(doc(p, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(-1), ultimaReserva: idRes() }));
});
test('un tercero o el conductor no pueden liberar el asiento de otro', async () => {
  await sembrar({ [idRes()]: aceptadaHoy() }, { asientos: 3, ocupadosPorFecha: { [hoy()]: 1 } });
  await assertFails(cancelar(db(OTRO), idRes()));
  await assertFails(cancelar(db(COND), idRes()));
});
test('no se cancela una aceptada de un día pasado', async () => {
  await sembrar({ [idRes(PAS, PAS_F)]: aceptadaHoy({ fecha: PAS_F }) }, { asientos: 3, ocupadosPorFecha: { [PAS_F]: 1 } });
  await assertFails(cancelar(db(PAS), idRes(PAS, PAS_F)));
});

// ---------------------------------------------------------------- ocupación y capacidad aisladas
test('el dueño no cambia la ocupación aislada, ni la tarifa, ni baja asientos', async () => {
  await sembrar({}, { asientos: 3, ocupadosPorFecha: { [hoy()]: 2 } });
  const c = db(COND);
  await assertFails(updateDoc(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: 0 }));
  await assertFails(updateDoc(doc(c, 'rutas', RUTA), { ocupadosPorFecha: {} }));
  await assertFails(updateDoc(doc(c, 'rutas', RUTA), { ultimaFechaAceptada: '' }));
  await assertFails(updateDoc(doc(c, 'rutas', RUTA), { tarifaKmCentimos: 10 }));
  await assertFails(updateDoc(doc(c, 'rutas', RUTA), { asientos: 1 }));
  await assertSucceeds(updateDoc(doc(c, 'rutas', RUTA), { asientos: 4, alias: 'Casa → Tecsup', estado: 'pausada' }));
});
test('ligar la ocupación a una reserva de otra ruta o que no cambia se rechaza', async () => {
  await sembrar({ [idRes(PAS, hoy(), 'r2')]: base({ rutaId: 'r2' }), [idRes()]: aceptadaHoy() }, { asientos: 3 });
  const c = db(COND);
  await assertFails(updateDoc(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(1), ultimaReserva: idRes(PAS, hoy(), 'r2') }));
  await assertFails(updateDoc(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(1), ultimaReserva: idRes(), ultimaFechaAceptada: hoy() }));
});
test('lote con dos reservas y la misma ultimaReserva no acepta dos', async () => {
  await sembrar({ [idRes()]: base(), [idRes(OTRO)]: base({ pasajeroId: OTRO }) }, { asientos: 3 });
  const c = db(COND);
  const unaEscritura = writeBatch(c);
  unaEscritura.update(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(1), ultimaReserva: idRes(), ultimaFechaAceptada: hoy() });
  unaEscritura.update(doc(c, 'reservas', idRes()), { estado: 'aceptada' });
  unaEscritura.update(doc(c, 'reservas', idRes(OTRO)), { estado: 'aceptada' });
  await assertFails(unaEscritura.commit());
  const dosEscrituras = writeBatch(c);
  dosEscrituras.update(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(1), ultimaReserva: idRes(), ultimaFechaAceptada: hoy() });
  dosEscrituras.update(doc(c, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(1), ultimaReserva: idRes(OTRO) });
  dosEscrituras.update(doc(c, 'reservas', idRes()), { estado: 'aceptada' });
  dosEscrituras.update(doc(c, 'reservas', idRes(OTRO)), { estado: 'aceptada' });
  await assertFails(dosEscrituras.commit());
  assertEq(await ocupadosEn(), 0);
});
test('lote con dos aceptadas y una sola resta no cancela ambas', async () => {
  await sembrar({ [idRes()]: aceptadaHoy(), [idRes(PAS, FUT)]: aceptadaHoy({ fecha: FUT }) },
                { asientos: 3, ocupadosPorFecha: { [hoy()]: 1, [FUT]: 1 } });
  const p = db(PAS), lote = writeBatch(p);
  lote.update(doc(p, 'rutas', RUTA), { [`ocupadosPorFecha.${hoy()}`]: increment(-1), ultimaReserva: idRes() });
  lote.update(doc(p, 'reservas', idRes()), { estado: 'cancelada', actualizadaEn: serverTimestamp() });
  lote.update(doc(p, 'reservas', idRes(PAS, FUT)), { estado: 'cancelada', actualizadaEn: serverTimestamp() });
  await assertFails(lote.commit());
  assertEq(await ocupadosEn(), 1); assertEq(await ocupadosEn(FUT), 1);
});
test('rutas: el dueño no puede transferir su ruta', async () => {
  await sembrar();
  await assertFails(updateDoc(doc(db(COND), 'rutas', RUTA), { conductorId: OTRO }));
});

// ---------------------------------------------------------------- borrado de rutas
test('no se borra una ruta con aceptaciones de hoy en adelante; sí cuando ya pasaron', async () => {
  await sembrar({}, { ultimaFechaAceptada: hoy() });
  await assertFails(deleteDoc(doc(db(COND), 'rutas', RUTA)));
  await sembrar({}, { ultimaFechaAceptada: PAS_F });
  await assertSucceeds(deleteDoc(doc(db(COND), 'rutas', RUTA)));
});
test('lote de borrado de ruta (app): rechaza pendientes y borra ruta y parte privada', async () => {
  await sembrar({ [idRes()]: base() }, {}, { [`rutasPrivadas/${RUTA}`]: { conductorId: COND } });
  const c = db(COND), lote = writeBatch(c);
  lote.update(doc(c, 'reservas', idRes()), { estado: 'rechazada', respondidaEn: serverTimestamp() });
  lote.delete(doc(c, 'rutasPrivadas', RUTA));
  lote.delete(doc(c, 'rutas', RUTA));
  await assertSucceeds(lote.commit());
  assertEq(await estadoDe(idRes()), 'rechazada');
});

// ---------------------------------------------------------------- datos públicos y privados
test('ruta pública: sin coordenadas de origen/destino ni totales; tarifa fija', async () => {
  await env.clearFirestore();
  const c = db(COND);
  await assertSucceeds(setDoc(doc(c, 'rutas', 'n1'), rutaPublica()));
  await assertFails(setDoc(doc(c, 'rutas', 'n2'), rutaPublica({ origen: { nombre: 'Casa', latitud: -16.4, longitud: -71.5 } })));
  await assertFails(setDoc(doc(c, 'rutas', 'n6'), rutaPublica({ origen: { nombre: 'Calle Mercaderes 123' } })));
  await assertSucceeds(setDoc(doc(c, 'rutas', 'n7'), rutaPublica({ origen: { zona: 'Cayma' } })));
  await assertFails(setDoc(doc(c, 'rutas', 'n3'), rutaPublica({ distanciaMetros: 12000 })));
  await assertFails(setDoc(doc(c, 'rutas', 'n4'), rutaPublica({ tarifaKmCentimos: 10 })));
  await assertFails(setDoc(doc(c, 'rutas', 'n5'), rutaPublica({ ocupadosPorFecha: { [hoy()]: -5 } })));
  await assertFails(updateDoc(doc(c, 'rutas', 'n1'), { origen: { nombre: 'Casa', latitud: -16.4, longitud: -71.5 } }));
  await assertFails(updateDoc(doc(c, 'rutas', 'n1'), { pasajeCentimos: 300 }));
});
test('tramo público: campo obligatorio y coherente con el recorrido', async () => {
  await env.clearFirestore();
  const c = db(COND);
  const { tramoPublico: _t, ...sinTramo } = rutaPublica();
  await assertFails(setDoc(doc(c, 'rutas', 't0'), sinTramo));                                         // falta el campo
  await assertFails(setDoc(doc(c, 'rutas', 't1'), rutaPublica({ tramoPublico: 'otro' })));
  await assertSucceeds(setDoc(doc(c, 'rutas', 't2'), rutaPublica({ tramoPublico: 'elegido' })));
  await assertFails(setDoc(doc(c, 'rutas', 't3'), rutaPublica({ tramoPublico: 'elegido', recorrido: [{ lat: -16.4, lng: -71.5 }] })));
  await assertFails(setDoc(doc(c, 'rutas', 't4'), rutaPublica({ tramoPublico: 'ninguno' })));        // ninguno con geometría
  const { recorrido: _r, ...sinRecorrido } = rutaPublica({ tramoPublico: 'ninguno' });
  await assertSucceeds(setDoc(doc(c, 'rutas', 't5'), sinRecorrido));                                  // guardar sin Buscar
  await assertFails(updateDoc(doc(c, 'rutas', 't5'), { tramoPublico: 'automatico' }));               // sin geometría
  await assertFails(updateDoc(doc(c, 'rutas', 't2'), { recorrido: [{ lat: -16.4, lng: -71.5 }] }));  // deja de ser utilizable
  await assertSucceeds(updateDoc(doc(c, 'rutas', 't2'), { alias: 'Casa → Tecsup' }));
});
test('rutasPrivadas: solo el dueño; ni el pasajero aceptado ni terceros', async () => {
  await sembrar({ [idRes()]: aceptadaHoy() }, {}, { [`rutasPrivadas/${RUTA}`]: { conductorId: COND, origen: { latitud: -16.4 } } });
  await assertSucceeds(getDoc(doc(db(COND), 'rutasPrivadas', RUTA)));
  await assertSucceeds(getDocs(query(collection(db(COND), 'rutasPrivadas'), where('conductorId', '==', COND))));
  await assertFails(getDoc(doc(db(PAS), 'rutasPrivadas', RUTA)));
  await assertFails(getDoc(doc(db(OTRO), 'rutasPrivadas', RUTA)));
  await assertFails(getDocs(collection(db(PAS), 'rutasPrivadas')));
  await assertFails(setDoc(doc(db(OTRO), 'rutasPrivadas', RUTA), { conductorId: OTRO }));
});
test('publicar (app): ruta pública y privada en un lote', async () => {
  await env.clearFirestore();
  const c = db(COND), lote = writeBatch(c);
  lote.set(doc(c, 'rutas', 'n1'), rutaPublica());
  lote.set(doc(c, 'rutasPrivadas', 'n1'), { conductorId: COND, origen: { nombre: 'Casa', latitud: -16.4, longitud: -71.5 } });
  await assertSucceeds(lote.commit());
});
test('pedidos: solo puntos aproximados; no se suplanta; días pasados solo los ve su dueño', async () => {
  await env.clearFirestore();
  const p = db(PAS), id = `${PAS}_${hoy()}`;
  const ped = (o = {}) => ({ pasajeroId: PAS, nombre: 'P', fecha: hoy(), origen: { latitud: -16.40, longitud: -71.52 },
    destino: { latitud: -16.43, longitud: -71.5 }, horaSalida: '07:30', minutoSalida: 450, creadaEn: serverTimestamp(), ...o });
  await assertFails(setDoc(doc(p, 'pedidos', id), ped({ origen: { latitud: -16.40123, longitud: -71.52 } })));
  await assertFails(setDoc(doc(p, 'pedidos', id), ped({ origen: { nombre: 'Mi casa', latitud: -16.40, longitud: -71.52 } })));
  await assertSucceeds(setDoc(doc(p, 'pedidos', id), ped()));
  await assertFails(setDoc(doc(db(OTRO), 'pedidos', id), ped({ pasajeroId: OTRO })));
  await assertSucceeds(getDocs(query(collection(db(COND), 'pedidos'), where('fecha', '==', hoy()))));
  await env.withSecurityRulesDisabled(async (c) => {
    await setDoc(doc(c.firestore(), 'pedidos', `${PAS}_${PAS_F}`), ped({ fecha: PAS_F })); });
  await assertFails(getDoc(doc(db(COND), 'pedidos', `${PAS}_${PAS_F}`)));
  await assertSucceeds(getDocs(query(collection(p, 'pedidos'), where('pasajeroId', '==', PAS))));
});

// ================================================================ viaje en curso
const V = () => `${RUTA}_${hoy()}`;
const viajeBase = (o = {}) => ({ conductorId: COND, rutaId: RUTA, fecha: hoy(), estado: 'preparando',
  creadoEn: new Date(), actualizadoEn: new Date(), ...o });
async function sembrarViaje(viaje = {}, extra = {}) {
  await sembrar({ [idRes()]: aceptadaHoy(), [idRes(OTRO)]: base({ pasajeroId: OTRO }) },
                { asientos: 3, ocupadosPorFecha: { [hoy()]: 1 } },
                viaje === null ? extra : { [`viajes/${V()}`]: viajeBase(viaje), ...extra });
}
// Igual que ViajesRepositorio.iniciarSalida: transacción que crea o reutiliza.
async function iniciarSalida(f, esperar = null) {
  let intento = 0;
  return runTransaction(f, async (tx) => {
    const ref = doc(f, 'viajes', V());
    const actual = await tx.get(ref);
    if (esperar && intento++ === 0) await esperar();
    if (!actual.exists()) tx.set(ref, { conductorId: COND, rutaId: RUTA, fecha: hoy(), estado: 'preparando',
      creadoEn: serverTimestamp(), actualizadoEn: serverTimestamp() });
  });
}
const cambio = (estado, extra = {}) => ({ estado, actualizadoEn: serverTimestamp(), ...extra });

test('viaje: inicio idempotente; solo el dueño, con id, fecha y estado válidos', async () => {
  await sembrarViaje(null);
  const b = barrera(2);
  const r = await Promise.allSettled([iniciarSalida(db(COND), b), iniciarSalida(db(COND), b)]);
  assertEq(r.filter((x) => x.status === 'fulfilled').length, 2);
  assertEq((await leer(`viajes/${V()}`)).estado, 'preparando');
  await assertSucceeds(iniciarSalida(db(COND))); // reintento: no cambia nada
  await env.clearFirestore(); await sembrarViaje(null);
  const nuevo = (o = {}) => ({ conductorId: COND, rutaId: RUTA, fecha: hoy(), estado: 'preparando',
    creadoEn: serverTimestamp(), actualizadoEn: serverTimestamp(), ...o });
  await assertFails(setDoc(doc(db(OTRO), 'viajes', V()), nuevo({ conductorId: OTRO })));
  await assertFails(setDoc(doc(db(COND), 'viajes', V()), nuevo({ estado: 'en_curso' })));
  await assertFails(setDoc(doc(db(COND), 'viajes', `${RUTA}_${FUT}`), nuevo({ fecha: FUT })));
  await assertFails(setDoc(doc(db(COND), 'viajes', 'otro-id'), nuevo()));
  await assertFails(setDoc(doc(db(COND), 'viajes', V()), nuevo({ pasajeros: [PAS] })));
});
// Día ISO de hoy en Lima (1 = lunes … 7 = domingo), como `dias` de la ruta.
const diaHoyLima = () => { const d = new Date(Date.now() - 5 * 3600e3).getUTCDay(); return d === 0 ? 7 : d; };
test('viaje: solo se inicia el día que sale la ruta (cliente que omite la UI)', async () => {
  const otroDia = (diaHoyLima() % 7) + 1;
  await sembrar({}, { dias: [otroDia] });
  await assertFails(iniciarSalida(db(COND)));
  await sembrar({}, { dias: [diaHoyLima(), otroDia] });
  await assertSucceeds(iniciarSalida(db(COND)));
});
test('viaje: transiciones y campos modificables', async () => {
  await sembrarViaje();
  const c = db(COND), ref = doc(c, 'viajes', V());
  await assertFails(updateDoc(ref, cambio('saliendo')));                                   // sin salidaEn
  await assertFails(updateDoc(ref, cambio('saliendo', { salidaEn: new Date(0) })));         // hora falsa
  await assertFails(updateDoc(ref, cambio('preparando', { rutaId: 'otra' })));
  await assertFails(updateDoc(doc(db(PAS), 'viajes', V()), cambio('saliendo', { salidaEn: serverTimestamp() })));
  await assertSucceeds(updateDoc(ref, cambio('saliendo', { salidaEn: serverTimestamp() })));
  await assertFails(updateDoc(ref, cambio('preparando')));
  await assertSucceeds(updateDoc(ref, cambio('en_curso', { iniciadoEn: serverTimestamp() })));
  await assertSucceeds(updateDoc(ref, cambio('finalizado', { finalizadoEn: serverTimestamp() })));
  await assertFails(updateDoc(ref, cambio('en_curso', { iniciadoEn: serverTimestamp() })));
  await assertFails(deleteDoc(ref));
});
test('viaje: lo leen el conductor y pasajeros con reserva aceptada; nadie más', async () => {
  await sembrarViaje({}, { [`reservas/${idRes('tercero2')}`]: base({ pasajeroId: 'tercero2', estado: 'cancelada' }) });
  await assertSucceeds(getDoc(doc(db(COND), 'viajes', V())));
  await assertSucceeds(getDoc(doc(db(PAS), 'viajes', V())));
  await assertFails(getDoc(doc(db(OTRO), 'viajes', V())));       // reserva pendiente
  await assertFails(getDoc(doc(db('tercero2'), 'viajes', V()))); // reserva cancelada
  await assertFails(getDoc(doc(db('nadie'), 'viajes', V())));    // sin reserva
  await assertFails(getDocs(collection(db(PAS), 'viajes')));
});
test('ubicación: solo el conductor la escribe; pasajero vigente la lee; termina al finalizar', async () => {
  await sembrarViaje();
  const ubi = (f) => doc(f, 'viajes', V(), 'ubicacion', 'actual');
  const punto = { latitud: -16.4, longitud: -71.5, activa: true, tomadaEn: new Date(), en: serverTimestamp() };
  await assertSucceeds(setDoc(ubi(db(COND)), punto));
  await assertFails(setDoc(ubi(db(PAS)), punto));
  await assertFails(setDoc(ubi(db(COND)), { ...punto, en: new Date(0) }));
  await assertFails(setDoc(ubi(db(COND)), { ...punto, pasajero: PAS }));
  await assertSucceeds(updateDoc(ubi(db(COND)), { activa: false, en: serverTimestamp() })); // pausa
  await assertSucceeds(getDoc(ubi(db(PAS))));
  await assertFails(getDoc(ubi(db(OTRO))));
  await assertFails(getDoc(ubi(db('nadie'))));
  // Finalizar (como la app): borra la ubicación en la misma transacción.
  const c = db(COND);
  await assertSucceeds(runTransaction(c, async (tx) => {
    await tx.get(doc(c, 'viajes', V()));
    tx.delete(ubi(c));
    tx.update(doc(c, 'viajes', V()), cambio('finalizado', { finalizadoEn: serverTimestamp() }));
  }));
  await assertFails(getDoc(ubi(db(PAS))));
  await assertFails(setDoc(ubi(db(COND)), punto));
});
test('ubicación: hora de la muestra separada; no se reenvía una muestra vieja como actual', async () => {
  await sembrarViaje();
  const ubi = doc(db(COND), 'viajes', V(), 'ubicacion', 'actual');
  const t0 = new Date(Date.now() - 20_000);
  const m = (o = {}) => ({ latitud: -16.4, longitud: -71.5, activa: true, tomadaEn: t0, en: serverTimestamp(), ...o });
  const { tomadaEn: _sinHora, ...sinMuestra } = m();
  await assertFails(setDoc(ubi, sinMuestra));                                         // sin hora de muestra
  await assertFails(setDoc(ubi, m({ tomadaEn: new Date(Date.now() - 20 * 60_000) })));  // muestra de hace 20 min
  await assertFails(setDoc(ubi, m({ tomadaEn: new Date(Date.now() + 10 * 60_000) })));  // del futuro
  await assertSucceeds(setDoc(ubi, m()));
  await assertFails(setDoc(ubi, m({ latitud: -16.41 })));                               // posición nueva, misma muestra
  await assertFails(setDoc(ubi, m({ tomadaEn: new Date(t0.getTime() - 5_000) })));     // muestra más vieja
  await assertSucceeds(updateDoc(ubi, { activa: false, en: serverTimestamp() }));      // pausa sin tocar la muestra
  await assertSucceeds(setDoc(ubi, m({ tomadaEn: new Date() })));                       // muestra nueva (detenido)
});
test('ubicación: deja de verse si la reserva del pasajero ya no está aceptada', async () => {
  await sembrarViaje({}, { [`viajes/${V()}/ubicacion/actual`]: { latitud: -16.4, longitud: -71.5, activa: true, tomadaEn: new Date(), en: new Date() } });
  await assertSucceeds(getDoc(doc(db(PAS), 'viajes', V(), 'ubicacion', 'actual')));
  await assertSucceeds(cancelar(db(PAS), idRes()));
  await assertFails(getDoc(doc(db(PAS), 'viajes', V(), 'ubicacion', 'actual')));
});
test('paradas: solo el conductor, en curso y para reservas aceptadas; el pasajero ve solo la suya', async () => {
  await sembrarViaje({ estado: 'en_curso' });
  const par = (f, rid) => doc(f, 'viajes', V(), 'paradas', rid);
  const p = (pasajeroId, estado) => ({ pasajeroId, conductorId: COND, estado, actualizadoEn: serverTimestamp() });
  await assertSucceeds(getDoc(par(db(PAS), idRes())));            // la suya aún no existe
  await assertFails(getDoc(par(db(PAS), idRes(OTRO))));           // la de otro, tampoco
  await assertFails(setDoc(par(db(PAS), idRes()), p(PAS, 'a_bordo')));
  await assertFails(setDoc(par(db(COND), idRes(OTRO)), p(OTRO, 'llegue'))); // reserva pendiente
  await assertFails(setDoc(par(db(COND), idRes()), p(OTRO, 'llegue')));     // pasajero falso
  await assertFails(setDoc(par(db(COND), idRes()), p(PAS, 'aceptada')));    // estado de reserva
  await assertSucceeds(setDoc(par(db(COND), idRes()), p(PAS, 'llegue')));
  await assertSucceeds(getDoc(par(db(PAS), idRes())));
  await assertFails(getDoc(par(db(OTRO), idRes())));
  await assertFails(getDocs(collection(db(PAS), 'viajes', V(), 'paradas')));
  await assertSucceeds(updateDoc(par(db(COND), idRes()), { estado: 'a_bordo', actualizadoEn: serverTimestamp() }));
  await assertFails(updateDoc(par(db(COND), idRes()), { estado: 'llegue', actualizadoEn: serverTimestamp() }));
  // La reserva y los asientos no cambian por el viaje.
  assertEq(await estadoDe(idRes()), 'aceptada');
  assertEq(await ocupadosEn(), 1);
});
test('paradas: no se marcan antes de iniciar el recorrido', async () => {
  await sembrarViaje({ estado: 'saliendo', salidaEn: new Date() });
  await assertFails(setDoc(doc(db(COND), 'viajes', V(), 'paradas', idRes()),
    { pasajeroId: PAS, conductorId: COND, estado: 'llegue', actualizadoEn: serverTimestamp() }));
});

function assertEq(a, b) { if (a !== b) throw new Error(`esperado ${b}, obtenido ${a}`); }

// ---------------------------------------------------------------- Mi empresa
const EMP = 'empresa-a';
const invitacionEmpresa = (empresaId = EMP, venceEn = new Date(Date.now() + 86400e3)) => ({ empresaId, venceEn });
async function prepararEmpresa(extra = {}) {
  await sembrar({}, {}, {
    [`empresas/${EMP}`]: { nombre: 'Comunidad A', activa: true },
    'empresas/empresa-b': { nombre: 'Comunidad B', activa: true },
    [`invitacionesEmpresa/${PAS}`]: invitacionEmpresa(), ...extra,
  });
}
async function unirseEmpresa(f, uid = PAS, id = EMP, extra = {}) {
  const lote = writeBatch(f);
  lote.set(doc(f, 'miembrosEmpresa', uid), { empresaId: id, unidoEn: serverTimestamp(), ...extra });
  lote.delete(doc(f, 'invitacionesEmpresa', uid));
  return lote.commit();
}
test('empresa: invitación individual se acepta y consume atómicamente', async () => {
  await prepararEmpresa();
  await assertSucceeds(getDoc(doc(db(PAS), 'empresas', EMP)));
  await assertSucceeds(unirseEmpresa(db(PAS)));
  if ((await leer(`miembrosEmpresa/${PAS}`)).empresaId !== EMP) throw Error('membresía faltante');
  if (await leer(`invitacionesEmpresa/${PAS}`)) throw Error('invitación no consumida');
});
test('empresa: sin invitación no puede elegir empresa ni crear una', async () => {
  await prepararEmpresa();
  await assertFails(unirseEmpresa(db(OTRO), OTRO));
  await assertFails(setDoc(doc(db(PAS), 'empresas', 'falsa'), { nombre: 'Falsa', activa: true }));
  await assertFails(setDoc(doc(db(OTRO), 'invitacionesEmpresa', OTRO), invitacionEmpresa()));
});
test('empresa: no admite otra empresa, otro uid, rol ni campos personales', async () => {
  await prepararEmpresa();
  await assertFails(unirseEmpresa(db(PAS), PAS, 'empresa-b'));
  await assertFails(unirseEmpresa(db(OTRO), PAS));
  await assertFails(unirseEmpresa(db(PAS), PAS, EMP, { rol: 'admin' }));
  await assertFails(unirseEmpresa(db(PAS), PAS, EMP, { correo: 'privado@example.test' }));
});
test('empresa: invitación vencida o empresa desactivada impide unirse', async () => {
  await prepararEmpresa({ [`invitacionesEmpresa/${PAS}`]: invitacionEmpresa(EMP, new Date(0)) });
  await assertFails(unirseEmpresa(db(PAS)));
  await prepararEmpresa({ [`empresas/${EMP}`]: { nombre: 'A', activa: false } });
  await assertFails(unirseEmpresa(db(PAS)));
});
test('empresa: aceptación exige consumir invitación y hora de servidor', async () => {
  await prepararEmpresa();
  await assertFails(setDoc(doc(db(PAS), 'miembrosEmpresa', PAS), { empresaId: EMP, unidoEn: serverTimestamp() }));
  await assertFails(unirseEmpresa(db(PAS), PAS, EMP, { unidoEn: new Date(0) }));
});
test('empresa: invitaciones privadas, no enumerables ni editables', async () => {
  await prepararEmpresa();
  await assertSucceeds(getDoc(doc(db(PAS), 'invitacionesEmpresa', PAS)));
  await assertFails(getDoc(doc(db(OTRO), 'invitacionesEmpresa', PAS)));
  await assertFails(getDocs(collection(db(PAS), 'invitacionesEmpresa')));
  await assertFails(updateDoc(doc(db(PAS), 'invitacionesEmpresa', PAS), { empresaId: 'empresa-b' }));
  await assertFails(getDocs(collection(db(PAS), 'empresas')));
});
test('empresa: directorio solo consultable por miembros de la misma empresa', async () => {
  await prepararEmpresa({
    [`miembrosEmpresa/${COND}`]: { empresaId: EMP, unidoEn: new Date() },
    [`miembrosEmpresa/${OTRO}`]: { empresaId: 'empresa-b', unidoEn: new Date() },
  });
  await assertSucceeds(unirseEmpresa(db(PAS)));
  const lista = await assertSucceeds(getDocs(query(collection(db(PAS), 'miembrosEmpresa'), where('empresaId', '==', EMP))));
  if (lista.size !== 2) throw Error('directorio incompleto');
  await assertSucceeds(getDoc(doc(db(PAS), 'miembrosEmpresa', COND)));
  await assertFails(getDoc(doc(db(PAS), 'miembrosEmpresa', OTRO)));
  await assertFails(getDocs(collection(db(PAS), 'miembrosEmpresa')));
  await assertFails(getDocs(query(collection(db(PAS), 'miembrosEmpresa'), where('empresaId', '==', 'empresa-b'))));
});
test('empresa: invitado sin aceptar y anónimo no leen directorio', async () => {
  await prepararEmpresa({ [`miembrosEmpresa/${COND}`]: { empresaId: EMP, unidoEn: new Date() } });
  await assertFails(getDocs(query(collection(db(PAS), 'miembrosEmpresa'), where('empresaId', '==', EMP))));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'miembrosEmpresa', COND)));
});
test('empresa: miembro no puede cambiar empresa ni eliminar a un compañero', async () => {
  await prepararEmpresa({ [`miembrosEmpresa/${COND}`]: { empresaId: EMP, unidoEn: new Date() } });
  await unirseEmpresa(db(PAS));
  await assertFails(updateDoc(doc(db(PAS), 'miembrosEmpresa', PAS), { empresaId: 'empresa-b' }));
  await assertFails(deleteDoc(doc(db(PAS), 'miembrosEmpresa', COND)));
});
test('empresa: salir retira acceso y exige nueva invitación para volver', async () => {
  await prepararEmpresa({ [`miembrosEmpresa/${COND}`]: { empresaId: EMP, unidoEn: new Date() } });
  await unirseEmpresa(db(PAS));
  await assertSucceeds(deleteDoc(doc(db(PAS), 'miembrosEmpresa', PAS)));
  await assertFails(getDoc(doc(db(PAS), 'miembrosEmpresa', COND)));
  await assertFails(unirseEmpresa(db(PAS)));
  await assertSucceeds(getDoc(doc(db(PAS), 'miembrosEmpresa', PAS)));
});
test('empresa: revocación del operador y desactivación retiran directorio', async () => {
  await prepararEmpresa();
  await unirseEmpresa(db(PAS));
  await env.withSecurityRulesDisabled(c => updateDoc(doc(c.firestore(), 'empresas', EMP), { activa: false }));
  await assertFails(getDocs(query(collection(db(PAS), 'miembrosEmpresa'), where('empresaId', '==', EMP))));
  await assertSucceeds(getDoc(doc(db(PAS), 'miembrosEmpresa', PAS)));
  await env.withSecurityRulesDisabled(c => deleteDoc(doc(c.firestore(), 'miembrosEmpresa', PAS)));
  await assertFails(getDoc(doc(db(PAS), 'empresas', EMP)));
});
test('empresa: pertenecer no permite leer reservas ni coordenadas de compañeros', async () => {
  await prepararEmpresa({
    [`miembrosEmpresa/${COND}`]: { empresaId: EMP, unidoEn: new Date() },
    [`rutasPrivadas/${RUTA}`]: { conductorId: COND },
    'reservas/ajena': base({ pasajeroId: OTRO }),
  });
  await unirseEmpresa(db(PAS));
  await assertFails(getDoc(doc(db(PAS), 'rutasPrivadas', RUTA)));
  await assertFails(getDoc(doc(db(PAS), 'reservas', 'ajena')));
});
test('empresa: invitación concurrente produce una sola pertenencia', async () => {
  await prepararEmpresa();
  const resultados = await Promise.allSettled([unirseEmpresa(db(PAS)), unirseEmpresa(db(PAS))]);
  if (resultados.filter(x => x.status === 'fulfilled').length !== 1) throw Error('aceptación duplicada');
});

// ---------- Calificaciones y perfil público (bloque 2)
// Igual que CalificacionesRepositorio.calificar: calificación + suma del perfil en una transacción.
async function calificar(uid, dest, rid, estrellas = 5, trampa = 0) {
  const f = db(uid); const cid = `${rid}_${uid}`;
  const pref = doc(f, 'perfilesPublicos', dest);
  return runTransaction(f, async (tx) => {
    const p = await tx.get(pref);
    const suma = (p.data()?.calificacionSuma ?? 0) + estrellas + trampa;
    const cant = (p.data()?.calificacionCantidad ?? 0) + 1;
    tx.set(doc(f, 'calificaciones', cid), { reservaId: rid, autorId: uid, destinatarioId: dest,
      estrellas, comentario: 'bien', creadaEn: serverTimestamp() });
    const campos = { calificacionSuma: suma, calificacionCantidad: cant, ultimaCalificacion: cid };
    if (p.exists()) tx.update(pref, campos); else tx.set(pref, campos);
  });
}
const resPasada = () => ({ [`reservas/${idRes(PAS, PAS_F)}`]: base({ fecha: PAS_F, estado: 'aceptada' }) });

test('calificación: ambos se califican tras un viaje pasado y el promedio suma bien', async () => {
  await sembrar({}, {}, resPasada());
  await assertSucceeds(calificar(PAS, COND, idRes(PAS, PAS_F), 4));
  await assertSucceeds(calificar(COND, PAS, idRes(PAS, PAS_F), 5));
  const p = await leer(`perfilesPublicos/${COND}`);
  if (p.calificacionSuma !== 4 || p.calificacionCantidad !== 1) throw Error('suma incorrecta');
});
test('calificación: no antes de terminar el viaje de hoy; sí cuando finaliza', async () => {
  const rid = idRes(PAS, hoy());
  await sembrar({}, {}, { [`reservas/${rid}`]: base({ estado: 'aceptada' }),
    [`viajes/${RUTA}_${hoy()}`]: { conductorId: COND, rutaId: RUTA, fecha: hoy(), estado: 'en_curso' } });
  await assertFails(calificar(PAS, COND, rid));
  await env.withSecurityRulesDisabled(c => updateDoc(doc(c.firestore(), 'viajes', `${RUTA}_${hoy()}`), { estado: 'finalizado' }));
  await assertSucceeds(calificar(PAS, COND, rid));
});
test('calificación: una sola vez, solo participantes y sin inflar la suma', async () => {
  await sembrar({}, {}, resPasada());
  await assertFails(calificar(OTRO, COND, idRes(PAS, PAS_F)));
  await assertFails(calificar(PAS, COND, idRes(PAS, PAS_F), 5, 10));
  await assertFails(calificar(PAS, OTRO, idRes(PAS, PAS_F)));
  await assertSucceeds(calificar(PAS, COND, idRes(PAS, PAS_F)));
  await assertFails(calificar(PAS, COND, idRes(PAS, PAS_F)));
});
test('calificación: reserva pendiente o cancelada no se califica', async () => {
  await sembrar({}, {}, { [`reservas/${idRes(PAS, PAS_F)}`]: base({ fecha: PAS_F, estado: 'cancelada' }) });
  await assertFails(calificar(PAS, COND, idRes(PAS, PAS_F)));
});
test('perfil público: el dueño edita su vehículo pero no su promedio; otros no editan el vehículo', async () => {
  const propio = { nombre: 'C', vehiculo: { marca: 'Kia', modelo: 'Rio', color: 'gris', placa: 'V4K-219' },
    actualizadoEn: serverTimestamp() };
  await assertSucceeds(setDoc(doc(db(COND), 'perfilesPublicos', COND), propio));
  await assertFails(updateDoc(doc(db(COND), 'perfilesPublicos', COND), { calificacionSuma: 50 }));
  await assertFails(updateDoc(doc(db(PAS), 'perfilesPublicos', COND), { vehiculo: { marca: 'X' } }));
  await assertSucceeds(getDoc(doc(db(PAS), 'perfilesPublicos', COND)));
  await assertFails(setDoc(doc(db(COND), 'perfilesPublicos', COND),
    { ...propio, vehiculo: { ...propio.vehiculo, placa: 'DEMASIADO-LARGA-123' } }));
});

// ---------- Chat de la reserva (bloque 3)
const msj = (uid, texto = 'hola') => ({ autorId: uid, texto, creadoEn: serverTimestamp() });
test('chat: pasajero y conductor escriben y leen; un tercero no', async () => {
  const rid = idRes(PAS, hoy());
  await sembrar({ [rid]: base({ estado: 'aceptada' }) });
  await assertSucceeds(setDoc(doc(db(PAS), 'reservas', rid, 'mensajes', 'm1'), msj(PAS)));
  await assertSucceeds(setDoc(doc(db(COND), 'reservas', rid, 'mensajes', 'm2'), msj(COND)));
  await assertSucceeds(getDocs(collection(db(COND), 'reservas', rid, 'mensajes')));
  await assertFails(getDocs(collection(db(OTRO), 'reservas', rid, 'mensajes')));
  await assertFails(setDoc(doc(db(OTRO), 'reservas', rid, 'mensajes', 'm3'), msj(OTRO)));
});
test('chat: no se suplanta, no se edita y se cierra tras el viaje o al cancelar', async () => {
  const rid = idRes(PAS, hoy()), pasada = idRes(PAS, PAS_F), cancelada = idRes(PAS, FUT);
  await sembrar({ [rid]: base({ estado: 'aceptada' }), [pasada]: base({ fecha: PAS_F, estado: 'aceptada' }),
    [cancelada]: base({ fecha: FUT, estado: 'cancelada' }) });
  await assertFails(setDoc(doc(db(PAS), 'reservas', rid, 'mensajes', 'm1'), msj(COND)));
  await assertFails(setDoc(doc(db(PAS), 'reservas', rid, 'mensajes', 'm1'), msj(PAS, 'x'.repeat(501))));
  await assertSucceeds(setDoc(doc(db(PAS), 'reservas', rid, 'mensajes', 'm1'), msj(PAS)));
  await assertFails(updateDoc(doc(db(PAS), 'reservas', rid, 'mensajes', 'm1'), { texto: 'otro' }));
  await assertFails(setDoc(doc(db(PAS), 'reservas', pasada, 'mensajes', 'm1'), msj(PAS)));
  await assertFails(setDoc(doc(db(PAS), 'reservas', cancelada, 'mensajes', 'm1'), msj(PAS)));
});
test('perfil público: solo el dueño lo borra', async () => {
  await sembrar({}, {}, { [`perfilesPublicos/${COND}`]: { nombre: 'C', calificacionSuma: 0, calificacionCantidad: 0 } });
  await assertFails(deleteDoc(doc(db(PAS), 'perfilesPublicos', COND)));
  await assertSucceeds(deleteDoc(doc(db(COND), 'perfilesPublicos', COND)));
});
test('perfil: foto y acerca de en el perfil público; celular solo lo ve su dueño', async () => {
  await assertSucceeds(setDoc(doc(db(PAS), 'perfilesPublicos', PAS),
    { nombre: 'P', acercaDe: 'Estudio en la UNSA', foto: 'abc', actualizadoEn: serverTimestamp() }));
  await assertFails(updateDoc(doc(db(PAS), 'perfilesPublicos', PAS), { acercaDe: 'x'.repeat(201), actualizadoEn: serverTimestamp() }));
  await assertSucceeds(setDoc(doc(db(PAS), 'usuarios', PAS), { telefono: '+51 987 654 321', actualizadoEn: serverTimestamp() }));
  await assertFails(setDoc(doc(db(PAS), 'usuarios', PAS), { telefono: 'llámame', actualizadoEn: serverTimestamp() }));
  await assertFails(getDoc(doc(db(COND), 'usuarios', PAS)));
  await assertSucceeds(getDoc(doc(db(PAS), 'usuarios', PAS)));
});
test('perfil: vehículo con año, capacidad y foto; preferencias; capacidad fuera de rango falla', async () => {
  const v = { marca: 'Kia', modelo: 'Rio', color: 'gris', placa: 'V4K-219', anio: '2019', capacidad: 4, foto: 'abc' };
  await assertSucceeds(setDoc(doc(db(COND), 'perfilesPublicos', COND),
    { nombre: 'C', vehiculo: v, preferencias: ['No se fuma'], actualizadoEn: serverTimestamp() }));
  await assertFails(updateDoc(doc(db(COND), 'perfilesPublicos', COND),
    { vehiculo: { ...v, capacidad: 20 }, actualizadoEn: serverTimestamp() }));
});
test('empresa en perfil: declarada libre; dominio solo si coincide con el correo verificado', async () => {
  const conCorreo = (uid, email) => env.authenticatedContext(uid, { email, email_verified: true }).firestore();
  const base2 = { nombre: 'P', empresa: 'UNSA', empresaClave: 'unsa', actualizadoEn: serverTimestamp() };
  await assertSucceeds(setDoc(doc(conCorreo(PAS, 'p@gmail.com'), 'perfilesPublicos', PAS), { ...base2, empresaDominio: '' }));
  await assertFails(setDoc(doc(conCorreo(PAS, 'p@gmail.com'), 'perfilesPublicos', PAS), { ...base2, empresaDominio: 'unsa.edu.pe' }));
  await assertSucceeds(setDoc(doc(conCorreo(COND, 'c@unsa.edu.pe'), 'perfilesPublicos', COND), { ...base2, empresaDominio: 'unsa.edu.pe' }));
  await assertFails(setDoc(doc(conCorreo(OTRO, 'o@gmail.com'), 'perfilesPublicos', OTRO), { ...base2, empresaDominio: 'gmail.com' }));
});
