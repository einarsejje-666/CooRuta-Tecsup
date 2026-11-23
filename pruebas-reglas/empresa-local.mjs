// Administración EXCLUSIVA del emulador. Sin credenciales de producción ni host configurable.
// Invitar no vincula: cada persona debe aceptar en Perfil > Mi empresa.
const base = 'http://127.0.0.1:8080/v1/projects/demo-cooruta/databases/(default)/documents';
const prefijo = 'projects/demo-cooruta/databases/(default)/documents';
const [accion, ...args] = process.argv.slice(2);
const valido = s => typeof s === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(s);
const borrar = (coleccion, uid) => ({ delete: `${prefijo}/${coleccion}/${uid}` });
let writes = [];
if (accion === 'invitar') {
  const [empresa, nombre, ...uids] = args;
  if (!valido(empresa) || !nombre?.trim() || nombre.length > 100 || !uids.length || uids.length > 20 || !uids.every(valido))
    throw Error('Uso: node empresa-local.mjs invitar empresa-demo "Comunidad de prueba" UID [UID…]');
  // No reemplaza pertenencias existentes, ni acepta en nombre del usuario.
  for (const uid of uids) {
    const r = await fetch(`${base}/miembrosEmpresa/${uid}`, { headers: { Authorization: 'Bearer owner' } });
    if (r.ok) throw Error(`La cuenta ${uid} ya está vinculada. Debe salir voluntariamente antes de cambiar.`);
    if (r.status !== 404) throw Error(`No se pudo comprobar la cuenta: ${r.status}`);
  }
  writes.push({ update: { name: `${prefijo}/empresas/${empresa}`, fields: {
    nombre: { stringValue: nombre.trim() }, activa: { booleanValue: true }
  } } });
  for (const uid of new Set(uids)) writes.push({ update: {
    name: `${prefijo}/invitacionesEmpresa/${uid}`, fields: {
      empresaId: { stringValue: empresa }, venceEn: { timestampValue: new Date(Date.now() + 7 * 86400e3).toISOString() }
    }
  } });
} else if (accion === 'revocar' && args.length === 1 && valido(args[0])) {
  writes = [borrar('miembrosEmpresa', args[0]), borrar('invitacionesEmpresa', args[0])];
} else throw Error('Acciones: invitar EMPRESA NOMBRE UID… | revocar UID. Solo demo-cooruta local.');
const respuesta = await fetch(`${base}:commit`, {
  method: 'POST', headers: { Authorization: 'Bearer owner', 'Content-Type': 'application/json' },
  body: JSON.stringify({ writes })
});
if (!respuesta.ok) throw Error(`Emulador local: HTTP ${respuesta.status}`);
console.log(accion === 'invitar' ? 'Invitaciones locales creadas. Deben aceptarse en Perfil → Mi empresa.' : 'Vinculación e invitación locales revocadas.');
