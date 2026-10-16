import { APARTMENT } from '../../../shared/layout.js';
import { heightAt } from '../terrain-data.js';

export function buildBlock(ctx) {
  const cx = 22, cz = 181, y = heightAt(cx, cz - 6), D = 12;
  const doorZ = cz - D / 2;
  ctx.portals.push({ id: 'apt-in', x: cx, y, z: doorZ - 1.3, r: 2.0, label: 'Войти в квартиру', to: { x: APARTMENT.x, y: 0, z: APARTMENT.d / 2 - 1.2, yaw: 0 } });
  return { exit: { x: cx, y, z: doorZ - 3.2, yaw: 0 } };
}

export function buildInterior(ctx, apt) {
  const { x: ox, d } = APARTMENT;
  ctx.portals.push({ id: 'apt-out', x: ox, y: 0, z: d / 2 - 0.5, r: 1.8, label: 'Выйти на улицу', to: { ...apt.exit } });
}
