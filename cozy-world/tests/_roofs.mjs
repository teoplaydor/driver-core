// Помощник тестов вида сверху (WS6): если в сцене ещё нет разметки крыш по контракту C5 (постройки до WS5b),
// помечает крышу беседки и потолок квартиры сам — только в тесте, код игры не трогается.
// С постройками WS5b разметка уже есть, и помощник ничего не меняет.

export async function markLegacyRoofs(page) {
  return page.evaluate(() => {
    const G = window.__cozy.game, sc = G.world.scene;
    let has = false;
    sc.traverse((o) => { if (o.userData.roof) has = true; });
    if (has) return 0;
    let n = 0;
    const mark = (o, x0, x1, z0, z1) => { o.userData.roof = true; o.userData.roofArea = { x0, x1, z0, z1 }; n++; };
    sc.traverse((o) => {
      if (!o.isMesh || !o.geometry?.parameters) return;
      o.updateWorldMatrix(true, false);
      const e = o.matrixWorld.elements, x = e[12], y = e[13], z = e[14], gp = o.geometry.parameters;
      if (Math.hypot(x - 27, z + 19) < 0.5 && y > 4) mark(o, 22.55, 31.45, -23.45, -14.55); // беседка: конус, настил, шпиль
      else if (x > 650 && y > 3 && gp.width > 9 && gp.height < 0.5) mark(o, x - gp.width / 2, x + gp.width / 2, z - gp.depth / 2, z + gp.depth / 2); // потолок квартиры
    });
    G.topView?._scanRoofs?.();
    return n;
  });
}
