// Мягкое матовое освещение для персонажей и рук: «обёрнутый» диффуз (свет заходит за терминатор)
// и тёплый подповерхностный оттенок на переходе в тень — кожа и ткань выглядят мягкими, а не пластиковыми.

import * as THREE from 'three';

const TARGET = 'reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );\n}';
const SOFT = `float softNL = dot( geometryNormal, directLight.direction );
	float softWrap = saturate( ( softNL + 0.5 ) / 1.5 );
	vec3 softWarm = vec3( 1.0, 0.6, 0.48 ) * ( softWrap - dotNL ) * 0.55;
	reflectedLight.directDiffuse += directLight.color * ( softWrap + softWarm ) * BRDF_Lambert( material.diffuseColor );
}`;

const SRC = THREE.ShaderChunk.lights_physical_pars_fragment;
const CHUNK = SRC.includes(TARGET) ? SRC.replace(TARGET, SOFT) : null;

export function softShading(mat) {
  if (!CHUNK) return mat; // другая версия three — остаётся обычное освещение
  mat.onBeforeCompile = (shader) => {
    shader.fragmentShader = shader.fragmentShader.replace('#include <lights_physical_pars_fragment>', CHUNK);
  };
  mat.customProgramCacheKey = () => 'cozy-soft';
  return mat;
}
